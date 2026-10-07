package com.strata.app.ui.explorer

import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.ImportEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.domain.ClassBucket
import com.strata.app.domain.FxTable
import com.strata.app.domain.LinePoint
import com.strata.app.domain.TimeRange
import com.strata.app.domain.ValueFlow
import com.strata.app.domain.ValuePoint
import com.strata.app.domain.ValuedProduct
import com.strata.app.domain.Valuator
import com.strata.app.domain.barGranularity
import com.strata.app.domain.bucketEnds
import com.strata.app.domain.lineGranularity
import com.strata.app.domain.netWorth
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

/** [categories] is the kind of spending category that can narrow the filter further. */
enum class TxFilter(val label: String, val kinds: Set<TxKind>?, val categories: FlowKind? = null) {
    ALL("All", null),
    SPENDING("Spending", setOf(TxKind.EXPENSE), FlowKind.EXPENSE),
    INCOME("Income", setOf(TxKind.INCOME, TxKind.DIVIDEND, TxKind.INTEREST), FlowKind.INCOME),
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

/** Category filter value that matches transactions without a spending category. */
const val UNCATEGORISED = -1L

/** [categoryId] narrows Spending or Income to one category, or to [UNCATEGORISED]; null keeps them all. */
fun transactionRows(data: ExplorerData, filter: TxFilter, query: String, categoryId: Long? = null): List<TransactionRowUi> {
    val products = data.products.associateBy { it.id }
    val categories = data.categories.associateBy { it.id }
    val q = query.trim().lowercase()
    val category = categoryId.takeIf { filter.categories != null }
    return data.transactions
        .filter { filter.kinds == null || it.kind in filter.kinds }
        .filter { category == null || it.spendingCategoryId == (if (category == UNCATEGORISED) null else category) }
        .filter { q.isEmpty() || it.description.lowercase().contains(q) || it.counterparty.lowercase().contains(q) }
        .map { TransactionRowUi(it, products[it.productId], it.spendingCategoryId?.let(categories::get)) }
}

/** One institution over time, in EUR. Liabilities count against the total. */
data class SourceHistoryUi(
    val total: Double = 0.0,
    val change: Double = 0.0,
    val changeRatio: Double? = null,
    val line: List<LinePoint> = emptyList(),
    /** Each product's value per period, keyed by product id; liabilities are negative. */
    val bars: List<ClassBucket> = emptyList(),
    val missingFx: Set<String> = emptySet(),
    val hasData: Boolean = false,
)

fun sourceHistory(data: ExplorerData, sourceId: Long, range: TimeRange, today: LocalDate = LocalDate.now()): SourceHistoryUi {
    val classes = data.assetClasses.associateBy { it.id }
    val products = data.products.filter { it.sourceId == sourceId }
        .sortedWith(compareBy({ classes[it.assetClassId]?.sortOrder ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    val ids = products.map { it.id }.toSet()
    val snapshots = data.snapshots.filter { it.productId in ids }
    val transactions = data.transactions.filter { it.productId in ids }
    if (snapshots.isEmpty() && transactions.isEmpty()) return SourceHistoryUi()

    val liabilityIds = data.assetClasses.filter { it.isLiability }.map { it.id }.toSet()
    val valuator = Valuator(
        products.map { ValuedProduct(it.id, it.assetClassId, it.currency) },
        snapshots.map { ValuePoint(it.productId, it.date, it.value, it.quantity) },
        data.fx,
        transactions.map { ValueFlow(it.productId, it.date, it.amount, it.quantity) },
    )
    val start = range.start(today, valuator.earliest)
    fun total(date: LocalDate) = netWorth(valuator.byAssetClass(date), liabilityIds)

    // Same sampling as the dashboard: plot where values change, fall back to buckets when that is too many points.
    val changeDates = (snapshots.map { it.date } + transactions.map { it.date })
        .filter { it > start && it < today }.plus(listOf(start, today)).distinct().sorted()
    val lineDates = if (changeDates.size <= 400) changeDates else bucketEnds(start, today, lineGranularity(start, today))
    val line = lineDates.map { LinePoint(it, total(it).toDouble()) }
    val bars = bucketEnds(start, today, barGranularity(start, today)).map { d ->
        ClassBucket(d, products.mapNotNull { p ->
            val v = valuator.productValueEur(p.id, d)?.toDouble() ?: return@mapNotNull null
            p.id to if (p.assetClassId in liabilityIds) -v else v
        })
    }

    val now = total(today)
    val change = now - total(start)
    val then = now - change
    return SourceHistoryUi(
        total = now.toDouble(),
        change = change.toDouble(),
        changeRatio = if (then.signum() != 0) change.toDouble() / kotlin.math.abs(then.toDouble()) else null,
        line = line,
        bars = bars,
        missingFx = valuator.missingCurrencies.toSet(),
        hasData = true,
    )
}
