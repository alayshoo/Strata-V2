package com.strata.app.data.repo

import androidx.room.withTransaction
import com.strata.app.ai.ChangeSet
import com.strata.app.data.db.ImportEntity
import com.strata.app.data.db.ImportRevert
import com.strata.app.data.db.LedgerDao
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ApplyResult(val importId: Long, val skippedDuplicates: Int)

class LedgerRepository(private val db: StrataDatabase) {
    private val dao: LedgerDao = db.ledgerDao()
    private val json = Json { ignoreUnknownKeys = true }

    val products = dao.observeProducts()
    val snapshots = dao.observeSnapshots()
    val transactions = dao.observeTransactions()
    val imports = dao.observeImports()

    fun product(id: Long) = dao.observeProduct(id)
    fun snapshotsFor(productId: Long) = dao.observeSnapshotsFor(productId)
    fun transactionsFor(productId: Long) = dao.observeTransactionsFor(productId)

    suspend fun saveProduct(product: ProductEntity) =
        if (product.id == 0L) dao.insertProduct(product).let { } else dao.updateProduct(product)

    suspend fun deleteProduct(product: ProductEntity) = dao.deleteProduct(product)

    /** Manual entry: one balance per product per day, so an existing one is replaced. */
    suspend fun saveSnapshot(snapshot: SnapshotEntity) = db.withTransaction {
        val existing = dao.snapshotOn(snapshot.productId, snapshot.date)
        if (existing != null && existing.id != snapshot.id) dao.deleteSnapshot(existing)
        dao.upsertSnapshot(snapshot)
    }

    suspend fun deleteSnapshot(snapshot: SnapshotEntity) = dao.deleteSnapshot(snapshot)

    suspend fun saveTransaction(transaction: TransactionEntity) =
        if (transaction.id == 0L) dao.insertTransaction(transaction).let { } else dao.updateTransaction(transaction)

    suspend fun deleteTransaction(transaction: TransactionEntity) = dao.deleteTransaction(transaction)

    /**
     * Writes a reviewed change set atomically. Balances that already exist for that day are skipped.
     * Corrections to recorded balances keep what they replace on the import, so undo can put it back.
     */
    suspend fun apply(changes: ChangeSet, chatId: Long?): ApplyResult = db.withTransaction {
        val entry = ImportEntity(chatId = chatId, fileNames = changes.fileNames.joinToString(", "), summary = changes.headline())
        val importId = dao.insertImport(entry)
        val refs = HashMap<String, Long>()
        for (p in changes.products) {
            refs[p.ref] = dao.insertProduct(
                ProductEntity(
                    sourceId = p.sourceId,
                    assetClassId = p.assetClassId,
                    name = p.name,
                    currency = p.currency.uppercase(),
                    identifier = p.identifier,
                    importId = importId,
                )
            )
        }
        fun resolve(id: Long?, ref: String?): Long =
            id ?: refs[ref] ?: error("Unknown product reference $ref")

        // Removals first, so a correction or a new balance can take a day a removed one held.
        val deleted = changes.snapshotDeletions.mapNotNull { d -> dao.snapshot(d.snapshotId)?.also { dao.deleteSnapshot(it) } }
        val edited = changes.snapshotEdits.mapNotNull { e ->
            val row = dao.snapshot(e.snapshotId) ?: return@mapNotNull null
            val date = LocalDate.parse(e.after.date)
            val clash = dao.snapshotOn(row.productId, date)
            if (clash != null && clash.id != row.id) error("A balance on $date was recorded after this card was made. Ask for the correction again.")
            dao.updateSnapshot(
                row.copy(
                    date = date,
                    value = BigDecimal(e.after.value),
                    quantity = e.after.quantity?.let(::BigDecimal),
                    unitPrice = e.after.unitPrice?.let(::BigDecimal),
                    note = e.after.note,
                )
            )
            row
        }
        if (deleted.isNotEmpty() || edited.isNotEmpty()) {
            val revert = ImportRevert(editedSnapshots = edited, deletedSnapshots = deleted)
            dao.updateImport(entry.copy(id = importId, revertJson = json.encodeToString(ImportRevert.serializer(), revert)))
        }

        var skipped = 0
        for (s in changes.snapshots) {
            val productId = resolve(s.product.id, s.product.ref)
            val date = LocalDate.parse(s.date)
            if (dao.snapshotOn(productId, date) != null) { skipped++; continue }
            dao.insertSnapshot(
                SnapshotEntity(
                    productId = productId,
                    date = date,
                    value = BigDecimal(s.value),
                    quantity = s.quantity?.let(::BigDecimal),
                    unitPrice = s.unitPrice?.let(::BigDecimal),
                    note = s.note,
                    importId = importId,
                )
            )
        }

        val groups = HashMap<String, String>()
        for (t in changes.transactions) {
            val kind = TxKind.valueOf(t.kind)
            var group = t.transferKey?.let { key -> groups.getOrPut(key) { UUID.randomUUID().toString() } }
            if (t.linkToTransactionId != null) {
                val other = dao.transaction(t.linkToTransactionId)
                if (other != null) {
                    group = other.transferGroup ?: group ?: UUID.randomUUID().toString()
                    if (other.transferGroup == null) dao.setTransferGroup(listOf(other.id), group)
                    t.transferKey?.let { groups[it] = group }
                }
            }
            dao.insertTransaction(
                TransactionEntity(
                    productId = resolve(t.product.id, t.product.ref),
                    date = LocalDate.parse(t.date),
                    amount = BigDecimal(t.amount),
                    description = t.description,
                    counterparty = t.counterparty,
                    kind = kind,
                    spendingCategoryId = t.spendingCategoryId.takeIf { kind == TxKind.EXPENSE || kind == TxKind.INCOME },
                    transferGroup = group,
                    quantity = t.quantity?.let(::BigDecimal),
                    fxRate = t.fxRate?.let(::BigDecimal),
                    importId = importId,
                )
            )
        }
        for (link in changes.links) {
            dao.setTransferGroup(link.transactionIds, UUID.randomUUID().toString())
        }
        ApplyResult(importId, skipped)
    }

    /**
     * Removes everything an import wrote and puts back the recorded balances it corrected or removed.
     * Products it created go too, unless other data now uses them.
     */
    suspend fun undoImport(importId: Long) = db.withTransaction {
        val revert = dao.importById(importId)?.revertJson?.let { runCatching { json.decodeFromString(ImportRevert.serializer(), it) }.getOrNull() }
        dao.deleteTransactionsOfImport(importId)
        dao.deleteSnapshotsOfImport(importId)
        dao.deleteOrphanProductsOfImport(importId)
        if (revert != null) {
            // A balance put back only takes its day if nothing else holds it now.
            fun dayFree(old: SnapshotEntity) = dao.snapshotOn(old.productId, old.date).let { it == null || it.id == old.id }
            for (old in revert.editedSnapshots) {
                if (dao.snapshot(old.id) != null && dayFree(old)) dao.updateSnapshot(old)
            }
            for (old in revert.deletedSnapshots) {
                if (dao.product(old.productId) != null && dao.snapshot(old.id) == null && dayFree(old)) dao.insertSnapshot(old)
            }
        }
        dao.importById(importId)?.let { dao.updateImport(it.copy(undoneAt = System.currentTimeMillis())) }
        db.chatDao().markImportUndone(importId)
    }

    suspend fun importCounts(importId: Long): Pair<Int, Int> =
        dao.snapshotCountOfImport(importId) to dao.transactionCountOfImport(importId)
}
