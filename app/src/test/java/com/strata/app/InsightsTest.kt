package com.strata.app

import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SourceType
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.domain.DashboardInput
import com.strata.app.domain.FlowItem
import com.strata.app.domain.FxTable
import com.strata.app.domain.Granularity
import com.strata.app.domain.TimeRange
import com.strata.app.domain.ValueFlow
import com.strata.app.domain.ValuePoint
import com.strata.app.domain.ValuedProduct
import com.strata.app.domain.Valuator
import com.strata.app.domain.bucketEnds
import com.strata.app.domain.buildDashboard
import com.strata.app.domain.growthBuckets
import com.strata.app.domain.incomeFromHoldings
import com.strata.app.domain.institutionSlices
import com.strata.app.domain.lastMonthExpenses
import com.strata.app.domain.savingsRateTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class InsightsTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun bd(s: String) = BigDecimal(s)
    private fun tx(id: Long, product: Long, date: String, amount: String, kind: TxKind, category: Long? = null, who: String = "", desc: String = "x") =
        TransactionEntity(id, product, d(date), bd(amount), desc, who, kind, category)

    @Test fun growthAddsUpToTheNetWorthChange() {
        for (range in TimeRange.entries) {
            val state = buildDashboard(
                DashboardInput(SampleData.assetClasses, SampleData.products, SampleData.snapshots, SampleData.transactions, SampleData.categories, SampleData.fx, SampleData.sources),
                range, SampleData.today,
            )
            val total = state.growth.sumOf { it.saved + it.returns + it.newAccounts }
            assertEquals("$range", state.change, total, 0.01)
            // Flows on the start date belong to the opening balance, so compare where none fall on it.
            if (range == TimeRange.Y1) assertEquals(state.income - state.spending, state.growth.sumOf { it.saved }, 0.01)
        }
    }

    @Test fun accountsFirstRecordedMidRangeAreNotReturns() {
        // Cash grows only by what was saved; the broker account arrives in March with 5,000 already in it.
        val valuator = Valuator(
            listOf(ValuedProduct(1, 1, "EUR"), ValuedProduct(2, 1, "EUR")),
            listOf(ValuePoint(1, d("2026-01-01"), bd("1000")), ValuePoint(2, d("2026-03-10"), bd("5000")), ValuePoint(2, d("2026-03-31"), bd("5100"))),
            FxTable.EMPTY,
            listOf(ValueFlow(1, d("2026-02-24"), bd("2000")), ValueFlow(1, d("2026-03-02"), bd("-500"))),
        )
        val flows = listOf(
            FlowItem(d("2026-02-24"), bd("2000"), "EUR", true, null),
            FlowItem(d("2026-03-02"), bd("-500"), "EUR", false, null),
        )
        val ends = bucketEnds(d("2026-01-01"), d("2026-03-31"), Granularity.MONTH)
        val growth = growthBuckets(valuator, mapOf(1L to false, 2L to false), emptySet(), flows, FxTable.EMPTY, d("2026-01-01"), ends)
        assertEquals(listOf(0.0, 2000.0, -500.0), growth.map { it.saved })
        assertEquals(listOf(0.0, 0.0, 5000.0), growth.map { it.newAccounts })
        assertEquals(listOf(0.0, 0.0, 100.0), growth.map { it.returns })
    }

    @Test fun savingsRateIsATrailingThreeMonthAverageOfCompleteMonths() {
        val flows = listOf(
            FlowItem(d("2026-06-24"), bd("1000"), "EUR", true, null),
            FlowItem(d("2026-06-02"), bd("-500"), "EUR", false, null),
            FlowItem(d("2026-07-24"), bd("1000"), "EUR", true, null),
            FlowItem(d("2026-07-02"), bd("-1000"), "EUR", false, null),
            FlowItem(d("2026-08-24"), bd("2000"), "EUR", true, null),
            FlowItem(d("2026-08-02"), bd("-500"), "EUR", false, null),
            // The month in progress is left out.
            FlowItem(d("2026-09-02"), bd("-900"), "EUR", false, null),
        )
        val points = savingsRateTrend(flows, d("2026-07-01"), d("2026-09-10"), FxTable.EMPTY)
        assertEquals(listOf(d("2026-07-31"), d("2026-08-31")), points.map { it.date })
        assertEquals(0.5 / 2, points[0].value, 1e-9)
        assertEquals(2.0 / 4, points[1].value, 1e-9)
    }

    @Test fun lastMonthListsTheLargestExpensesAndNetsRefunds() {
        val categories = mapOf(1L to SpendingCategoryEntity(1, "Travel", FlowKind.EXPENSE, "lime"))
        val txs = listOf(
            tx(1, 1, "2026-09-03", "-40", TxKind.EXPENSE, who = "Lidl"),
            tx(2, 1, "2026-09-19", "-600", TxKind.EXPENSE, 1, who = "TAP", desc = "Flights"),
            tx(3, 1, "2026-09-21", "100", TxKind.EXPENSE, 1, who = "TAP"),
            tx(4, 1, "2026-09-25", "-800", TxKind.TRANSFER),
            tx(5, 1, "2026-10-02", "-900", TxKind.EXPENSE),
            tx(6, 2, "2026-09-10", "-100", TxKind.EXPENSE, desc = "Hotel"),
        )
        val last = lastMonthExpenses(txs, mapOf(1L to "EUR", 2L to "USD"), categories, SampleData.fx, d("2026-10-06"), limit = 2)
        assertEquals(d("2026-09-01"), last.month)
        assertEquals(3, last.count)
        assertEquals(listOf(2L, 6L), last.largest.map { it.id })
        assertEquals("Travel", last.largest[0].category)
        assertEquals("Flights", last.largest[0].description)
        assertEquals("Hotel", last.largest[1].title)
        val hotel = SampleData.fx.toEur(bd("100"), "USD", d("2026-09-10"))!!.toDouble()
        assertEquals(540.0 + hotel, last.total, 0.01)
    }

    @Test fun flagsBankCashAboveTheDepositGuarantee() {
        val valuator = Valuator(
            listOf(ValuedProduct(1, 1, "EUR"), ValuedProduct(2, 2, "EUR"), ValuedProduct(3, 1, "EUR"), ValuedProduct(4, 3, "EUR")),
            listOf(
                ValuePoint(1, d("2026-01-01"), bd("90000")),
                ValuePoint(2, d("2026-01-01"), bd("50000"), bd("100")),
                ValuePoint(3, d("2026-01-01"), bd("120000")),
                ValuePoint(4, d("2026-01-01"), bd("2000")),
            ),
            FxTable.EMPTY,
        )
        val sources = listOf(SourceEntity(1, "Bank A", SourceType.BANK), SourceEntity(2, "Broker B", SourceType.BROKER))
        // Bank A holds 90k cash plus 50k in a fund: still within the guarantee. Broker B is not a bank.
        val slices = institutionSlices(
            valuator, sources,
            listOf(Triple(1L, 1L, false), Triple(2L, 1L, false), Triple(3L, 2L, false), Triple(4L, 1L, true)),
            d("2026-02-01"),
        )
        assertEquals(listOf("Bank A", "Broker B"), slices.map { it.name })
        assertEquals(140000.0, slices[0].value, 0.01)
        assertEquals(140.0 / 260, slices[0].share, 1e-9)
        assertFalse(slices.any { it.aboveGuarantee })

        val richer = institutionSlices(
            valuator, listOf(SourceEntity(2, "Bank B", SourceType.BANK)), listOf(Triple(3L, 2L, false)), d("2026-02-01"),
        )
        assertTrue(richer.single().aboveGuarantee)
    }

    @Test fun holdingsIncomeSplitsDividendsInterestAndFees() {
        val txs = listOf(
            tx(1, 1, "2026-09-12", "10", TxKind.DIVIDEND),
            tx(2, 2, "2026-09-28", "25", TxKind.INTEREST),
            tx(3, 2, "2026-09-26", "-1", TxKind.FEE),
            tx(4, 2, "2026-09-24", "3000", TxKind.INCOME),
            tx(5, 2, "2026-10-02", "-1", TxKind.FEE),
        )
        val months = incomeFromHoldings(txs, mapOf(1L to "USD", 2L to "EUR"), SampleData.fx, d("2026-09-01"), d("2026-10-06"))
        assertEquals(2, months.size)
        assertEquals(SampleData.fx.toEur(bd("10"), "USD", d("2026-09-12"))!!.toDouble(), months[0].dividends, 1e-9)
        assertEquals(25.0, months[0].interest, 1e-9)
        assertEquals(-1.0, months[0].fees, 1e-9)
        assertEquals(-1.0, months[1].fees, 1e-9)
    }
}
