package com.strata.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** Whole-table access for encrypted export and restore. Chats are not part of a backup. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM sources") suspend fun sources(): List<SourceEntity>
    @Query("SELECT * FROM asset_classes") suspend fun assetClasses(): List<AssetClassEntity>
    @Query("SELECT * FROM spending_categories") suspend fun spendingCategories(): List<SpendingCategoryEntity>
    @Query("SELECT * FROM products") suspend fun products(): List<ProductEntity>
    @Query("SELECT * FROM snapshots") suspend fun snapshots(): List<SnapshotEntity>
    @Query("SELECT * FROM transactions") suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM imports") suspend fun imports(): List<ImportEntity>

    @Insert suspend fun insertSources(rows: List<SourceEntity>)
    @Insert suspend fun insertAssetClasses(rows: List<AssetClassEntity>)
    @Insert suspend fun insertSpendingCategories(rows: List<SpendingCategoryEntity>)
    @Insert suspend fun insertProducts(rows: List<ProductEntity>)
    @Insert suspend fun insertSnapshots(rows: List<SnapshotEntity>)
    @Insert suspend fun insertTransactions(rows: List<TransactionEntity>)
    @Insert suspend fun insertImports(rows: List<ImportEntity>)

    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM snapshots") suspend fun clearSnapshots()
    @Query("DELETE FROM products") suspend fun clearProducts()
    @Query("DELETE FROM imports") suspend fun clearImports()
    @Query("DELETE FROM spending_categories") suspend fun clearSpendingCategories()
    @Query("DELETE FROM asset_classes") suspend fun clearAssetClasses()
    @Query("DELETE FROM sources") suspend fun clearSources()

    @Transaction
    suspend fun replaceAll(
        sources: List<SourceEntity>,
        assetClasses: List<AssetClassEntity>,
        spendingCategories: List<SpendingCategoryEntity>,
        products: List<ProductEntity>,
        snapshots: List<SnapshotEntity>,
        transactions: List<TransactionEntity>,
        imports: List<ImportEntity>,
    ) {
        clearTransactions()
        clearSnapshots()
        clearProducts()
        clearImports()
        clearSpendingCategories()
        clearAssetClasses()
        clearSources()
        insertSources(sources)
        insertAssetClasses(assetClasses)
        insertSpendingCategories(spendingCategories)
        insertImports(imports)
        insertProducts(products)
        insertSnapshots(snapshots)
        insertTransactions(transactions)
    }
}
