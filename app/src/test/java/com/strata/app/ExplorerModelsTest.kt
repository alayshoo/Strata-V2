package com.strata.app

import com.strata.app.data.db.TxKind
import com.strata.app.domain.TimeRange
import com.strata.app.ui.explorer.ExplorerData
import com.strata.app.ui.explorer.TxFilter
import com.strata.app.ui.explorer.UNCATEGORISED
import com.strata.app.ui.explorer.sourceHistory
import com.strata.app.ui.explorer.transactionRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerModelsTest {
    private val data = ExplorerData(
        sources = SampleData.sources,
        assetClasses = SampleData.assetClasses,
        categories = SampleData.categories,
        products = SampleData.products,
        snapshots = SampleData.snapshots,
        transactions = SampleData.transactions,
        fx = SampleData.fx,
    )

    @Test fun narrowsSpendingToOneCategory() {
        val category = SampleData.transactions.first { it.kind == TxKind.EXPENSE && it.spendingCategoryId != null }.spendingCategoryId!!
        val rows = transactionRows(data, TxFilter.SPENDING, "", category)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.transaction.kind == TxKind.EXPENSE && it.transaction.spendingCategoryId == category })
        assertEquals(
            SampleData.transactions.count { it.kind == TxKind.EXPENSE && it.spendingCategoryId == category },
            rows.size,
        )
        val uncategorised = transactionRows(data, TxFilter.SPENDING, "", UNCATEGORISED)
        assertTrue(uncategorised.all { it.transaction.spendingCategoryId == null })
        // A category only narrows filters that have categories.
        assertEquals(transactionRows(data, TxFilter.MOVES, "").size, transactionRows(data, TxFilter.MOVES, "", category).size)
    }

    @Test fun institutionHistoryAddsUpItsProducts() {
        val history = sourceHistory(data, sourceId = 2, range = TimeRange.Y1, today = SampleData.today)
        assertTrue(history.hasData)
        assertEquals(SampleData.today, history.line.last().date)
        assertEquals(history.total, history.line.last().value, 0.01)
        // The latest bar holds each product of the institution and sums to the total.
        val latest = history.bars.last()
        assertEquals(SampleData.products.filter { it.sourceId == 2L }.map { it.id }.toSet(), latest.values.map { it.first }.toSet())
        assertEquals(history.total, latest.values.sumOf { it.second }, 0.01)
        assertEquals(history.total - history.line.first().value, history.change, 0.01)
    }

    @Test fun institutionWithoutRecordsHasNoHistory() {
        val empty = sourceHistory(data.copy(snapshots = emptyList(), transactions = emptyList()), 2, TimeRange.ALL, SampleData.today)
        assertTrue(!empty.hasData)
    }
}
