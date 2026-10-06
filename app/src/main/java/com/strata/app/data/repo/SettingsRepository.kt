package com.strata.app.data.repo

import com.strata.app.data.db.SettingEntity
import com.strata.app.data.db.SettingsDao
import kotlinx.coroutines.flow.map

/** Settings live inside the encrypted database, so the API key is protected like the ledger. */
class SettingsRepository(private val dao: SettingsDao) {
    val apiKey = dao.observe(KEY_API)
    val model = dao.observe(KEY_MODEL).map { it ?: DEFAULT_MODEL }
    val privateProvidersOnly = dao.observe(KEY_PRIVATE_ONLY).map { it != "false" }

    suspend fun apiKey(): String? = dao.get(KEY_API)
    suspend fun model(): String = dao.get(KEY_MODEL) ?: DEFAULT_MODEL
    suspend fun privateProvidersOnly(): Boolean = dao.get(KEY_PRIVATE_ONLY) != "false"

    suspend fun setApiKey(value: String) =
        if (value.isBlank()) dao.remove(KEY_API) else dao.put(SettingEntity(KEY_API, value.trim()))

    suspend fun setModel(value: String) = dao.put(SettingEntity(KEY_MODEL, value.trim()))
    suspend fun setPrivateProvidersOnly(value: Boolean) = dao.put(SettingEntity(KEY_PRIVATE_ONLY, value.toString()))

    companion object {
        private const val KEY_API = "openrouter.apiKey"
        private const val KEY_MODEL = "openrouter.model"
        private const val KEY_PRIVATE_ONLY = "openrouter.privateOnly"
        const val DEFAULT_MODEL = "anthropic/claude-sonnet-5.5"
    }
}
