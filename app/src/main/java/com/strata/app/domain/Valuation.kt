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
data class ValuePoint(
    val productId: Long,
    val date: LocalDate,
    val value: BigDecimal,
    val quantity: BigDecimal? = null,
)
data class ValueFlow(val productId: Long, val date: LocalDate, val amount: BigDecimal, val quantity: BigDecimal? = null)

/**
 * Values products at any date. Recorded balances are anchors and are exact on their own dates;
 * transactions fill the gaps between them:
 *
 * - after a balance: that balance plus the flows since;
 * - before the first balance: the next balance minus the flows in between;
 * - before a product's first record of any kind: nothing.
 *
 * Products that hold units (any snapshot or flow carries a quantity) are rolled on units and
 * valued at the latest known price per unit in the product's currency: a snapshot's value divided
 * by its units, or a trade's amount divided by its units.
 */
class Valuator(
    products: List<ValuedProduct>,
    snapshots: List<ValuePoint>,
    private val fx: FxTable,
    flows: List<ValueFlow> = emptyList(),
) {
    private val products = products.associateBy { it.id }
    private val history: Map<Long, List<ValuePoint>> =
        snapshots.filter { it.productId in this.products }.groupBy { it.productId }
            .mapValues { (_, list) -> list.sortedBy { it.date } }

    /** Flows per product, sorted, with running totals for O(log n) range sums. */
    private class Ledger(val dates: List<LocalDate>, val cumAmount: List<BigDecimal>, val cumUnits: List<BigDecimal>) {
        private fun indexThrough(date: LocalDate): Int {
            var lo = 0
            var hi = dates.lastIndex
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (dates[mid] <= date) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            return found
        }
        fun amountThrough(date: LocalDate): BigDecimal = indexThrough(date).let { if (it < 0) BigDecimal.ZERO else cumAmount[it] }
        fun unitsThrough(date: LocalDate): BigDecimal = indexThrough(date).let { if (it < 0) BigDecimal.ZERO else cumUnits[it] }
    }

    private val ledgers: Map<Long, Ledger> = flows.filter { it.productId in this.products }.groupBy { it.productId }
        .mapValues { (_, list) ->
            val sorted = list.sortedBy { it.date }
            var amount = BigDecimal.ZERO
            var units = BigDecimal.ZERO
            val cumA = ArrayList<BigDecimal>(sorted.size)
            val cumU = ArrayList<BigDecimal>(sorted.size)
            for (f in sorted) {
                amount += f.amount
                units += f.quantity ?: BigDecimal.ZERO
                cumA += amount
                cumU += units
            }
            Ledger(sorted.map { it.date }, cumA, cumU)
        }

    private val firstSeen: Map<Long, LocalDate> = buildMap {
        for ((id, list) in history) put(id, list.first().date)
        for ((id, l) in ledgers) put(id, minOf(get(id) ?: l.dates.first(), l.dates.first()))
    }

    private val unitBased: Set<Long> = buildSet {
        history.forEach { (id, list) -> if (list.any { it.quantity != null }) add(id) }
        flows.forEach { if (it.quantity != null && it.quantity.signum() != 0) add(it.productId) }
    }

    /** Known unit prices per product, sorted by date. */
    private val prices: Map<Long, List<Pair<LocalDate, BigDecimal>>> = buildMap {
        val all = HashMap<Long, MutableList<Pair<LocalDate, BigDecimal>>>()
        for (p in snapshots) {
            // Value / units, never the quoted unit price: brokers quote in the trading currency or unit
            // (pence, dollars) while the value is in the product's currency.
            val price = p.quantity?.takeIf { it.signum() != 0 }?.let { p.value.divide(it, MathContext.DECIMAL64) }
            if (price != null) all.getOrPut(p.productId) { mutableListOf() } += p.date to price
        }
        for (f in flows) {
            val q = f.quantity ?: continue
            if (q.signum() == 0 || f.amount.signum() == 0) continue
            all.getOrPut(f.productId) { mutableListOf() } += f.date to f.amount.divide(q, MathContext.DECIMAL64).abs()
        }
        all.forEach { (id, list) -> put(id, list.sortedBy { it.first }) }
    }

    val earliest: LocalDate? = firstSeen.values.minOrNull()

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

    private fun firstAfter(productId: Long, date: LocalDate): ValuePoint? = history[productId]?.firstOrNull { it.date > date }

    /** Value in the product's own currency at [date], or null before the product's first record. */
    fun valueAt(productId: Long, date: LocalDate): BigDecimal? {
        val first = firstSeen[productId] ?: return null
        if (date < first) return null
        val ledger = ledgers[productId]
        return if (productId in unitBased) {
            val units = anchored(productId, date, ledger, units = true) ?: return null
            if (units.signum() == 0) return BigDecimal.ZERO
            val price = priceAt(productId, date) ?: return null
            units.multiply(price, MathContext.DECIMAL64)
        } else {
            anchored(productId, date, ledger, units = false)
        }
    }

    private fun anchored(productId: Long, date: LocalDate, ledger: Ledger?, units: Boolean): BigDecimal? {
        fun through(d: LocalDate) = if (ledger == null) BigDecimal.ZERO else if (units) ledger.unitsThrough(d) else ledger.amountThrough(d)
        fun measure(p: ValuePoint): BigDecimal? = if (units) p.quantity else p.value
        val previous = latestOnOrBefore(productId, date)
        if (previous != null) {
            // A unit-based snapshot without a quantity cannot anchor units; fall through to the next one.
            measure(previous)?.let { return it + through(date) - through(previous.date) }
        }
        val next = firstAfter(productId, date)
        if (next != null) measure(next)?.let { return it - (through(next.date) - through(date)) }
        return if (ledger != null) through(date) else null
    }

    private fun priceAt(productId: Long, date: LocalDate): BigDecimal? {
        val list = prices[productId]?.takeIf { it.isNotEmpty() } ?: return null
        return list.lastOrNull { it.first <= date }?.second ?: list.first().second
    }

    /** EUR value per asset class at [date]; positive numbers, liabilities included as owed. */
    fun byAssetClass(date: LocalDate): Map<Long, BigDecimal> {
        val out = HashMap<Long, BigDecimal>()
        for (product in products.values) {
            val value = valueAt(product.id, date) ?: continue
            val eur = fx.toEur(value, product.currency, date)
            if (eur == null) { missingCurrencies += product.currency; continue }
            out.merge(product.assetClassId, eur, BigDecimal::add)
        }
        return out
    }

    fun productValueEur(productId: Long, date: LocalDate): BigDecimal? {
        val product = products[productId] ?: return null
        val value = valueAt(productId, date) ?: return null
        return fx.toEur(value, product.currency, date)
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

/** Spending per category for each month in EUR, keyed by category id (null means uncategorised). */
fun monthlySpendingByCategory(items: List<FlowItem>, start: LocalDate, end: LocalDate, fx: FxTable): List<Pair<LocalDate, Map<Long?, BigDecimal>>> {
    val months = bucketEnds(start, end, Granularity.MONTH)
    return months.map { monthEnd ->
        val monthStart = maxOf(monthEnd.withDayOfMonth(1), start)
        val totals = HashMap<Long?, BigDecimal>()
        for (item in items) {
            if (item.isIncome || item.date < monthStart || item.date > monthEnd) continue
            val eur = fx.toEur(item.amount, item.currency, item.date) ?: continue
            totals.merge(item.categoryId, eur.negate(), BigDecimal::add)
        }
        monthEnd to totals
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
