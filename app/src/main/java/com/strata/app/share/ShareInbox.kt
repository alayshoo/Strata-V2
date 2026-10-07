package com.strata.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SharedFile(val uri: Uri, val name: String)

/** What another app handed us through the share sheet. */
data class SharedBundle(val files: List<SharedFile>, val text: String?)

/**
 * Receives documents shared from other apps. Each file is copied into app-private storage as
 * soon as it arrives, because the sending app's read grant can lapse before the user unlocks
 * Strata and taps send. Copies are deleted once read, and leftovers after a day.
 */
class ShareInbox(private val context: Context) {
    private val dir = File(context.cacheDir, "shared")

    private val _pending = MutableStateFlow<SharedBundle?>(null)
    val pending: StateFlow<SharedBundle?> = _pending

    /** Returns true when the intent was a share we could take. */
    suspend fun accept(intent: Intent): Boolean = withContext(Dispatchers.IO) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return@withContext false
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            }
            if (isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
        }
        val files = uris.mapNotNull { copy(it) }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (files.isEmpty() && text == null) return@withContext false
        _pending.value = SharedBundle(files, text)
        true
    }

    /** Hands the pending share to whoever opens a chat for it. */
    fun take(): SharedBundle? = _pending.value.also { _pending.value = null }

    fun isOurs(uri: Uri): Boolean = uri.scheme == "file" && uri.path?.startsWith(dir.path) == true

    fun release(uri: Uri) {
        if (isOurs(uri)) uri.path?.let { File(it).parentFile?.deleteRecursively() }
    }

    fun clearStale(maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private fun copy(source: Uri): SharedFile? = runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: source.lastPathSegment ?: "document"
        // One folder per file keeps the original name, which later tells us the type.
        val folder = File(dir, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(folder, name.replace('/', '_'))
        resolver.openInputStream(source)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        SharedFile(Uri.fromFile(target), name)
    }.getOrNull()
}
