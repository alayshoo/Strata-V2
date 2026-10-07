package com.strata.app.domain

import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SourceType
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * One period's change in net worth, split three ways. Saved is income minus spending; new accounts
 * is the opening balance of products first recorded in the period; returns is everything else:
 * price moves, dividends and interest, less fees.
 */
data class GrowthBucket(val date: LocalDate, val saved: Double, val returns: Double, val newAccounts: Double)

data class InstitutionSlice(
    val id: Long,
    val name: String,
    val colorKey: String,
    val value: Double,
    val share: Double,
    /** A bank holding more cash than the EU deposit guarantee covers. */
    val aboveGuarantee: Boolean,
)

/** Signed EUR totals per month; fees are negative. */
data class IncomeFromHoldings(val date: LocalDate, val dividends: Double, val interest: Double, val fees: Double)

data class LargeExpense(
    val id: Long,
    val date: LocalDate,
    val title: String,
    val description: String,
    /** Null when uncategorised. */
    val category: String?,
    val colorKey: String?,
    val amount: Double,
)

data class LastMonthExpenses(val month: LocalDate, val total: Double, val count: Int, val largest: List<LargeExpense>)

/** EU deposit guarantee per person per bank, in EUR. */
const val DEPOSIT_GUARANTEE_EUR = 100_000.0

/**
 * Splits the change in net worth over [start, end] into periods ending on [ends].
 * Periods are (previous end, end], the first starting at [start], so they add up to the whole change.
 */
fun growthBuckets(
    valuator: Valuator,
    productIds: Map<Long, Boolean>,
    liabilityClassIds: Set<Long>,
    flows: List<FlowItem>,
    fx: FxTable,
    start: LocalDate,
    ends: List<LocalDate>,
): List<GrowthBucket> {
    var previous = start
    var netBefore = netWorth(valuator.byAssetClass(start), liabilityClassIds)
    return ends.map { end ->
        val netAfter = netWorth(valuator.byAssetClass(end), liabilityClassIds)
        var saved = BigDecimal.ZERO
        for (f in flows) {
            if (f.date <= previous || f.date > end) continue
            val eur = fx.toEur(f.amount, f.currency, f.date) ?: continue
            saved += eur
        }
        // A product first recorded mid-range arrives with a balance that was neither saved nor earned here.
        var opening = BigDecimal.ZERO
        for ((id, isLiability) in productIds) {
            val first = valuator.firstRecord(id) ?: continue
            if (first <= previous || first > end) continue
            val v = valuator.productValueEur(id, first) ?: continue
            opening += if (isLiability) v.negate() else v
        }
        val change = netAfter - netBefore
        previous = end
        netBefore = netAfter
        GrowthBucket(end, saved.toDouble(), (change - saved - opening).toDouble(), opening.toDouble())
    }
}

/** Assets per institution today, largest first. Colours follow the institutions' order of creation. */
fun institutionSlices(
    valuator: Valuator,
    sources: List<SourceEntity>,
    products: List<Triple<Long, Long, Boolean>>,
    today: LocalDate,
): List<InstitutionSlice> {
    val keys = InstitutionPalette
    val colorBySource = sources.sortedBy { it.id }.mapIndexed { i, s -> s.id to keys[i % keys.size] }.toMap()
    val totals = HashMap<Long, Double>()
    val cash = HashMap<Long, Double>()
    for ((productId, sourceId, isLiability) in products) {
        if (isLiability) continue
        val v = valuator.productValueEur(productId, today)?.toDouble() ?: continue
        totals.merge(sourceId, v, Double::plus)
        if (!valuator.holdsUnits(productId)) cash.merge(sourceId, v, Double::plus)
    }
    val gross = totals.values.filter { it > 0 }.sum()
    return sources.mapNotNull { s ->
        val v = totals[s.id]?.takeIf { it >= 0.5 } ?: return@mapNotNull null
        InstitutionSlice(
            s.id, s.name, colorBySource[s.id] ?: "graphite", v,
            if (gross > 0) v / gross else 0.0,
            s.type == SourceType.BANK && (cash[s.id] ?: 0.0) > DEPOSIT_GUARANTEE_EUR,
        )
    }.sortedByDescending { it.value }
}

