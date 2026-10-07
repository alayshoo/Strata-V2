package com.strata.app.ui.chat

import com.strata.app.ai.ChangeSet
import com.strata.app.ai.ProductPointer
import com.strata.app.data.db.AttachmentEntity
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.db.Role
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.TxKind
import com.strata.app.domain.MoneyFormat
import com.strata.app.ai.Reconcile
import com.strata.app.ai.RoundTrace
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.TransactionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate

data class AttachmentChip(val name: String, val pages: Int)

data class ProposalGroup(val title: String, val lines: List<ProposalLine>)
enum class Tone { NEUTRAL, IN, OUT }

data class ProposalLine(val primary: String, val secondary: String, val amount: String? = null, val tone: Tone = Tone.NEUTRAL)

data class ProposalUi(
    val messageId: Long,
    val json: String,
    val headline: String,
    val status: ProposalStatus,
    val importId: Long?,
    val groups: List<ProposalGroup>,
    /** Balance checks that failed; shown above the rows while the card is pending. */
    val warnings: List<String> = emptyList(),
)

sealed interface ChatItem {
    val key: String

    data class User(val id: Long, val text: String, val attachments: List<AttachmentChip>) : ChatItem {
        override val key = "u$id"
    }

    /** Everything the model did between a user message and its answer. */
    data class Activity(val id: Long, val steps: List<String>, val rounds: List<TraceRound> = emptyList()) : ChatItem {
        override val key = "a$id"
        val totalMs: Long get() = rounds.sumOf { it.durationMs ?: 0L }
        val callCount: Int get() = rounds.sumOf { it.calls.size }
        val errorCount: Int get() = rounds.sumOf { r -> r.calls.count { it.isError } }
        /** Null when no round reported a cost. */
        val totalCost: Double? get() = rounds.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()
    }

    data class Assistant(val id: Long, val text: String, val proposal: ProposalUi?) : ChatItem {
        override val key = "m$id"
    }
}

data class TraceCall(val label: String, val name: String, val arguments: String, val result: String?, val isError: Boolean)

data class TraceRound(
    val round: Int?,
    val durationMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val reasoning: String?,
    /** Text the model wrote alongside its tool calls. */
    val note: String?,
    val calls: List<TraceCall>,
    /** US dollars, when OpenRouter reported it. */
    val cost: Double? = null,
)

