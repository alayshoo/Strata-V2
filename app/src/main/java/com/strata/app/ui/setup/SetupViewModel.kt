package com.strata.app.ui.setup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.app.Session
import com.strata.app.ai.ModelInfo
import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SpendingCategoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupLists(
    val sources: List<SourceEntity> = emptyList(),
    val assetClasses: List<AssetClassEntity> = emptyList(),
    val categories: List<SpendingCategoryEntity> = emptyList(),
)

data class AiSettingsUi(
    val keyTail: String? = null,
    val model: String = "",
    val privateOnly: Boolean = true,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
)

class SetupViewModel(private val session: Session) : ViewModel() {
    val lists = combine(session.setup.sources, session.setup.assetClasses, session.setup.spendingCategories) { s, c, k -> SetupLists(s, c, k) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupLists())

    private val transient = MutableStateFlow(AiSettingsUi())

    val ai: StateFlow<AiSettingsUi> = combine(session.settings.apiKey, session.settings.model, session.settings.privateProvidersOnly, transient) { key, model, priv, t ->
        t.copy(keyTail = key?.takeLast(4), model = model, privateOnly = priv)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUi())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    fun saveSource(s: SourceEntity) = launchCatching { session.setup.saveSource(s) }
    fun deleteSource(s: SourceEntity) = launchCatching { session.setup.deleteSource(s)?.let { _notice.value = it } }
    fun saveAssetClass(c: AssetClassEntity) = launchCatching { session.setup.saveAssetClass(c) }
    fun deleteAssetClass(c: AssetClassEntity) = launchCatching { session.setup.deleteAssetClass(c)?.let { _notice.value = it } }
    fun saveCategory(c: SpendingCategoryEntity) = launchCatching { session.setup.saveSpendingCategory(c) }
    fun deleteCategory(c: SpendingCategoryEntity) = launchCatching { session.setup.deleteSpendingCategory(c) }
    fun addSuggestedClasses() = launchCatching { session.setup.addSuggestedAssetClasses() }
    fun addSuggestedCategories() = launchCatching { session.setup.addSuggestedSpendingCategories() }

    fun saveApiKey(key: String) = launchCatching {
        session.settings.setApiKey(key)
        transient.update { it.copy(testResult = null) }
    }

    fun setModel(id: String) = launchCatching { session.settings.setModel(id) }
    fun setPrivateOnly(value: Boolean) = launchCatching { session.settings.setPrivateProvidersOnly(value) }

    fun testConnection() = viewModelScope.launch {
        val key = session.settings.apiKey() ?: run {
            transient.update { it.copy(testResult = "Add a key first.", testOk = false) }
            return@launch
        }
        transient.update { it.copy(testing = true, testResult = null) }
        val result = runCatching { session.openRouter.checkKey(key) }
        transient.update {
            it.copy(
                testing = false,
                testOk = result.isSuccess,
                testResult = result.fold(
                    onSuccess = { info ->
                        buildString {
                            append("Connected")
                            info.label?.let { l -> append(" as $l") }
                            info.usage?.let { u -> append(". Used $" + String.format(java.util.Locale.US, "%.2f", u)) }
                            info.limit?.let { l -> append(" of $" + String.format(java.util.Locale.US, "%.2f", l)) }
                            append(".")
                        }
                    },
                    onFailure = { e -> e.message ?: "Could not reach OpenRouter." },
                ),
            )
        }
    }

    fun loadModels() = viewModelScope.launch {
        if (transient.value.models.isNotEmpty()) return@launch
        transient.update { it.copy(modelsLoading = true, modelsError = null) }
        val result = runCatching { session.openRouter.models() }
        transient.update {
            it.copy(
                modelsLoading = false,
                models = result.getOrDefault(emptyList()).sortedBy { m -> m.name.lowercase() },
                modelsError = result.exceptionOrNull()?.message,
            )
        }
    }

    fun exportBackup(uri: Uri, passphrase: CharArray) = launchCatching {
        val (products, transactions) = session.backup.export(uri, passphrase)
        _notice.value = "Backup saved: $products products, $transactions transactions."
    }

    fun restoreBackup(uri: Uri, passphrase: CharArray) = launchCatching {
        val payload = session.backup.restore(uri, passphrase)
        _notice.value = "Restored ${payload.products.size} products and ${payload.transactions.size} transactions."
    }

    private fun launchCatching(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { _notice.value = e.message ?: "Something went wrong." }
    }
}
