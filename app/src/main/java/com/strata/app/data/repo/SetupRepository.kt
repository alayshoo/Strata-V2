package com.strata.app.data.repo

import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.SetupDao
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SpendingCategoryEntity

class SetupRepository(private val dao: SetupDao) {
    val sources = dao.observeSources()
    val assetClasses = dao.observeAssetClasses()
    val spendingCategories = dao.observeSpendingCategories()

    suspend fun saveSource(source: SourceEntity) =
        if (source.id == 0L) dao.insertSource(source.copy(name = source.name.trim())).let { }
        else dao.updateSource(source.copy(name = source.name.trim()))

    suspend fun saveAssetClass(assetClass: AssetClassEntity) =
        if (assetClass.id == 0L) dao.insertAssetClass(assetClass.copy(name = assetClass.name.trim())).let { }
        else dao.updateAssetClass(assetClass.copy(name = assetClass.name.trim()))

    suspend fun saveSpendingCategory(category: SpendingCategoryEntity) =
        if (category.id == 0L) dao.insertSpendingCategory(category.copy(name = category.name.trim())).let { }
        else dao.updateSpendingCategory(category.copy(name = category.name.trim()))

    /** Returns an explanation when the source still has products and cannot be deleted. */
    suspend fun deleteSource(source: SourceEntity): String? {
        val count = dao.productCountForSource(source.id)
        if (count > 0) return "${source.name} still has $count product${if (count == 1) "" else "s"}. Move or delete them first."
        dao.deleteSource(source)
        return null
    }

    suspend fun deleteAssetClass(assetClass: AssetClassEntity): String? {
        val count = dao.productCountForAssetClass(assetClass.id)
        if (count > 0) return "${assetClass.name} is used by $count product${if (count == 1) "" else "s"}. Reassign them first."
        dao.deleteAssetClass(assetClass)
        return null
    }

    /** Transactions keep existing and become uncategorised. */
    suspend fun deleteSpendingCategory(category: SpendingCategoryEntity) = dao.deleteSpendingCategory(category)

    suspend fun addSuggestedAssetClasses() {
        val existing = dao.assetClasses().map { it.name.lowercase() }.toSet()
        SUGGESTED_ASSET_CLASSES.forEachIndexed { index, (name, color, liability) ->
            if (name.lowercase() !in existing) {
                dao.insertAssetClass(AssetClassEntity(name = name, colorKey = color, isLiability = liability, sortOrder = index))
            }
        }
    }

    suspend fun addSuggestedSpendingCategories() {
        val existing = dao.spendingCategories().map { it.name.lowercase() }.toSet()
        SUGGESTED_CATEGORIES.forEach { (name, kind, color) ->
            if (name.lowercase() !in existing) {
                dao.insertSpendingCategory(SpendingCategoryEntity(name = name, kind = kind, colorKey = color))
            }
        }
    }

    companion object {
        val SUGGESTED_ASSET_CLASSES = listOf(
            Triple("Cash", "cobalt", false),
            Triple("ETFs", "saffron", false),
            Triple("Stocks", "magenta", false),
            Triple("Bonds", "sky", false),
            Triple("Crypto", "jade", false),
            Triple("Pension", "coral", false),
            Triple("Real estate", "violet", false),
            Triple("Credit", "graphite", true),
        )
        val SUGGESTED_CATEGORIES = listOf(
            Triple("Housing", FlowKind.EXPENSE, "cobalt"),
            Triple("Groceries", FlowKind.EXPENSE, "saffron"),
            Triple("Dining out", FlowKind.EXPENSE, "magenta"),
            Triple("Transport", FlowKind.EXPENSE, "sky"),
            Triple("Health", FlowKind.EXPENSE, "jade"),
            Triple("Shopping", FlowKind.EXPENSE, "coral"),
            Triple("Subscriptions", FlowKind.EXPENSE, "violet"),
            Triple("Travel", FlowKind.EXPENSE, "lime"),
            Triple("Utilities", FlowKind.EXPENSE, "graphite"),
            Triple("Salary", FlowKind.INCOME, "jade"),
            Triple("Other income", FlowKind.INCOME, "saffron"),
        )
    }
}
