package com.strata.app.ai

import com.strata.app.data.db.AttachmentEntity
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.db.Role
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.repo.SettingsRepository
import com.strata.app.domain.FxTable
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
        onProgress: (String) -> Unit,
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

        val executor = ToolExecutor(db, fxTable)
        executor.reset(ChangeSet(fileNames = attachments.map { it.fileName }))
        val history = buildHistory(chatId, currentImages = userMessageId to attachments.flatMap { it.images })
        val messages = history.toMutableList()

        repeat(MAX_ROUNDS) {
            onProgress("Thinking")
            val reply = client.complete(apiKey, model, JsonArray(messages), ToolSpecs.all, privateOnly)
            if (reply.toolCalls.isEmpty()) {
                finish(chatId, reply.content.orEmpty(), executor.staged)
                return
            }
            chatDao.insertMessage(
                MessageEntity(chatId = chatId, role = Role.ASSISTANT, content = reply.content.orEmpty(), toolCallsJson = reply.rawToolCalls.toString())
            )
            messages += assistantJson(reply.content, reply.rawToolCalls)
            for (call in reply.toolCalls) {
                onProgress(executor.describe(call.name))
                val result = executor.execute(call.name, call.arguments)
                chatDao.insertMessage(MessageEntity(chatId = chatId, role = Role.TOOL, content = result, toolCallId = call.id))
                messages += buildJsonObject { put("role", "tool"); put("tool_call_id", call.id); put("content", result) }
            }
        }
        finish(chatId, "I stopped after too many steps. Here is what I staged so far.", executor.staged)
    }

    private suspend fun finish(chatId: Long, content: String, staged: ChangeSet) {
        val proposal = staged.takeUnless { it.isEmpty }
        chatDao.insertMessage(
            MessageEntity(
                chatId = chatId,
                role = Role.ASSISTANT,
                content = content.trim(),
                proposalJson = proposal?.let { json.encodeToString(ChangeSet.serializer(), it) },
                proposalStatus = proposal?.let { ProposalStatus.PENDING },
            )
        )
        chatDao.chat(chatId)?.let { chatDao.updateChat(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    private suspend fun buildHistory(chatId: Long, currentImages: Pair<Long, List<String>>) = buildList {
        add(buildJsonObject { put("role", "system"); put("content", systemPrompt(LocalDate.now())) })
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
                        ProposalStatus.PENDING -> "\n\n[The staged changes from this turn are still waiting for review.]"
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
        const val MAX_ROUNDS = 16

        fun systemPrompt(today: LocalDate) = """
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
            - Amounts stay in the product's currency. The app converts to EUR with ECB rates; record fx_rate only when the statement shows one.

            How to work
            - Start by reading the lists and products you need. Never guess ids.
            - Before staging, check what is already recorded for that product and period to avoid duplicates.
            - Writes are only staged. The user reviews them on a card and taps Apply. Each of your turns produces at most one card.
            - Record a closing balance snapshot for every product a statement covers, at the statement end date.
            - Use '.' as the decimal separator and plain digits, e.g. "-1520.37".
            - If something is ambiguous (which product, which category), stage what is clear and ask about the rest.

            Replies
            - Be brief and concrete. After staging, summarise in a few lines what you staged and anything you were unsure about.
            - Plain sentences and short lists. No tables, no headings, no emoji.
        """.trimIndent()
    }
}
