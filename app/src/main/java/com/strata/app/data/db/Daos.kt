package com.strata.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface SetupDao {
    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    suspend fun sources(): List<SourceEntity>

    @Insert suspend fun insertSource(source: SourceEntity): Long
    @Update suspend fun updateSource(source: SourceEntity)
    @Delete suspend fun deleteSource(source: SourceEntity)

    @Query("SELECT * FROM asset_classes ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAssetClasses(): Flow<List<AssetClassEntity>>

    @Query("SELECT * FROM asset_classes ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun assetClasses(): List<AssetClassEntity>

    @Insert suspend fun insertAssetClass(assetClass: AssetClassEntity): Long
    @Update suspend fun updateAssetClass(assetClass: AssetClassEntity)
    @Delete suspend fun deleteAssetClass(assetClass: AssetClassEntity)

    @Query("SELECT * FROM spending_categories ORDER BY kind, name COLLATE NOCASE")
    fun observeSpendingCategories(): Flow<List<SpendingCategoryEntity>>

    @Query("SELECT * FROM spending_categories ORDER BY kind, name COLLATE NOCASE")
    suspend fun spendingCategories(): List<SpendingCategoryEntity>

    @Insert suspend fun insertSpendingCategory(category: SpendingCategoryEntity): Long
    @Update suspend fun updateSpendingCategory(category: SpendingCategoryEntity)
    @Delete suspend fun deleteSpendingCategory(category: SpendingCategoryEntity)

    @Query("SELECT COUNT(*) FROM products WHERE sourceId = :sourceId")
    suspend fun productCountForSource(sourceId: Long): Int

    @Query("SELECT COUNT(*) FROM products WHERE assetClassId = :assetClassId")
    suspend fun productCountForAssetClass(assetClassId: Long): Int
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    suspend fun products(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun product(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE id = :id")
    fun observeProduct(id: Long): Flow<ProductEntity?>

    @Insert suspend fun insertProduct(product: ProductEntity): Long
    @Update suspend fun updateProduct(product: ProductEntity)
    @Delete suspend fun deleteProduct(product: ProductEntity)

    @Query("SELECT * FROM snapshots ORDER BY date")
    fun observeSnapshots(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots WHERE productId = :productId ORDER BY date DESC")
    fun observeSnapshotsFor(productId: Long): Flow<List<SnapshotEntity>>

    @Query(
        "SELECT * FROM snapshots WHERE productId = :productId AND date BETWEEN :from AND :to ORDER BY date"
    )
    suspend fun snapshotsFor(productId: Long, from: LocalDate, to: LocalDate): List<SnapshotEntity>

    @Query("SELECT * FROM snapshots WHERE productId = :productId AND date = :date")
    suspend fun snapshotOn(productId: Long, date: LocalDate): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Upsert suspend fun upsertSnapshot(snapshot: SnapshotEntity)
    @Delete suspend fun deleteSnapshot(snapshot: SnapshotEntity)

    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE productId = :productId ORDER BY date DESC, id DESC")
    fun observeTransactionsFor(productId: Long): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE (:productId IS NULL OR productId = :productId)
          AND date BETWEEN :from AND :to
          AND (:kind IS NULL OR kind = :kind)
        ORDER BY date DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun transactions(
        productId: Long?,
        from: LocalDate,
        to: LocalDate,
        kind: TxKind?,
        limit: Int,
    ): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE transferGroup IS NULL AND kind IN ('TRANSFER', 'TRADE')
          AND date BETWEEN :from AND :to
        ORDER BY date
        """
    )
    suspend fun unlinkedTransfers(from: LocalDate, to: LocalDate): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: Long): TransactionEntity?

    @Insert suspend fun insertTransaction(transaction: TransactionEntity): Long
    @Update suspend fun updateTransaction(transaction: TransactionEntity)
    @Delete suspend fun deleteTransaction(transaction: TransactionEntity)

    @Query("UPDATE transactions SET transferGroup = :group WHERE id IN (:ids)")
    suspend fun setTransferGroup(ids: List<Long>, group: String)

    @Query("SELECT * FROM imports ORDER BY createdAt DESC")
    fun observeImports(): Flow<List<ImportEntity>>

    @Query("SELECT * FROM imports WHERE id = :id")
    suspend fun importById(id: Long): ImportEntity?

    @Insert suspend fun insertImport(entry: ImportEntity): Long
    @Update suspend fun updateImport(entry: ImportEntity)

    @Query("DELETE FROM snapshots WHERE importId = :importId")
    suspend fun deleteSnapshotsOfImport(importId: Long)

    @Query("DELETE FROM transactions WHERE importId = :importId")
    suspend fun deleteTransactionsOfImport(importId: Long)

    @Query(
        """
        DELETE FROM products WHERE importId = :importId
          AND id NOT IN (SELECT productId FROM snapshots)
          AND id NOT IN (SELECT productId FROM transactions)
        """
    )
    suspend fun deleteOrphanProductsOfImport(importId: Long)

    @Query("SELECT COUNT(*) FROM snapshots WHERE importId = :importId")
    suspend fun snapshotCountOfImport(importId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE importId = :importId")
    suspend fun transactionCountOfImport(importId: Long): Int
}

@Dao
interface FxDao {
    @Query("SELECT * FROM fx_rates ORDER BY date")
    fun observeAll(): Flow<List<FxRateEntity>>

    @Query("SELECT MIN(date) FROM fx_rates WHERE currency = :currency")
    suspend fun firstDate(currency: String): LocalDate?

    @Query("SELECT MAX(date) FROM fx_rates WHERE currency = :currency")
    suspend fun lastDate(currency: String): LocalDate?

    @Query("SELECT * FROM fx_rates WHERE currency = :currency AND date <= :date ORDER BY date DESC LIMIT 1")
    suspend fun rateOnOrBefore(currency: String, date: LocalDate): FxRateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rates: List<FxRateEntity>)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: Long): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chat(id: Long): ChatEntity?

    @Insert suspend fun insertChat(chat: ChatEntity): Long
    @Update suspend fun updateChat(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: Long)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    suspend fun messages(chatId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: Long): MessageEntity?

    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @Update suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM attachments WHERE messageId IN (SELECT id FROM messages WHERE chatId = :chatId)")
    fun observeAttachments(chatId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId IN (SELECT id FROM messages WHERE chatId = :chatId)")
    suspend fun attachments(chatId: Long): List<AttachmentEntity>

    @Insert suspend fun insertAttachment(attachment: AttachmentEntity): Long

    @Query("UPDATE messages SET proposalStatus = 'UNDONE' WHERE importId = :importId")
    suspend fun markImportUndone(importId: Long)

    @Query("SELECT MAX(id) FROM messages WHERE chatId = :chatId")
    suspend fun lastMessageId(chatId: Long): Long?
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun remove(key: String)
}
