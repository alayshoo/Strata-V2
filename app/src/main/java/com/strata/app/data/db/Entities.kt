@file:UseSerializers(BigDecimalSerializer::class, LocalDateSerializer::class)

package com.strata.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.LocalDate

enum class SourceType(val label: String) {
    BANK("Bank"),
    BROKER("Broker"),
    EXCHANGE("Exchange"),
    WALLET("Wallet"),
    EMPLOYER("Employer"),
    PENSION("Pension fund"),
    INSURANCE("Insurance"),
    OTHER("Other"),
}

enum class FlowKind(val label: String) { EXPENSE("Expense"), INCOME("Income") }

/**
 * What a transaction means. Only EXPENSE and INCOME take a spending category and count
 * toward spending; TRANSFER and TRADE move money between the user's own products.
 */
enum class TxKind(val label: String) {
    EXPENSE("Expense"),
    INCOME("Income"),
    TRANSFER("Transfer"),
    TRADE("Trade"),
    DIVIDEND("Dividend"),
    INTEREST("Interest"),
    FEE("Fee"),
}

enum class Role { USER, ASSISTANT, TOOL }

/** SUPERSEDED: a pending card carried into a later turn and replaced by that turn's card. */
enum class ProposalStatus { PENDING, APPLIED, DISCARDED, UNDONE, SUPERSEDED }

@Entity(tableName = "sources", indices = [Index(value = ["name"], unique = true)])
@Serializable
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "asset_classes", indices = [Index(value = ["name"], unique = true)])
@Serializable
data class AssetClassEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Key into the curated palette, see ui.theme.SeriesPalette. */
    val colorKey: String,
    val isLiability: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(tableName = "spending_categories", indices = [Index(value = ["name"], unique = true)])
@Serializable
data class SpendingCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: FlowKind,
    val colorKey: String,
)

@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(SourceEntity::class, ["id"], ["sourceId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AssetClassEntity::class, ["id"], ["assetClassId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("sourceId"), Index("assetClassId"), Index("importId")],
)
@Serializable
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val assetClassId: Long,
    val name: String,
    /** ISO 4217 code; every snapshot and transaction of this product is in this currency. */
    val currency: String,
    /** IBAN tail, ISIN, ticker: whatever identifies it on statements. */
    val identifier: String = "",
    val archived: Boolean = false,
    val importId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "snapshots",
    foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["productId", "date"], unique = true), Index("importId")],
)
@Serializable
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val date: LocalDate,
    /** Value in the product's currency. Liabilities store the amount owed as a positive number. */
    val value: BigDecimal,
    val quantity: BigDecimal? = null,
    val unitPrice: BigDecimal? = null,
    val note: String = "",
    val importId: Long? = null,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SpendingCategoryEntity::class, ["id"], ["spendingCategoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("productId"), Index("date"), Index("spendingCategoryId"), Index("transferGroup"), Index("importId")],
)
@Serializable
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val date: LocalDate,
    /** Signed, in the product's currency, from the product's point of view. */
    val amount: BigDecimal,
    val description: String,
    val counterparty: String = "",
    val kind: TxKind,
    val spendingCategoryId: Long? = null,
    /** Shared by the legs of one transfer or trade. */
    val transferGroup: String? = null,
    /** Units bought (+) or sold (−) on the security leg of a trade. */
    val quantity: BigDecimal? = null,
    /** Rate the institution applied, units of this currency per 1 EUR. */
    val fxRate: BigDecimal? = null,
    val importId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "imports")
@Serializable
data class ImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long?,
    val fileNames: String,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
    val undoneAt: Long? = null,
)

@Entity(tableName = "fx_rates", primaryKeys = ["date", "currency"])
data class FxRateEntity(
    val date: LocalDate,
    val currency: String,
    /** Units of [currency] per 1 EUR (ECB convention). */
    val perEur: BigDecimal,
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chatId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: Role,
    val content: String,
    /** Assistant tool calls, as the OpenAI-style JSON array. */
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    /** Staged writes awaiting review, serialized ChangeSet. */
    val proposalJson: String? = null,
    val proposalStatus: ProposalStatus? = null,
    val importId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** How the model call that produced this message went, serialized RoundTrace. */
    val traceJson: String? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val fileName: String,
    val mimeType: String,
    val extractedText: String,
    val pageCount: Int = 0,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