data class Lookup(
    val products: List<ProductEntity>,
    val sources: List<SourceEntity>,
    val categories: List<SpendingCategoryEntity>,
    val snapshots: List<SnapshotEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

private val stepNames = mapOf(
    "list_sources" to "Checked sources",
    "list_asset_classes" to "Checked asset classes",
    "list_spending_categories" to "Checked categories",
    "list_products" to "Looked up products",
    "get_snapshots" to "Read balances",
    "get_transactions" to "Read transactions",
    "find_transfer_candidates" to "Matched transfers",
    "get_portfolio" to "Valued the portfolio",
    "get_spending_summary" to "Summarised spending",
    "stage_product" to "Staged a product",
    "stage_snapshots" to "Staged balances",
    "stage_transactions" to "Staged transactions",
    "link_transfer" to "Linked a transfer",
    "sum_transactions" to "Added up transactions",
    "calculate" to "Calculated",
    "get_staged_changes" to "Reviewed the draft",
    "update_staged" to "Corrected the draft",
    "remove_staged" to "Removed from the draft",
    "clear_staged_changes" to "Started the draft over",
)

private fun pretty(raw: String): String = runCatching {
    prettyJson.encodeToString(JsonElement.serializer(), json.parseToJsonElement(raw))
}.getOrDefault(raw)

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun trace(m: MessageEntity): RoundTrace? =
    m.traceJson?.let { runCatching { json.decodeFromString(RoundTrace.serializer(), it) }.getOrNull() }

/**
 * Turns stored messages into what the conversation shows. Tool traffic collapses into one
 * activity item per turn, which can be expanded into a round-by-round trace.
 */
fun buildChatItems(messages: List<MessageEntity>, attachments: List<AttachmentEntity>, lookup: Lookup): List<ChatItem> {
    val byMessage = attachments.groupBy { it.messageId }
    val results = messages.filter { it.role == Role.TOOL && it.toolCallId != null }.associateBy { it.toolCallId }
    val items = mutableListOf<ChatItem>()
    var steps = mutableListOf<String>()
    var rounds = mutableListOf<TraceRound>()
    var activityId = 0L
    fun flush() {
        if (steps.isNotEmpty() || rounds.isNotEmpty()) {
            items += ChatItem.Activity(activityId, steps.distinct(), rounds)
            steps = mutableListOf()
            rounds = mutableListOf()
        }
    }
    for (m in messages) {
        when (m.role) {
            Role.USER -> {
                flush()
                items += ChatItem.User(m.id, m.content, byMessage[m.id].orEmpty().map { AttachmentChip(it.fileName, it.pageCount) })
            }
            Role.TOOL -> Unit
            Role.ASSISTANT -> {
                val t = trace(m)
                if (steps.isEmpty() && rounds.isEmpty()) activityId = m.id
                if (m.toolCallsJson != null) {
                    val calls = runCatching {
                        json.parseToJsonElement(m.toolCallsJson).jsonArray.map { element ->
                            val call = element.jsonObject
                            val function = call["function"]!!.jsonObject
                            val name = function["name"]!!.jsonPrimitive.content
                            val args = function["arguments"]?.let { a -> (a as? JsonPrimitive)?.contentOrNull ?: a.toString() } ?: "{}"
                            val result = results[call["id"]?.jsonPrimitive?.contentOrNull]?.content
                            val label = stepNames[name] ?: name
                            steps += label
                            TraceCall(label, name, pretty(args), result?.let(::pretty), result?.trimStart()?.startsWith("{\"error\"") == true)
                        }
                    }.getOrDefault(emptyList())
                    rounds += TraceRound(t?.round, t?.durationMs, t?.promptTokens, t?.completionTokens, t?.reasoning, m.content.ifBlank { null }, calls, t?.cost)
                } else {
                    if (t != null) rounds += TraceRound(t.round, t.durationMs, t.promptTokens, t.completionTokens, t.reasoning, null, emptyList(), t.cost)
                    flush()
                    items += ChatItem.Assistant(m.id, m.content, m.proposalJson?.let { proposalUi(m, it, lookup) })
                }
            }
        }
    }
    flush()
    return items
}

private fun proposalUi(message: MessageEntity, raw: String, lookup: Lookup): ProposalUi? {
    val changes = runCatching { json.decodeFromString(ChangeSet.serializer(), raw) }.getOrNull() ?: return null
    val products = lookup.products.associateBy { it.id }
    val sources = lookup.sources.associateBy { it.id }
    val categories = lookup.categories.associateBy { it.id }
    val staged = changes.products.associateBy { it.ref }

    fun productName(p: ProductPointer): Pair<String, String> {
        p.id?.let { id -> products[id]?.let { return it.name to it.currency } }
        p.ref?.let { ref -> staged[ref]?.let { return it.name to it.currency } }
        return "Unknown product" to "EUR"
    }

    val groups = buildList {
        if (changes.products.isNotEmpty()) add(ProposalGroup("New products", changes.products.map {
            ProposalLine(it.name, listOfNotNull(sources[it.sourceId]?.name, it.currency, it.identifier.ifBlank { null }).joinToString("  ·  "))
        }))
        if (changes.snapshots.isNotEmpty()) add(ProposalGroup("Balances", changes.snapshots.map {
            val (name, currency) = productName(it.product)
            ProposalLine(name, MoneyFormat.date(LocalDate.parse(it.date)), MoneyFormat.full(BigDecimal(it.value), currency))
        }))
        if (changes.transactions.isNotEmpty()) add(ProposalGroup("Transactions", changes.transactions.map {
            val (name, currency) = productName(it.product)
            val amount = BigDecimal(it.amount)
            val kind = TxKind.valueOf(it.kind)
            val tag = it.spendingCategoryId?.let { id -> categories[id]?.name } ?: kind.label
            ProposalLine(
                it.counterparty.ifBlank { it.description },
                "${MoneyFormat.date(LocalDate.parse(it.date))}  ·  $name  ·  $tag",
                MoneyFormat.signed(amount, currency),
                tone = if (amount.signum() > 0) Tone.IN else Tone.OUT,
            )
        }))
        if (changes.links.isNotEmpty()) add(ProposalGroup("Transfer links", changes.links.map {
            ProposalLine("Link ${it.transactionIds.size} legs", it.transactionIds.joinToString(" ↔ ") { id -> "#$id" })
        }))
    }
    val status = message.proposalStatus ?: ProposalStatus.PENDING
    // Once applied, staged rows are also stored, so checking again would count them twice.
    val warnings = if (status != ProposalStatus.PENDING) emptyList() else buildList {
        Reconcile.checkStaged(changes, lookup.snapshots, lookup.transactions).forEach { f ->
            val name = f.product.id?.let { products[it]?.name } ?: f.product.ref?.let { staged[it]?.name } ?: "A product"
            val currency = f.product.id?.let { products[it]?.currency } ?: f.product.ref?.let { staged[it]?.currency } ?: "EUR"
            val date = MoneyFormat.date(f.date)
            val basis = if (f.since == null) "all its recorded transactions" else "the balance on ${MoneyFormat.date(f.since)} plus the transactions since"
            add(
                if (f.measure == Reconcile.Measure.UNITS)
                    "$name, $date: ${MoneyFormat.quantity(f.stated)} units recorded, but $basis give ${MoneyFormat.quantity(f.implied)}."
                else
                    "$name, $date: the balance says ${MoneyFormat.full(f.stated, currency)}, but $basis add up to ${MoneyFormat.full(f.implied, currency)}."
            )
        }
        val unlinked = Reconcile.unlinkedTrades(changes)
        if (unlinked > 0) add("$unlinked trade leg${if (unlinked == 1) " isn't" else "s aren't"} linked to the other side of the trade.")
    }
    return ProposalUi(
        messageId = message.id,
        json = raw,
        headline = changes.headline().replaceFirstChar { it.uppercase() },
        status = status,
        importId = message.importId,
        groups = groups,
        warnings = warnings,
    )
}
