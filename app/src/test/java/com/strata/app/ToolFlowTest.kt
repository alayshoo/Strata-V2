package com.strata.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.strata.app.ai.ToolExecutor
import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SourceType
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.data.repo.LedgerRepository
import com.strata.app.domain.FxTable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

/** The assistant's path through the ledger: read, stage, apply, undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ToolFlowTest {
    private lateinit var db: StrataDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var tools: ToolExecutor

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StrataDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepository(db)
        tools = ToolExecutor(db, { FxTable.EMPTY }, today = LocalDate.of(2026, 10, 6))
        val setup = db.setupDao()
        setup.insertSource(SourceEntity(1, "Millennium BCP", SourceType.BANK))
        setup.insertSource(SourceEntity(2, "Trade Republic", SourceType.BROKER))
        setup.insertAssetClass(AssetClassEntity(1, "Cash", "cobalt"))
        setup.insertSpendingCategory(SpendingCategoryEntity(1, "Groceries", FlowKind.EXPENSE, "saffron"))
        setup.insertSpendingCategory(SpendingCategoryEntity(2, "Salary", FlowKind.INCOME, "jade"))
        db.ledgerDao().insertProduct(ProductEntity(1, 1, 1, "Current account", "EUR"))
        db.ledgerDao().insertProduct(ProductEntity(2, 2, 1, "Cash balance", "EUR"))
        Unit
    }

    @After fun tearDown() = db.close()

    @Test fun stagesNothingUntilApplied() = runBlocking {
        tools.execute("stage_snapshots", """{"items":[{"product_id":1,"date":"2026-09-30","value":"4381.27"}]}""")
        assertEquals(0, db.backupDao().snapshots().size)
        assertEquals(1, tools.staged.snapshots.size)
    }

    @Test fun applyThenUndoRemovesEverything() = runBlocking {
        tools.execute("stage_product", """{"ref":"visa","source_id":1,"asset_class_id":1,"name":"Visa Gold","currency":"eur"}""")
        tools.execute("stage_snapshots", """{"items":[{"new_product_ref":"visa","date":"2026-09-30","value":"712.40"},{"product_id":1,"date":"2026-09-30","value":"4381.27"}]}""")
        tools.execute(
            "stage_transactions",
            """{"items":[
                {"product_id":1,"date":"2026-09-21","amount":"-63.18","description":"Groceries","counterparty":"Pingo Doce","kind":"expense","spending_category_id":1},
                {"product_id":1,"date":"2026-09-25","amount":"-800","description":"To broker","kind":"transfer","transfer_key":"m"},
                {"product_id":2,"date":"2026-09-25","amount":"800","description":"Deposit","kind":"transfer","transfer_key":"m"}
            ]}""",
        )
        val result = ledger.apply(tools.staged, chatId = null)
        assertEquals(3, db.ledgerDao().products().size)
        assertEquals(2, db.backupDao().snapshots().size)
        val txs = db.backupDao().transactions()
        assertEquals(3, txs.size)
        val legs = txs.filter { it.kind == TxKind.TRANSFER }
        assertNotNull(legs[0].transferGroup)
        assertEquals(legs[0].transferGroup, legs[1].transferGroup)

        ledger.undoImport(result.importId)
        assertEquals(2, db.ledgerDao().products().size)
        assertEquals(0, db.backupDao().snapshots().size)
        assertEquals(0, db.backupDao().transactions().size)
    }

    @Test fun rejectsInventedIdsAndWrongCategoryKinds() = runBlocking {
        val missing = tools.execute("stage_product", """{"ref":"x","source_id":99,"asset_class_id":1,"name":"X","currency":"EUR"}""")
        assertTrue(missing.contains("created by the user in Setup"))
        val wrongKind = tools.execute(
            "stage_transactions",
            """{"items":[{"product_id":1,"date":"2026-09-21","amount":"-5","description":"x","kind":"expense","spending_category_id":2}]}""",
        )
        assertTrue(wrongKind.contains("income category"))
        val comma = tools.execute("stage_snapshots", """{"items":[{"product_id":1,"date":"2026-09-30","value":"1.234,56"}]}""")
        assertTrue(comma.contains("decimal separator"))
        assertTrue(tools.staged.isEmpty)
    }

    @Test fun skipsDuplicatesAndLinksExistingLegs() = runBlocking {
        val existing = db.ledgerDao().insertTransaction(
            TransactionEntity(productId = 2, date = LocalDate.of(2026, 9, 25), amount = BigDecimal("800"), description = "Deposit", kind = TxKind.TRANSFER)
        )
        db.ledgerDao().insertTransaction(
            TransactionEntity(productId = 1, date = LocalDate.of(2026, 9, 21), amount = BigDecimal("-63.18"), description = "Groceries", kind = TxKind.EXPENSE)
        )
        val candidates = tools.execute("find_transfer_candidates", """{"date":"2026-09-25"}""")
        assertTrue(candidates.contains("\"id\":$existing"))
        val out = tools.execute(
            "stage_transactions",
            """{"items":[
                {"product_id":1,"date":"2026-09-21","amount":"-63.18","description":"Groceries","kind":"expense"},
                {"product_id":1,"date":"2026-09-25","amount":"-800","description":"To broker","kind":"transfer","link_to_transaction_id":$existing}
            ]}""",
        )
        assertTrue(out.contains("looks like existing transaction"))
        ledger.apply(tools.staged, chatId = null)
        val legs = db.backupDao().transactions().filter { it.kind == TxKind.TRANSFER }
        assertEquals(2, legs.size)
        assertEquals(legs[0].transferGroup, legs[1].transferGroup)
        assertNotNull(legs[0].transferGroup)
    }
}
