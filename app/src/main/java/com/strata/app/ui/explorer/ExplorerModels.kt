package com.strata.app.ui.explorer

import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.ImportEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.domain.FxTable
import java.math.BigDecimal
import java.time.LocalDate

data class ProductRowUi(
    val product: ProductEntity,
    val assetClass: AssetClassEntity?,
    val latest: SnapshotEntity?,
    val valueEur: BigDecimal?,
)

data class SourceGroupUi(val source: SourceEntity, val totalEur: BigDecimal, val products: List<ProductRowUi>)

data class TransactionRowUi(
    val transaction: TransactionEntity,
    val product: ProductEntity?,
    val category: SpendingCategoryEntity?,
)

data class ImportRowUi(val entry: ImportEntity)

enum class TxFilter(val label: String, val kinds: Set<TxKind>?) {
    ALL("All", null),
    SPENDING("Spending", setOf(TxKind.EXPENSE)),
    INCOME("Income", setOf(TxKind.INCOME, TxKind.DIVIDEND, TxKind.INTEREST)),
    MOVES("Transfers", setOf(TxKind.TRANSFER)),
    TRADES("Trades", setOf(TxKind.TRADE)),
    FEES("Fees", setOf(TxKind.FEE)),
}

data class ExplorerData(
    val sources: List<SourceEntity> = emptyList(),
    val assetClasses: List<AssetClassEntity> = emptyList(),
    val categories: List<SpendingCategoryEntity> = emptyList(),
    val products: List<ProductEntity> = emptyList(),
    val snapshots: List<SnapshotEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val imports: List<ImportEntity> = emptyList(),
    val fx: FxTable = FxTable.EMPTY,
)

fun holdings(data: ExplorerData, today: LocalDate = LocalDate.now()): List<SourceGroupUi> {
    val classes = data.assetClasses.associateBy { it.id }
    val latestByProduct = data.snapshots.groupBy { it.productId }.mapValues { (_, s) -> s.maxBy { it.date } }
    return data.sources.map { source ->
        val rows = data.products.filter { it.sourceId == source.id }
            .sortedWith(compareBy({ it.archived }, { it.name.lowercase() }))
            .map { p ->
                val latest = latestByProduct[p.id]
                val eur = latest?.let { data.fx.toEur(it.value, p.currency, today) }
                    ?.let { if (classes[p.assetClassId]?.isLiability == true) it.negate() else it }
                ProductRowUi(p, classes[p.assetClassId], latest, eur)
            }
        SourceGroupUi(source, rows.mapNotNull { it.valueEur }.fold(BigDecimal.ZERO, BigDecimal::add), rows)
    }
}

fun transactionRows(data: ExplorerData, filter: TxFilter, query: String): List<TransactionRowUi> {
    val products = data.products.associateBy { it.id }
    val categories = data.categories.associateBy { it.id }
    val q = query.trim().lowercase()
    return data.transactions
        .filter { filter.kinds == null || it.kind in filter.kinds }
        .filter { q.isEmpty() || it.description.lowercase().contains(q) || it.counterparty.lowercase().contains(q) }
        .map { TransactionRowUi(it, products[it.productId], it.spendingCategoryId?.let(categories::get)) }
}
