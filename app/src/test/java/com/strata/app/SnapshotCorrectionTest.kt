package com.strata.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.strata.app.ai.ChangeSet
import com.strata.app.ai.ToolExecutor
import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.db.Role
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SourceType
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.data.repo.LedgerRepository
import com.strata.app.domain.FxTable
import com.strata.app.domain.MoneyFormat
import com.strata.app.ui.chat.ChatItem
import com.strata.app.ui.chat.GroupKind
import com.strata.app.ui.chat.Lookup
import com.strata.app.ui.chat.buildChatItems
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

/** Correcting balances that are already recorded, through the same review card and import undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SnapshotCorrectionTest {
    private lateinit var db: StrataDatabase
    private lateinit var ledger: LedgerRepository
    private lateinit var tools: ToolExecutor

    private val aug = LocalDate.of(2026, 8, 31)
    private val sep = LocalDate.of(2026, 9, 30)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StrataDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepository(db)
        tools = ToolExecutor(db, { FxTable.EMPTY }, today = LocalDate.of(2026, 10, 6))
        db.setupDao().insertSource(SourceEntity(1, "Millennium BCP", SourceType.BANK))
        db.setupDao().insertAssetClass(AssetClassEntity(1, "Cash", "cobalt"))
        val dao = db.ledgerDao()
        dao.insertProduct(ProductEntity(1, 1, 1, "Current account", "EUR"))
        // September's balance was typed with an extra zero: 1000 + 500 should be 1500.
        dao.insertSnapshot(SnapshotEntity(id = 10, productId = 1, date = aug, value = BigDecimal("1000")))
        dao.insertSnapshot(SnapshotEntity(id = 11, productId = 1, date = sep, value = BigDecimal("15000"), note = "statement"))
        dao.insertTransaction(TransactionEntity(productId = 1, date = LocalDate.of(2026, 9, 15), amount = BigDecimal("500"), description = "Salary", kind = TxKind.INCOME))
        Unit
    }

    @After fun tearDown() = db.close()

    private suspend fun value(id: Long) = db.ledgerDao().snapshot(id)?.value?.toPlainString()

    @Test fun listsRecordedIdsAndStagesACorrectionWithoutWriting() = runBlocking {
        val listed = tools.execute("get_snapshots", """{"product_id":1}""")
        assertTrue(listed, listed.contains("\"id\":11") && listed.contains("\"note\":\"statement\""))

        val wrong = tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"value":"1600"}}]}""")
        assertTrue(wrong, wrong.contains("MISMATCH"))
        val fixed = tools.execute("update_staged", """{"items":[{"staged_id":1,"set":{"value":"1500.00"}}]}""")
        assertFalse(fixed, fixed.contains("MISMATCH"))

        val edit = tools.staged.snapshotEdits.single()
        assertEquals("15000", edit.before.value)
        assertEquals("1500.00", edit.after.value)
        assertEquals("statement", edit.after.note)
        assertEquals("15000", value(11))
        assertTrue(tools.execute("get_snapshots", """{"product_id":1}""").contains("\"staged_correction\":1"))
        assertEquals("1 recorded balance corrected", tools.staged.headline())
    }

    @Test fun applyCorrectsAndUndoPutsItBack() = runBlocking {
        tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"value":"1500","note":null}}]}""")
        tools.execute("delete_recorded_snapshots", """{"snapshot_ids":[10]}""")
        val result = ledger.apply(tools.staged, chatId = null)
        assertEquals("1500", value(11))
        assertEquals("", db.ledgerDao().snapshot(11)!!.note)
        assertNull(db.ledgerDao().snapshot(10))

        ledger.undoImport(result.importId)
        assertEquals("15000", value(11))
        assertEquals("statement", db.ledgerDao().snapshot(11)!!.note)
        assertEquals("1000", value(10))
        assertEquals(2, db.backupDao().snapshots().size)
    }

    @Test fun movesABalanceToAnotherDayOnlyWhenTheDayIsFree() = runBlocking {
        val clash = tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"date":"2026-08-31"}}]}""")
        assertTrue(clash, clash.contains("balance 10 is already recorded on 2026-08-31"))
        assertTrue(tools.staged.isEmpty)

        // Once the balance on that day is staged for removal, the day is free.
        tools.execute("delete_recorded_snapshots", """{"snapshot_ids":[10]}""")
        val moved = tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"date":"2026-08-31","value":"1000"}}]}""")
        assertTrue(moved, moved.contains("\"staged\":1"))
        ledger.apply(tools.staged, chatId = null)
        val left = db.backupDao().snapshots().single()
        assertEquals(11L, left.id)
        assertEquals(aug, left.date)
    }

    @Test fun rejectsWhatACorrectionCannotDo() = runBlocking {
        val missing = tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":99,"set":{"value":"1"}}]}""")
        assertTrue(missing, missing.contains("get_snapshots"))
        val product = tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"product_id":2}}]}""")
        assertTrue(product, product.contains("can't move to another product"))
        val same = tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"value":"15000.00"}}]}""")
        assertTrue(same, same.contains("as it is recorded"))
        val comma = tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"value":"1.500,00"}}]}""")
        assertTrue(comma, comma.contains("decimal separator"))
        // All or nothing: one bad item stages none.
        val mixed = tools.execute(
            "edit_recorded_snapshots",
            """{"items":[{"snapshot_id":10,"set":{"value":"900"}},{"snapshot_id":11,"set":{"date":"2027-01-01"}}]}""",
        )
        assertTrue(mixed, mixed.contains("in the future"))
        assertTrue(tools.staged.isEmpty)

        // A second balance on a recorded day points at the correction tool.
        val duplicate = tools.execute("stage_snapshots", """{"items":[{"product_id":1,"date":"2026-09-30","value":"1500"}]}""")
        assertTrue(duplicate, duplicate.contains("edit_recorded_snapshots"))
    }

    @Test fun removalReplacesACorrectionAndBothCanBeUnstaged() = runBlocking {
        tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"value":"1500"}}]}""")
        val removal = tools.execute("delete_recorded_snapshots", """{"snapshot_ids":[11]}""")
        assertTrue(removal, removal.contains("\"replaced_corrections\":[1]"))
        assertTrue(tools.staged.snapshotEdits.isEmpty())
        val id = tools.staged.snapshotDeletions.single().id

        val edit = tools.execute("update_staged", """{"items":[{"staged_id":$id,"set":{"value":"1"}}]}""")
        assertTrue(edit, edit.contains("nothing to change"))
        val listed = tools.execute("get_staged_changes", "")
        assertTrue(listed, listed.contains("recorded_snapshot_removals"))

        tools.execute("remove_staged", """{"staged_ids":[$id]}""")
        assertTrue(tools.staged.isEmpty)
    }

    @Test fun newBalancesNextToCorrectionsSayTheyAreNew() = runBlocking {
        tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"value":"1500"}}]}""")
        tools.execute("stage_snapshots", """{"items":[{"product_id":1,"date":"2026-10-05","value":"1500"}]}""")
        assertEquals("1 recorded balance corrected, 1 new balance", tools.staged.headline())
    }

    @Test fun theCardShowsWhatChanges() = runBlocking {
        tools.execute("edit_recorded_snapshots", """{"items":[{"snapshot_id":11,"set":{"value":"1600"}}]}""")
        tools.execute("delete_recorded_snapshots", """{"snapshot_ids":[10]}""")
        val json = Json.encodeToString(ChangeSet.serializer(), tools.staged)
        val message = MessageEntity(id = 1, chatId = 1, role = Role.ASSISTANT, content = "Fixed it.", proposalJson = json, proposalStatus = ProposalStatus.PENDING)
        val lookup = Lookup(
            db.ledgerDao().products(), db.setupDao().sources(), emptyList(),
            db.backupDao().snapshots(), db.backupDao().transactions(),
        )
        val proposal = (buildChatItems(listOf(message), emptyList(), lookup).single() as ChatItem.Assistant).proposal!!
        val (corrected, removed) = proposal.groups
        assertEquals(GroupKind.CORRECT, corrected.kind)
        assertEquals("Corrections to recorded balances", corrected.title)
        // The recorded value is shown next to the corrected one.
        fun eur(v: String) = MoneyFormat.full(BigDecimal(v), "EUR")
        assertEquals(eur("15000") to eur("1600"), corrected.lines.single().let { it.previous to it.amount })
        assertEquals(GroupKind.REMOVE, removed.kind)
        assertEquals(eur("1000"), removed.lines.single().previous)
        assertTrue(proposal.rewrites!!, proposal.rewrites!!.contains("overwrites 1 balance and deletes 1 balance"))
        assertEquals("1 recorded balance corrected, 1 recorded balance removed", proposal.headline.lowercase())
        // With August removed, September is checked against all its transactions: 500, not 1600.
        assertTrue(proposal.warnings.toString(), proposal.warnings.single().contains("Current account"))
    }
}
