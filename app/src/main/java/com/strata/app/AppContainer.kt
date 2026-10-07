package com.strata.app

import android.content.Context
import com.strata.app.ai.Agent
import com.strata.app.ai.ChatController
import com.strata.app.ai.DocumentExtractor
import com.strata.app.ai.OpenRouterClient
import com.strata.app.backup.BackupManager
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.repo.ChatRepository
import com.strata.app.data.repo.FxRepository
import com.strata.app.data.repo.LedgerRepository
import com.strata.app.data.repo.SettingsRepository
import com.strata.app.data.repo.SetupRepository
import com.strata.app.data.security.KeyVault
import com.strata.app.share.SharedBundle
import com.strata.app.share.ShareInbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Everything that needs the decrypted database. Exists only after a successful unlock. */
class Session(context: Context, val db: StrataDatabase, http: OkHttpClient, shareInbox: ShareInbox) {
    val setup = SetupRepository(db.setupDao())
    val ledger = LedgerRepository(db)
    val settings = SettingsRepository(db.settingsDao())
    val fx = FxRepository(db.fxDao(), http)
    val chats = ChatRepository(db.chatDao())
    val openRouter = OpenRouterClient(http)
    val agent = Agent(db, openRouter, settings) { fx.table.first() }
    val documents by lazy { DocumentExtractor(context) }
    val backup = BackupManager(db, context.contentResolver)
    val chatController = ChatController(agent, { documents }, chats, ledger, shareInbox)

    /** Shared files waiting for the conversation that was opened for them. */
    val drafts = ConcurrentHashMap<Long, SharedBundle>()
}

class AppContainer(private val context: Context) {
    val vault by lazy { KeyVault(context) }
    val shareInbox = ShareInbox(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Keep-alive bytes can defeat the read timeout; this caps any single request.
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    /** Set when the app comes back after a while in the background; the UI asks for presence again. */
    val relockRequired = MutableStateFlow(false)
    private var backgroundedAt = 0L

    fun onBackground() { backgroundedAt = System.currentTimeMillis() }

    fun onForeground() {
        val away = System.currentTimeMillis() - backgroundedAt
        if (backgroundedAt != 0L && away > RELOCK_AFTER_MS && _session.value != null) relockRequired.value = true
    }

    fun unlock(passphrase: ByteArray) {
        if (_session.value != null) return
        _session.value = Session(context, StrataDatabase.open(context, passphrase), http, shareInbox)
    }

    private companion object {
        const val RELOCK_AFTER_MS = 60_000L
    }

    /** Wipes the key and the database. Used only when the key is permanently gone. */
    fun eraseEverything() {
        _session.value?.db?.close()
        _session.value = null
        vault.reset()
        context.deleteDatabase(StrataDatabase.FILE_NAME)
    }
}
