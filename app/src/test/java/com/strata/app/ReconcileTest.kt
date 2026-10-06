package com.strata.app

import com.strata.app.ai.Calculator
import com.strata.app.ai.Reconcile
import com.strata.app.ai.Reconcile.Flow
import com.strata.app.ai.Reconcile.Key
import com.strata.app.ai.Reconcile.Point
import com.strata.app.data.db.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReconcileTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun bd(s: String) = BigDecimal(s)
    private val cash = Key(1, null)
    private val bond = Key(null, "bond")

    @Test fun fullHistoryCashMustMatchSumOfFlows() {
        val flows = mapOf(cash to listOf(Flow(d("2025-05-13"), bd("950"), null, TxKind.TRANSFER), Flow(d("2025-06-01"), bd("-120.50"), null, TxKind.EXPENSE)))
        val wrong = Reconcile.check(mapOf(cash to listOf(Point(d("2026-10-01"), bd("0.00"), null))), flows)
        assertEquals(1, wrong.size)
        assertEquals(0, bd("829.50").compareTo(wrong.single().implied))
        assertEquals(null, wrong.single().since)
        val right = Reconcile.check(mapOf(cash to listOf(Point(d("2026-10-01"), bd("829.50"), null))), flows)
        assertTrue(right.isEmpty())
    }

    @Test fun laterBalanceUsesPreviousBalancePlusFlowsSince() {
        val points = listOf(Point(d("2026-08-31"), bd("1000"), null), Point(d("2026-09-30"), bd("1150"), null))
        val flows = listOf(
            Flow(d("2026-08-15"), bd("999"), null, TxKind.INCOME), // before the first balance, ignored for the second
            Flow(d("2026-09-10"), bd("200"), null, TxKind.INCOME),
            Flow(d("2026-09-20"), bd("-50"), null, TxKind.EXPENSE),
        )
        val findings = Reconcile.check(mapOf(cash to points), mapOf(cash to flows))
        // The first balance disagrees with the one flow before it; the second is consistent.
        assertEquals(listOf(d("2026-08-31")), findings.map { it.date })
    }

    @Test fun securitiesAreCheckedOnUnitsNotValue() {
        val buys = listOf(
            Flow(d("2025-07-31"), bd("99.92"), bd("98"), TxKind.TRADE),
            Flow(d("2025-07-31"), bd("899.30"), bd("882"), TxKind.TRADE),
        )
        val sell = Flow(d("2026-07-17"), bd("-963.16"), bd("-980"), TxKind.TRADE)
        val closed = mapOf(bond to listOf(Point(d("2026-10-01"), bd("0"), bd("0"))))

        // Position closed and the sale recorded: units agree, value is not compared.
        assertTrue(Reconcile.check(closed, mapOf(bond to buys + sell)).isEmpty())

        // Sale missing: 980 units are still implied.
        val f = Reconcile.check(closed, mapOf(bond to buys)).single()
        assertEquals(Reconcile.Measure.UNITS, f.measure)
        assertEquals(0, bd("980").compareTo(f.implied))
    }

    @Test fun calculatorIsExactAndStrict() {
        assertEquals("0.3", Calculator.evaluate("0.1 + 0.2").toPlainString())
        assertEquals("973.054166", Calculator.evaluate("881.23 * 1.1042").toPlainString())
        assertEquals("14", Calculator.evaluate("2 + 3 * 4").toPlainString())
        assertEquals("20", Calculator.evaluate("(2 + 3) * 4").toPlainString())
        assertEquals("-5", Calculator.evaluate("-(2 + 3)").toPlainString())
        assertEquals("164.2684", Calculator.evaluate("(7240.39 + 973.03) * 2%").toPlainString())
        assertEquals("0.3333333333333333333333333333333333", Calculator.evaluate("1 / 3").toPlainString())
        listOf("1,5 + 2", "2 +", "(1 + 2", "1 / 0", "java.lang.System", "2 ** 3").forEach { bad ->
            val failed = runCatching { Calculator.evaluate(bad) }.exceptionOrNull()
            assertTrue("expected '$bad' to fail", failed is Calculator.CalcError)
        }
    }
}