/** Graphite stays reserved for "uncategorised" and liabilities. */
private val InstitutionPalette = listOf("cobalt", "saffron", "magenta", "sky", "jade", "coral", "violet", "lime")

fun incomeFromHoldings(
    transactions: List<TransactionEntity>,
    currencyOf: Map<Long, String>,
    fx: FxTable,
    start: LocalDate,
    end: LocalDate,
): List<IncomeFromHoldings> {
    val relevant = transactions.filter { it.kind == TxKind.DIVIDEND || it.kind == TxKind.INTEREST || it.kind == TxKind.FEE }
    return bucketEnds(start, end, Granularity.MONTH).map { monthEnd ->
        val monthStart = maxOf(monthEnd.withDayOfMonth(1), start)
        var dividends = BigDecimal.ZERO
        var interest = BigDecimal.ZERO
        var fees = BigDecimal.ZERO
        for (t in relevant) {
            if (t.date < monthStart || t.date > monthEnd) continue
            val eur = fx.toEur(t.amount, currencyOf[t.productId] ?: FxTable.BASE_CURRENCY, t.date) ?: continue
            when (t.kind) {
                TxKind.DIVIDEND -> dividends += eur
                TxKind.INTEREST -> interest += eur
                else -> fees += eur
            }
        }
        IncomeFromHoldings(monthEnd, dividends.toDouble(), interest.toDouble(), fees.toDouble())
    }
}

/**
 * Share of income kept per complete month in [start, end], as a trailing three-month average
 * weighted by income. Months without income are left out.
 */
fun savingsRateTrend(flows: List<FlowItem>, start: LocalDate, end: LocalDate, fx: FxTable): List<LinePoint> {
    val from = start.withDayOfMonth(1).minusMonths(2)
    val months = monthlyFlows(flows, from, end, fx).filter { it.monthEnd < end && it.monthEnd == it.monthEnd.with(TemporalAdjusters.lastDayOfMonth()) }
    return months.indices.mapNotNull { i ->
        val m = months[i]
        if (m.monthEnd < start) return@mapNotNull null
        val window = months.subList(maxOf(0, i - 2), i + 1)
        val income = window.sumOf { it.income.toDouble() }
        if (income <= 0) return@mapNotNull null
        LinePoint(m.monthEnd, (income - window.sumOf { it.spending.toDouble() }) / income)
    }
}

/** The previous calendar month's spending and its [limit] largest expenses, refunds aside. */
fun lastMonthExpenses(
    transactions: List<TransactionEntity>,
    currencyOf: Map<Long, String>,
    categories: Map<Long, SpendingCategoryEntity>,
    fx: FxTable,
    today: LocalDate,
    limit: Int = 5,
): LastMonthExpenses {
    val monthStart = today.withDayOfMonth(1).minusMonths(1)
    val monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth())
    var total = BigDecimal.ZERO
    val expenses = ArrayList<LargeExpense>()
    for (t in transactions) {
        if (t.kind != TxKind.EXPENSE || t.date < monthStart || t.date > monthEnd) continue
        val eur = fx.toEur(t.amount, currencyOf[t.productId] ?: FxTable.BASE_CURRENCY, t.date) ?: continue
        total -= eur
        if (eur.signum() >= 0) continue
        val title = t.counterparty.ifBlank { t.description }
        val description = if (t.counterparty.isBlank()) "" else t.description
        val category = t.spendingCategoryId?.let { categories[it] }
        expenses += LargeExpense(t.id, t.date, title, description, category?.name, category?.colorKey, -eur.toDouble())
    }
    return LastMonthExpenses(
        monthStart,
        total.toDouble(),
        expenses.size,
        expenses.sortedWith(compareByDescending<LargeExpense> { it.amount }.thenByDescending { it.date }).take(limit),
    )
}
