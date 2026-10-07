package com.strata.app.ai

import kotlinx.serialization.Serializable

/**
 * Writes the assistant has staged. Nothing touches the ledger until the user taps Apply on the
 * review card; then [com.strata.app.data.repo.LedgerRepository.apply] writes everything in one
 * database transaction tagged with a single import id. A card still pending when the user writes
 * again carries into the next turn, so the assistant can correct single items by their staged id.
 * Corrections to balances already recorded are staged here too, with the recorded values they replace.
 */
@Serializable
data class ChangeSet(
    val products: List<NewProduct> = emptyList(),
    val snapshots: List<NewSnapshot> = emptyList(),
    val transactions: List<NewTransaction> = emptyList(),
    val links: List<TransferLink> = emptyList(),
    val snapshotEdits: List<SnapshotEdit> = emptyList(),
    val snapshotDeletions: List<SnapshotDeletion> = emptyList(),
    val fileNames: List<String> = emptyList(),
    /** Next staged id to hand out; ids are unique across every kind of item. */
    val nextId: Int = 1,
) {
    val isEmpty: Boolean get() = products.isEmpty() && snapshots.isEmpty() && transactions.isEmpty() && links.isEmpty() &&
        snapshotEdits.isEmpty() && snapshotDeletions.isEmpty()

    /** Every staged id in use. */
    val ids: List<Int> get() = products.map { it.id } + snapshots.map { it.id } + transactions.map { it.id } + links.map { it.id } +
        snapshotEdits.map { it.id } + snapshotDeletions.map { it.id }

    /** Gives an id to items staged before items had one. */
    fun withIds(): ChangeSet {
        var next = maxOf(nextId, (ids.maxOrNull() ?: 0) + 1)
        val products = products.map { if (it.id == 0) it.copy(id = next++) else it }
        val snapshots = snapshots.map { if (it.id == 0) it.copy(id = next++) else it }
        val transactions = transactions.map { if (it.id == 0) it.copy(id = next++) else it }
        val links = links.map { if (it.id == 0) it.copy(id = next++) else it }
        val snapshotEdits = snapshotEdits.map { if (it.id == 0) it.copy(id = next++) else it }
        val snapshotDeletions = snapshotDeletions.map { if (it.id == 0) it.copy(id = next++) else it }
        return copy(
            products = products, snapshots = snapshots, transactions = transactions, links = links,
            snapshotEdits = snapshotEdits, snapshotDeletions = snapshotDeletions, nextId = next,
        )
    }

    /** True when applying changes or removes rows already recorded. */
    val rewritesRecorded: Boolean get() = snapshotEdits.isNotEmpty() || snapshotDeletions.isNotEmpty()

    fun headline(): String = buildList {
        // Changes to recorded data lead, and new balances say they are new, so neither is mistaken for the other.
        if (snapshotEdits.isNotEmpty()) add(plural(snapshotEdits.size, "recorded balance corrected", "recorded balances corrected"))
        if (snapshotDeletions.isNotEmpty()) add(plural(snapshotDeletions.size, "recorded balance removed", "recorded balances removed"))
        val fresh = if (rewritesRecorded) "new " else ""
        if (snapshots.isNotEmpty()) add(plural(snapshots.size, "${fresh}balance", "${fresh}balances"))
        if (transactions.isNotEmpty()) add(plural(transactions.size, "transaction", "transactions"))
        if (products.isNotEmpty()) add(plural(products.size, "new product", "new products"))
        if (links.isNotEmpty()) add(plural(links.size, "transfer link", "transfer links"))
    }.joinToString(", ")

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}

@Serializable
data class NewProduct(
    val ref: String,
    val sourceId: Long,
    val assetClassId: Long,
    val name: String,
    val currency: String,
    val identifier: String = "",
    val id: Int = 0,
)

/** Points at an existing product by id, or at a staged one by its ref. */
@Serializable
data class ProductPointer(val id: Long? = null, val ref: String? = null)

@Serializable
data class NewSnapshot(
    val product: ProductPointer,
    val date: String,
    val value: String,
    val quantity: String? = null,
    val unitPrice: String? = null,
    val note: String = "",
    val id: Int = 0,
)

@Serializable
data class NewTransaction(
    val product: ProductPointer,
    val date: String,
    val amount: String,
    val description: String,
    val counterparty: String = "",
    val kind: String,
    val spendingCategoryId: Long? = null,
    /** Legs staged in this change set that share a key are linked as one transfer. */
    val transferKey: String? = null,
    /** Links this leg to a transaction already in the database. */
    val linkToTransactionId: Long? = null,
    val quantity: String? = null,
    val fxRate: String? = null,
    val id: Int = 0,
)

@Serializable
data class TransferLink(val transactionIds: List<Long>, val id: Int = 0)

/** A balance as it is, or will be, recorded. */
@Serializable
data class RecordedSnapshot(
    val productId: Long,
    val date: String,
    val value: String,
    val quantity: String? = null,
    val unitPrice: String? = null,
    val note: String = "",
)

/** A correction to recorded balance [snapshotId]: [before] is what was recorded when it was staged. */
@Serializable
data class SnapshotEdit(val snapshotId: Long, val before: RecordedSnapshot, val after: RecordedSnapshot, val id: Int = 0)

/** Removes recorded balance [snapshotId], which held [before] when it was staged. */
@Serializable
data class SnapshotDeletion(val snapshotId: Long, val before: RecordedSnapshot, val id: Int = 0)
