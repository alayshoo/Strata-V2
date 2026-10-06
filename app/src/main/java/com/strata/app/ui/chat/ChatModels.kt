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
import kotlinx.serialization.json.Json
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
)

sealed interface ChatItem {
    val key: String

    data class User(val id: Long, val text: String, val attachments: List<AttachmentChip>) : ChatItem {
        override val key = "u$id"
    }

    data class Activity(val id: Long, val steps: List<String>) : ChatItem {
        override val key = "a$id"
    }

    data class Assistant(val id: Long, val text: String, val proposal: ProposalUi?) : ChatItem {
        override val key = "m$id"
    }
}

data class Lookup(
    val products: List<ProductEntity>,
    val sources: List<SourceEntity>,
    val categories: List<SpendingCategoryEntity>,
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
    "get_staged_changes" to "Reviewed the draft",
    "clear_staged_changes" to "Started the draft over",
)

/** Turns stored messages into what the conversation shows; tool traffic collapses into activity lines. */
fun buildChatItems(messages: List<MessageEntity>, attachments: List<AttachmentEntity>, lookup: Lookup): List<ChatItem> {
    val byMessage = attachments.groupBy { it.messageId }
    val items = mutableListOf<ChatItem>()
    var pendingSteps = mutableListOf<String>()
    var activityId = 0L
    fun flushSteps() {
        if (pendingSteps.isNotEmpty()) {
            items += ChatItem.Activity(activityId, pendingSteps.distinct())
            pendingSteps = mutableListOf()
        }
    }
    for (m in messages) {
        when (m.role) {
            Role.USER -> {
                flushSteps()
                items += ChatItem.User(m.id, m.content, byMessage[m.id].orEmpty().map { AttachmentChip(it.fileName, it.pageCount) })
            }
            Role.TOOL -> Unit
            Role.ASSISTANT -> {
                if (m.toolCallsJson != null) {
                    if (pendingSteps.isEmpty()) activityId = m.id
                    runCatching {
                        json.parseToJsonElement(m.toolCallsJson).jsonArray.forEach { call ->
                            val name = call.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                            pendingSteps += stepNames[name] ?: name
                        }
                    }
                } else {
                    flushSteps()
                    items += ChatItem.Assistant(m.id, m.content, m.proposalJson?.let { proposalUi(m, it, lookup) })
                }
            }
        }
    }
    flushSteps()
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
    return ProposalUi(
        messageId = message.id,
        json = raw,
        headline = changes.headline().replaceFirstChar { it.uppercase() },
        status = message.proposalStatus ?: ProposalStatus.PENDING,
        importId = message.importId,
        groups = groups,
    )
}
