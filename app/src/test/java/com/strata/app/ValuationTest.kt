package com.strata.app

import com.strata.app.domain.FlowItem
import com.strata.app.domain.FxTable
import com.strata.app.domain.Granularity
import com.strata.app.domain.TimeRange
import com.strata.app.domain.ValueFlow
import com.strata.app.domain.ValuePoint
import com.strata.app.domain.ValuedProduct
import com.strata.app.domain.Valuator
import com.strata.app.domain.bucketEnds
import com.strata.app.domain.monthlyFlows
import com.strata.app.domain.netWorth
import com.strata.app.domain.spendingByCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ValuationTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun bd(s: String) = BigDecimal(s)

    private val fx = FxTable(mapOf("USD" to listOf(d("2026-01-02") to bd("1.10"), d("2026-02-02") to bd("1.25"))))

    @Test fun carriesLatestSnapshotForward() {
        val v = Valuator(
            listOf(ValuedProduct(1, 10, "EUR")),
            listOf(ValuePoint(1, d("2026-01-31"), bd("100")), ValuePoint(1, d("2026-03-31"), bd("300"))),
            fx,
        )
        assertNull(v.byAssetClass(d("2026-01-30"))[10])
        assertEquals(0, bd("100").compareTo(v.byAssetClass(d("2026-03-30"))[10]))
        assertEquals(0, bd("300").compareTo(v.byAssetClass(d("2026-06-01"))[10]))
    }

    @Test fun rebuildsHistoryAroundBalancesWithTransactions() {
        // A full-history export: flows from the first deposit, one closing balance at the end.
        val v = Valuator(
            listOf(ValuedProduct(1, 10, "EUR")),
            listOf(ValuePoint(1, d("2026-10-01"), bd("1300"))),
            fx,
            listOf(
                ValueFlow(1, d("2025-05-13"), bd("1000")),
                ValueFlow(1, d("2025-09-04"), bd("500")),
                ValueFlow(1, d("2026-05-02"), bd("-200")),
                ValueFlow(1, d("2026-10-05"), bd("50")),
            ),
        )
        assertNull(v.valueAt(1, d("2025-05-12"))) // before the product's first record
        assertEquals(0, bd("1000").compareTo(v.valueAt(1, d("2025-06-01"))))
        assertEquals(0, bd("1500").compareTo(v.valueAt(1, d("2026-01-01"))))
        assertEquals(0, bd("1300").compareTo(v.valueAt(1, d("2026-10-01")))) // the balance itself
        assertEquals(0, bd("1350").compareTo(v.valueAt(1, d("2026-10-06")))) // rolled forward after it
    }

    @Test fun valuesUnitHoldingsAtLatestKnownPrice() {
        val v = Valuator(
            listOf(ValuedProduct(2, 20, "EUR")),
            listOf(ValuePoint(2, d("2026-10-01"), bd("973.03"), bd("881.23"), bd("1.1042"))),
            fx,
            listOf(ValueFlow(2, d("2025-11-10"), bd("998.88"), bd("879")), ValueFlow(2, d("2025-11-10"), bd("2.53"), bd("2.23"))),
        )
        // 881.23 units bought at about 1.1364 each; before the snapshot the trade price is used.
        val earlier = v.valueAt(2, d("2026-01-01"))!!
        assertTrue(earlier > bd("995") && earlier < bd("1005"))
        assertEquals(0, bd("973.054166").compareTo(v.valueAt(2, d("2026-10-02"))!!.setScale(6, java.math.RoundingMode.HALF_UP)))
    }

    @Test fun convertsWithRateOnOrBeforeDate() {
        assertEquals(0, bd("100").compareTo(fx.toEur(bd("110"), "USD", d("2026-01-15"))))
        // Weekend after a rate change uses the newest earlier rate.
        assertEquals(0, bd("100").compareTo(fx.toEur(bd("125"), "USD", d("2026-02-07"))))
        // Before the first rate we use the earliest one rather than dropping the product.
        assertEquals(0, bd("100").compareTo(fx.toEur(bd("110"), "USD", d("2025-12-01"))))
        assertNull(fx.toEur(bd("1"), "JPY", d("2026-01-15")))
    }

    @Test fun liabilitiesReduceNetWorth() {
        val byClass = mapOf(1L to bd("1000"), 2L to bd("250"))
        assertEquals(0, bd("750").compareTo(netWorth(byClass, setOf(2L))))
    }

    @Test fun reportsMissingCurrencies() {
        val v = Valuator(listOf(ValuedProduct(1, 1, "CHF")), listOf(ValuePoint(1, d("2026-01-01"), bd("5"))), fx)
        v.byAssetClass(d("2026-02-01"))
        assertEquals(setOf("CHF"), v.missingCurrencies)
    }

    @Test fun monthlyBucketsEndToday() {
        val ends = bucketEnds(d("2026-07-15"), d("2026-10-06"), Granularity.MONTH)
        assertEquals(listOf(d("2026-07-31"), d("2026-08-31"), d("2026-09-30"), d("2026-10-06")), ends)
    }

    @Test fun ytdStartsOnFirstOfJanuary() {
        assertEquals(d("2026-01-01"), TimeRange.YTD.start(d("2026-10-06"), null))
    }

    @Test fun refundsReduceSpending() {
        val items = listOf(
            FlowItem(d("2026-09-02"), bd("-100"), "EUR", false, 1),
            FlowItem(d("2026-09-10"), bd("30"), "EUR", false, 1),
            FlowItem(d("2026-09-24"), bd("2000"), "EUR", true, 9),
        )
        val months = monthlyFlows(items, d("2026-09-01"), d("2026-09-30"), FxTable.EMPTY)
        assertEquals(0, bd("70").compareTo(months.single().spending))
        assertEquals(0, bd("2000").compareTo(months.single().income))
        assertEquals(0, bd("70.00").compareTo(spendingByCategory(items, d("2026-09-01"), d("2026-09-30"), FxTable.EMPTY).single().second))
    }
}
