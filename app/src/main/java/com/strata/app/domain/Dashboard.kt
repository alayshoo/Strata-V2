package com.strata.app.domain

import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import java.math.BigDecimal
import java.time.LocalDate

data class LinePoint(val date: LocalDate, val value: Double)
data class PairedBucket(val date: LocalDate, val first: Double, val second: Double)

data class ClassSlice(
    val id: Long,
    val name: String,
    val colorKey: String,
    val value: Double,
    val share: Double,
    val isLiability: Boolean,
)

/** Per-period values by asset class; liabilities are negative. */
data class ClassBucket(val date: LocalDate, val values: List<Pair<Long, Double>>)

data class CategorySlice(val id: Long?, val name: String, val colorKey: String, val value: Double, val share: Double)

/** Per-month spending by category; null id means uncategorised. */
data class CategoryBucket(val date: LocalDate, val values: List<Pair<Long?, Double>>)

data class DashboardInput(
    val assetClasses: List<AssetClassEntity>,
    val products: List<ProductEntity>,
    val snapshots: List<SnapshotEntity>,
    val transactions: List<TransactionEntity>,
    val categories: List<SpendingCategoryEntity>,
    val fx: FxTable,
)

data class DashboardState(
    val range: TimeRange = TimeRange.Y1,
    val netWorth: Double = 0.0,
    val change: Double = 0.0,
    val changeRatio: Double? = null,
    val slices: List<ClassSlice> = emptyList(),
    val line: List<LinePoint> = emptyList(),
    val bars: List<ClassBucket> = emptyList(),
    val flows: List<PairedBucket> = emptyList(),
    val income: Double = 0.0,
    val spending: Double = 0.0,
    val categories: List<CategorySlice> = emptyList(),
    val categoryBars: List<CategoryBucket> = emptyList(),
    val missingFx: Set<String> = emptySet(),
    val hasData: Boolean = false,
    val hasFlows: Boolean = false,
    val loading: Boolean = true,
)

fun buildDashboard(input: DashboardInput, range: TimeRange, today: LocalDate = LocalDate.now()): DashboardState {
    val liabilityIds = input.assetClasses.filter { it.isLiability }.map { it.id }.toSet()
    val valuator = Valuator(
        input.products.map { ValuedProduct(it.id, it.assetClassId, it.currency) },
        input.snapshots.map { ValuePoint(it.productId, it.date, it.value, it.quantity) },
        input.fx,
        input.transactions.map { ValueFlow(it.productId, it.date, it.amount, it.quantity) },
    )
    val earliest = valuator.earliest
    val start = range.start(today, earliest)

    val nowByClass = valuator.byAssetClass(today)
    val netNow = netWorth(nowByClass, liabilityIds)
    val netStart = netWorth(valuator.byAssetClass(start), liabilityIds)
    val grossAssets = nowByClass.filterKeys { it !in liabilityIds }.values.fold(BigDecimal.ZERO, BigDecimal::add)

    val slices = input.assetClasses.mapNotNull { c ->
        val v = nowByClass[c.id]?.toDouble()?.takeIf { kotlin.math.abs(it) >= 0.005 } ?: return@mapNotNull null
        ClassSlice(c.id, c.name, c.colorKey, v, if (grossAssets.signum() > 0 && !c.isLiability) v / grossAssets.toDouble() else 0.0, c.isLiability)
    }

    // Plot where values actually change; carrying forward daily would draw a staircase.
    val changeDates = (input.snapshots.map { it.date } + input.transactions.map { it.date })
        .filter { it > start && it < today }.plus(listOf(start, today)).distinct().sorted()
    val lineDates = if (changeDates.size <= 400) changeDates else bucketEnds(start, today, lineGranularity(start, today))
    val line = lineDates.map { d ->
        LinePoint(d, netWorth(valuator.byAssetClass(d), liabilityIds).toDouble())
    }
    val order = input.assetClasses.sortedBy { it.sortOrder }.map { it.id }
    val bars = bucketEnds(start, today, barGranularity(start, today)).map { d ->
        val byClass = valuator.byAssetClass(d)
        ClassBucket(d, order.mapNotNull { id ->
            val v = byClass[id]?.toDouble() ?: return@mapNotNull null
            id to if (id in liabilityIds) -v else v
        })
    }

    val currencyOf = input.products.associate { it.id to it.currency }
    val flowItems = input.transactions
        .filter { it.kind == TxKind.EXPENSE || it.kind == TxKind.INCOME }
        .map { FlowItem(it.date, it.amount, currencyOf[it.productId] ?: FxTable.BASE_CURRENCY, it.kind == TxKind.INCOME, it.spendingCategoryId) }
    val flowStart = start.withDayOfMonth(1).let { if (range == TimeRange.ALL) it else maxOf(it, start) }
    val months = monthlyFlows(flowItems, flowStart, today, input.fx)
    val income = months.sumOf { it.income.toDouble() }
    val spending = months.sumOf { it.spending.toDouble() }
    val byCategory = spendingByCategory(flowItems, flowStart, today, input.fx)
    val categoryTotal = byCategory.sumOf { it.second.toDouble() }
    val categoryMap = input.categories.associateBy { it.id }
    val categories = byCategory.map { (id, v) ->
        val c = id?.let { categoryMap[it] }
        CategorySlice(id, c?.name ?: "Uncategorised", c?.colorKey ?: "graphite", v.toDouble(), if (categoryTotal > 0) v.toDouble() / categoryTotal else 0.0)
    }

    // Largest categories sit at the base of every bar, so the stacks read the same month to month.
    val categoryOrder = byCategory.map { it.first }
    val categoryBars = monthlySpendingByCategory(flowItems, flowStart, today, input.fx).map { (monthEnd, totals) ->
        CategoryBucket(monthEnd, categoryOrder.mapNotNull { id ->
            totals[id]?.toDouble()?.takeIf { it > 0 }?.let { id to it }
        })
    }

    val change = netNow - netStart
    return DashboardState(
        range = range,
        netWorth = netNow.toDouble(),
        change = change.toDouble(),
        changeRatio = if (netStart.signum() != 0) change.toDouble() / kotlin.math.abs(netStart.toDouble()) else null,
        slices = slices,
        line = line,
        bars = bars,
        flows = months.map { PairedBucket(it.monthEnd, it.income.toDouble(), it.spending.toDouble()) },
        income = income,
        spending = spending,
        categories = categories,
        categoryBars = categoryBars,
        missingFx = valuator.missingCurrencies.toSet(),
        hasData = input.snapshots.isNotEmpty() || input.transactions.isNotEmpty(),
        hasFlows = flowItems.isNotEmpty(),
        loading = false,
    )
}
