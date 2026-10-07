package com.strata.app.ai

import kotlinx.serialization.Serializable

/**
 * Writes the assistant has staged during one turn. Nothing touches the ledger until the user
 * taps Apply on the review card; then [com.strata.app.data.repo.LedgerRepository.apply]
 * writes everything in one database transaction tagged with a single import id.
 */
@Serializable
data class ChangeSet(
    val products: List<NewProduct> = emptyList(),
    val snapshots: List<NewSnapshot> = emptyList(),
    val transactions: List<NewTransaction> = emptyList(),
    val links: List<TransferLink> = emptyList(),
    val fileNames: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = products.isEmpty() && snapshots.isEmpty() && transactions.isEmpty() && links.isEmpty()

    fun headline(): String = buildList {
        if (snapshots.isNotEmpty()) add(plural(snapshots.size, "balance", "balances"))
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
)

@Serializable
data class TransferLink(val transactionIds: List<Long>)
