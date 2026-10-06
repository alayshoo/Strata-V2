package com.strata.app.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class TimeRange(val label: String) {
    ALL("All"),
    Y1("1Y"),
    YTD("YTD"),
    M6("6M"),
    M3("3M");

    fun start(today: LocalDate, earliest: LocalDate?): LocalDate = when (this) {
        ALL -> minOf(earliest ?: today.minusMonths(1), today.minusMonths(1))
        Y1 -> today.minusYears(1)
        YTD -> today.withDayOfYear(1)
        M6 -> today.minusMonths(6)
        M3 -> today.minusMonths(3)
    }
}

enum class Granularity { DAY, WEEK, MONTH }

/** Bucket end dates, oldest first; the last bucket always ends today. */
fun bucketEnds(start: LocalDate, end: LocalDate, granularity: Granularity): List<LocalDate> {
    if (end < start) return listOf(end)
    return when (granularity) {
        Granularity.DAY -> generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.toList()
        Granularity.WEEK -> generateSequence(end) { it.minusWeeks(1) }.takeWhile { it >= start }.toList().reversed()
        Granularity.MONTH -> {
            val months = generateSequence(start.with(TemporalAdjusters.lastDayOfMonth())) {
                it.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())
            }.takeWhile { it < end }.toList()
            months + end
        }
    }
}

fun barGranularity(start: LocalDate, end: LocalDate): Granularity =
    if (ChronoUnit.DAYS.between(start, end) > 200) Granularity.MONTH else Granularity.WEEK

fun lineGranularity(start: LocalDate, end: LocalDate): Granularity =
    if (ChronoUnit.DAYS.between(start, end) > 400) Granularity.WEEK else Granularity.DAY

/** Converts amounts to EUR using ECB-style rates (units of currency per 1 EUR). */
class FxTable(rates: Map<String, List<Pair<LocalDate, BigDecimal>>>) {
    private val byCurrency = rates.mapValues { (_, list) -> list.sortedBy { it.first } }

    fun perEur(currency: String, date: LocalDate): BigDecimal? {
        if (currency == BASE_CURRENCY) return BigDecimal.ONE
        val list = byCurrency[currency]?.takeIf { it.isNotEmpty() } ?: return null
        var lo = 0
        var hi = list.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].first <= date) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        // Before the first published rate we fall back to the earliest one we have.
        return list[if (found >= 0) found else 0].second
    }

    fun toEur(amount: BigDecimal, currency: String, date: LocalDate): BigDecimal? {
        val rate = perEur(currency, date) ?: return null
        return if (rate.signum() == 0) null else amount.divide(rate, MathContext.DECIMAL64)
    }

    companion object {
        const val BASE_CURRENCY = "EUR"
        val EMPTY = FxTable(emptyMap())
    }
}

data class ValuedProduct(val id: Long, val assetClassId: Long, val currency: String)
data class ValuePoint(val productId: Long, val date: LocalDate, val value: BigDecimal)

/** Values every product at a date by carrying its latest snapshot forward. */
class Valuator(
    products: List<ValuedProduct>,
    snapshots: List<ValuePoint>,
    private val fx: FxTable,
) {
    private val products = products.associateBy { it.id }
    private val history: Map<Long, List<ValuePoint>> =
        snapshots.filter { it.productId in this.products }.groupBy { it.productId }
            .mapValues { (_, list) -> list.sortedBy { it.date } }

    val earliest: LocalDate? = snapshots.minOfOrNull { it.date }

    /** Currencies that had no rate when we needed one. */
    val missingCurrencies = mutableSetOf<String>()

    fun latestOnOrBefore(productId: Long, date: LocalDate): ValuePoint? {
        val list = history[productId] ?: return null
        var lo = 0
        var hi = list.lastIndex
        var found: ValuePoint? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].date <= date) { found = list[mid]; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** EUR value per asset class at [date]; positive numbers, liabilities included as owed. */
    fun byAssetClass(date: LocalDate): Map<Long, BigDecimal> {
        val out = HashMap<Long, BigDecimal>()
        for (product in products.values) {
            val point = latestOnOrBefore(product.id, date) ?: continue
            val eur = fx.toEur(point.value, product.currency, date)
            if (eur == null) { missingCurrencies += product.currency; continue }
            out.merge(product.assetClassId, eur, BigDecimal::add)
        }
        return out
    }

    fun productValueEur(productId: Long, date: LocalDate): BigDecimal? {
        val product = products[productId] ?: return null
        val point = latestOnOrBefore(productId, date) ?: return null
        return fx.toEur(point.value, product.currency, date)
    }
}

fun netWorth(byClass: Map<Long, BigDecimal>, liabilityClassIds: Set<Long>): BigDecimal =
    byClass.entries.fold(BigDecimal.ZERO) { acc, (classId, value) ->
        if (classId in liabilityClassIds) acc - value else acc + value
    }

data class MonthFlow(val monthEnd: LocalDate, val income: BigDecimal, val spending: BigDecimal)
data class FlowItem(
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val isIncome: Boolean,
    val categoryId: Long?,
)

/** Monthly income and spending in EUR. Refunds on expenses reduce spending. */
fun monthlyFlows(items: List<FlowItem>, start: LocalDate, end: LocalDate, fx: FxTable): List<MonthFlow> {
    val months = bucketEnds(start, end, Granularity.MONTH)
    return months.map { monthEnd ->
        val monthStart = monthEnd.withDayOfMonth(1)
        var income = BigDecimal.ZERO
        var spending = BigDecimal.ZERO
        for (item in items) {
            if (item.date < monthStart || item.date > monthEnd || item.date < start) continue
            val eur = fx.toEur(item.amount, item.currency, item.date) ?: continue
            if (item.isIncome) income += eur else spending -= eur
        }
        MonthFlow(monthEnd, income, spending)
    }
}

/** Spending per category over the range, largest first. Null id means uncategorised. */
fun spendingByCategory(items: List<FlowItem>, start: LocalDate, end: LocalDate, fx: FxTable): List<Pair<Long?, BigDecimal>> {
    val totals = HashMap<Long?, BigDecimal>()
    for (item in items) {
        if (item.isIncome || item.date < start || item.date > end) continue
        val eur = fx.toEur(item.amount, item.currency, item.date) ?: continue
        totals.merge(item.categoryId, eur.negate(), BigDecimal::add)
    }
    return totals.entries.filter { it.value.signum() > 0 }
        .sortedByDescending { it.value }
        .map { it.key to it.value.setScale(2, RoundingMode.HALF_UP) }
}
