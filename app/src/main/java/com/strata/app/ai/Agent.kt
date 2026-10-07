package com.strata.app.ai

import com.strata.app.data.db.AttachmentEntity
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.db.Role
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.repo.SettingsRepository
import com.strata.app.domain.FxTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/** What the UI shows while a turn runs. */
data class RunProgress(val label: String, val round: Int, val since: Long)

/** One request to the model: how long it took, what it cost, and its reasoning if exposed. */
@Serializable
data class RoundTrace(
    val round: Int,
    val model: String,
    val durationMs: Long,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoning: String? = null,
    val finishReason: String? = null,
    /** US dollars, as OpenRouter reported it. */
    val cost: Double? = null,
)

/**
 * Runs one user turn: sends the conversation to the model, executes tool calls until it answers,
 * and stores everything. Writes end up as a pending review card on the final assistant message.
 */
class Agent(
    private val db: StrataDatabase,
    private val client: OpenRouterClient,
    private val settings: SettingsRepository,
    private val fxTable: suspend () -> FxTable,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val chatDao = db.chatDao()

    suspend fun send(
        chatId: Long,
        text: String,
        attachments: List<PreparedAttachment>,
        onProgress: (RunProgress) -> Unit,
    ) {
        val apiKey = settings.apiKey() ?: throw OpenRouterException("Add your OpenRouter API key in Setup first.")
        val model = settings.model()
        val privateOnly = settings.privateProvidersOnly()

        val userMessageId = chatDao.insertMessage(MessageEntity(chatId = chatId, role = Role.USER, content = text))
        attachments.forEach {
            chatDao.insertAttachment(AttachmentEntity(messageId = userMessageId, fileName = it.fileName, mimeType = it.mimeType, extractedText = it.text, pageCount = it.pageCount))
        }
        chatDao.chat(chatId)?.let { chat ->
            val title = if (chat.title == "New chat") titleFor(text, attachments) else chat.title
            chatDao.updateChat(chat.copy(title = title, updatedAt = System.currentTimeMillis()))
        }

        // A card still waiting for review carries into this turn, so the model can correct single items in it.
        val carried = chatDao.messages(chatId).lastOrNull { it.proposalStatus == ProposalStatus.PENDING && it.proposalJson != null }
        val carriedChanges = carried?.proposalJson?.let { runCatching { json.decodeFromString(ChangeSet.serializer(), it) }.getOrNull() }
        val executor = ToolExecutor(db, fxTable)
        executor.reset(
            (carriedChanges ?: ChangeSet()).let { it.copy(fileNames = (it.fileNames + attachments.map { a -> a.fileName }).distinct()) }
        )
        val draft = Draft(carried?.id?.takeIf { carriedChanges != null }, executor.staged)
        val history = buildHistory(chatId, currentImages = userMessageId to attachments.flatMap { it.images })
        val messages = history.toMutableList()

        var round = 0
        try {
            while (round < MAX_ROUNDS) {
                round++
                val startedAt = System.currentTimeMillis()
                onProgress(RunProgress("Waiting for the model", round, startedAt))
                val reply = client.complete(apiKey, model, JsonArray(messages), ToolSpecs.all, privateOnly)
                val trace = RoundTrace(
                    round = round,
                    model = model,
                    durationMs = System.currentTimeMillis() - startedAt,
                    promptTokens = reply.promptTokens,
                    completionTokens = reply.completionTokens,
                    reasoning = reply.reasoning,
                    finishReason = reply.finishReason,
                    cost = reply.cost,
                )
                reply.cost?.let { chatDao.addCost(chatId, it) }
                if (reply.toolCalls.isEmpty()) {
                    finish(chatId, reply.content.orEmpty(), executor.staged, draft, trace)
                    return
                }
                // Tool calls and their results are written together so a stop never leaves
                // a call without a result, which would make the history invalid for the next turn.
                withContext(NonCancellable) {
                    chatDao.insertMessage(
                        MessageEntity(
                            chatId = chatId, role = Role.ASSISTANT, content = reply.content.orEmpty(),
                            toolCallsJson = reply.rawToolCalls.toString(), traceJson = json.encodeToString(RoundTrace.serializer(), trace),
                        )
                    )
                    messages += assistantJson(reply.content, reply.rawToolCalls)
                    for (call in reply.toolCalls) {
                        onProgress(RunProgress(executor.describe(call.name), round, System.currentTimeMillis()))
                        val result = executor.execute(call.name, call.arguments)
                        chatDao.insertMessage(MessageEntity(chatId = chatId, role = Role.TOOL, content = result, toolCallId = call.id))
                        messages += buildJsonObject { put("role", "tool"); put("tool_call_id", call.id); put("content", result) }
                    }
                }
            }
            finish(chatId, "I stopped after $MAX_ROUNDS rounds without finishing. Here is what I staged so far.", executor.staged, draft, null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val changed = executor.staged != draft.initial
                finish(chatId, STOPPED_MESSAGE + if (executor.staged.isEmpty || !changed) "" else " What was staged before that is below.", executor.staged, draft, null)
            }
            throw e
        } catch (e: Exception) {
            // A timeout or provider error should not throw away batches already staged in this turn.
            if (!executor.staged.isEmpty && executor.staged != draft.initial) {
                withContext(NonCancellable) {
                    finish(chatId, "The model stopped responding (${e.message ?: "error"}). Here is what was staged before that; you can apply it and ask me to continue.", executor.staged, draft, null)
                }
            }
            throw e
        }
    }

    /** The card a turn started from: the carried-over pending card, if any, and what was staged at the start. */
    private class Draft(val carriedMessageId: Long?, val initial: ChangeSet)

    private suspend fun finish(chatId: Long, content: String, staged: ChangeSet, draft: Draft, trace: RoundTrace?) {
        // An untouched carried card stays where it is; a changed one is replaced by the new card.
        val changed = staged != draft.initial
        val proposal = staged.takeUnless { it.isEmpty || !changed }
        if (changed) draft.carriedMessageId?.let { id ->
            chatDao.message(id)?.takeIf { it.proposalStatus == ProposalStatus.PENDING }?.let {
                chatDao.updateMessage(it.copy(proposalStatus = ProposalStatus.SUPERSEDED))
            }
        }
        chatDao.insertMessage(
            MessageEntity(
                chatId = chatId,
                role = Role.ASSISTANT,
                content = content.trim(),
                proposalJson = proposal?.let { json.encodeToString(ChangeSet.serializer(), it) },
                proposalStatus = proposal?.let { ProposalStatus.PENDING },
                traceJson = trace?.let { json.encodeToString(RoundTrace.serializer(), it) },
            )
        )
        chatDao.chat(chatId)?.let { chatDao.updateChat(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    private suspend fun buildHistory(chatId: Long, currentImages: Pair<Long, List<String>>) = buildList {
        add(buildJsonObject { put("role", "system"); put("content", systemPrompt(LocalDate.now(), settings.userNote())) })
        val attachments = chatDao.attachments(chatId).groupBy { it.messageId }
        for (message in chatDao.messages(chatId)) {
            when (message.role) {
                Role.USER -> add(userJson(message, attachments[message.id].orEmpty(), currentImages.takeIf { it.first == message.id }?.second.orEmpty()))
                Role.ASSISTANT -> {
                    val calls = message.toolCallsJson?.let { json.parseToJsonElement(it).jsonArray }
                    val note = when (message.proposalStatus) {
                        ProposalStatus.APPLIED -> "\n\n[The user applied the staged changes from this turn.]"
                        ProposalStatus.DISCARDED -> "\n\n[The user discarded the staged changes from this turn.]"
                        ProposalStatus.UNDONE -> "\n\n[The user applied, then undid, the staged changes from this turn.]"
                        ProposalStatus.PENDING -> "\n\n[The staged changes from this turn are still waiting for review. They carry into your next turn: " +
                            "get_staged_changes lists them with staged ids, and update_staged or remove_staged correct single items.]"
                        ProposalStatus.SUPERSEDED -> "\n\n[The staged changes from this turn were carried into a later turn and replaced by its card.]"
                        null -> ""
                    }
                    add(assistantJson((message.content + note).ifBlank { null }, calls))
                }
                Role.TOOL -> add(buildJsonObject { put("role", "tool"); put("tool_call_id", message.toolCallId.orEmpty()); put("content", message.content) })
            }
        }
    }

    private fun userJson(message: MessageEntity, attachments: List<AttachmentEntity>, images: List<String>) = buildJsonObject {
        put("role", "user")
        val text = buildString {
            append(message.content)
            for (a in attachments) {
                append("\n\n<document name=\"${a.fileName}\"")
                if (a.pageCount > 0) append(" pages=\"${a.pageCount}\"")
                append(">\n")
                append(a.extractedText.ifBlank { if (images.isNotEmpty()) "(scanned pages attached as images)" else "(scanned document shown earlier; images are no longer available)" })
                append("\n</document>")
            }
        }
        if (images.isEmpty()) put("content", text)
        else putJsonArray("content") {
            addJsonObject { put("type", "text"); put("text", text) }
            images.forEach { url -> addJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", url) } } }
        }
    }

    private fun assistantJson(content: String?, toolCalls: JsonArray?) = buildJsonObject {
        put("role", "assistant")
        if (content.isNullOrBlank()) put("content", JsonNull) else put("content", content)
        if (toolCalls != null && toolCalls.isNotEmpty()) put("tool_calls", toolCalls)
    }

    private fun titleFor(text: String, attachments: List<PreparedAttachment>): String {
        val base = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: attachments.firstOrNull()?.fileName ?: "New chat"
        return if (base.length > 48) base.take(47).trimEnd() + "…" else base
    }

    companion object {
        /** Batched staging takes many short rounds; the user can always stop a turn. */
        const val MAX_ROUNDS = 40
        const val STOPPED_MESSAGE = "Stopped."

        fun systemPrompt(today: LocalDate, userNote: String = ""): String = buildString {
            append(basePrompt(today))
            if (userNote.isNotBlank()) {
                append("\n\n")
                append(
                    """
                    Notes from the user about their setup
                    The user wrote these to tell you how their accounts and statements work. Follow them. They add to the rules
                    above and settle conventions and judgement calls, but they never let you create sources, asset classes or
                    categories, or write without a review card.
                    """.trimIndent()
                )
                append("\n\n")
                append(userNote.trim())
            }
        }

        private fun basePrompt(today: LocalDate) = """
            You are the assistant inside Strata, a private personal-finance app on the user's phone. Today is $today.
            The user shares statements (PDF text, CSV, scans) from banks, brokers and employers, and asks questions about their money.

            The ledger
            - Sources are institutions. Asset classes group products (cash, ETFs, credit...). Spending categories classify income and expenses.
              The user manages these three lists in Setup. You can never create them: if one is missing, say exactly what to add and stop.
            - Products are accounts or holdings at a source, each in one currency. You may stage new products, but prefer existing ones.
            - Snapshots are the value of a product on a date (closing balance, or market value for securities, with quantity and unit price
              when shown). One per product per day. Liabilities such as credit cards store the amount owed as a positive number.
            - Transactions are flows on a product, signed from that product's point of view (money out is negative). Kinds:
              expense and income (real money leaving or entering the user's world, take a spending category),
              transfer (between the user's own products, even across institutions), trade (buy/sell: the cash leg and the security leg,
              with quantity on the security leg), dividend, interest, fee.
            - Link both legs of a transfer or trade. Within one turn give both legs the same transfer_key. When only one side is in this
              statement, call find_transfer_candidates and use link_to_transaction_id if the other side already exists.
            - A trade is always two legs with the same transfer_key: on the cash product the signed cash amount (a buy is negative),
              and on the security product the opposite amount (a buy is positive, the cost) with the units in quantity
              (negative when selling). Broker fees and taxes are separate fee transactions on the cash product.
              A row's effect on cash is its amount plus its fee plus its tax.
            - Amounts stay in the product's currency. The app converts to EUR with ECB rates; record fx_rate only when the statement shows one.

            Numbers
            - Never do arithmetic in your head. Use sum_transactions for totals over many rows (after staging them), and calculate
              for small one-off sums such as units times price.
            - Balances: use the closing balance the statement prints. If it prints none (many CSV exports), and the file covers the
              account from its first movement, the closing cash balance is sum_amount from sum_transactions for that product;
              for a security, the units held are sum_quantity. Say in the reply that you derived it.
            - Staging tools return "checks". A MISMATCH means a balance disagrees with its transactions. Never ignore it: correct
              the items involved with update_staged or remove_staged, or tell the user plainly why they differ.

            Time and size limits
            - Each of your responses must finish within about 5 minutes or it is cut off and lost. A tool call with a long list
              of items, or a long stretch of thinking, is what runs out of time.
            - Stage in small batches: about 20 items per stage_transactions or stage_snapshots call, then call again for the
              next batch. Everything staged during this turn adds up into one review card, so there is no need to do it in
              one go. More, smaller calls are always better than one big call.
            - Read only what you need. Give get_transactions a product_id and a narrow from/to range, e.g. the statement's
              period. Do not read a product's whole history to check for duplicates.
            - Keep your reasoning short and to the point.

            How to work
            - Start by reading the lists and products you need. Never guess ids.
            - Before staging, check what is already recorded for that product and period to avoid duplicates.
            - Writes are only staged. The user reviews them on a card and taps Apply. Each of your turns produces at most one card.
            - Every staged item has a staged_id. A card still waiting for review carries into your next turn, and anything you
              stage then is added to it. When the user asks for corrections, call get_staged_changes (filtered to the product
              or dates involved), then fix only the affected items with update_staged or remove_staged. Never clear and restage
              everything to fix a few items; clear_staged_changes is only for starting over completely.
            - Record a closing balance snapshot for every product a statement covers, at the statement end date.
            - When the user says a recorded balance is wrong (often after spotting a jump in a chart), find it with get_snapshots
              and stage the fix with edit_recorded_snapshots, or delete_recorded_snapshots for a balance that should not exist.
              Change only what they asked for, and check the result against the transactions like any staged balance. Never
              stage a second balance on a day that already has one; correct the recorded one instead.
            - Use '.' as the decimal separator and plain digits, e.g. "-1520.37".
            - If something is ambiguous (which product, which category), stage what is clear and ask about the rest.

            Replies
            - Be brief and concrete. After staging, summarise in a few lines what you staged and anything you were unsure about.
            - Plain sentences and short lists. No tables, no headings, no emoji.
        """.trimIndent()
    }
}
