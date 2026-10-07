package com.strata.app.ai

import android.net.Uri
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.repo.ChatRepository
import com.strata.app.data.repo.LedgerRepository
import com.strata.app.share.ShareInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

data class RunState(
    val progress: String? = null,
    val error: String? = null,
    val round: Int = 0,
    /** When the current step began, for the live timer. */
    val since: Long = 0,
    /** When the whole turn began. */
    val startedAt: Long = 0,
) {
    val busy: Boolean get() = progress != null
}

/**
 * Owns assistant turns so they keep running when the user leaves the conversation,
 * and applies or undoes review cards.
 */
class ChatController(
    private val agent: Agent,
    private val documents: () -> DocumentExtractor,
    private val chats: ChatRepository,
    private val ledger: LedgerRepository,
    private val shareInbox: ShareInbox,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _runs = MutableStateFlow<Map<Long, RunState>>(emptyMap())
    val runs: StateFlow<Map<Long, RunState>> = _runs

    private val jobs = ConcurrentHashMap<Long, Job>()

    fun send(chatId: Long, text: String, uris: List<Uri>) {
        if (_runs.value[chatId]?.busy == true) return
        val startedAt = System.currentTimeMillis()
        set(chatId, RunState(progress = if (uris.isEmpty()) "Starting" else "Reading your files", since = startedAt, startedAt = startedAt))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val prepared = try {
                    uris.map { documents().prepare(it) }
                } finally {
                    // Copies of shared files are only needed until they have been read.
                    uris.forEach(shareInbox::release)
                }
                agent.send(chatId, text, prepared) { p ->
                    set(chatId, RunState(progress = p.label, round = p.round, since = p.since, startedAt = startedAt))
                }
                set(chatId, RunState())
            } catch (e: CancellationException) {
                set(chatId, RunState())
                throw e
            } catch (e: Exception) {
                set(chatId, RunState(error = e.message ?: "Something went wrong."))
            } finally {
                jobs.remove(chatId, coroutineContext[Job])
            }
        }
        jobs[chatId] = job
        job.start()
    }

    /** Cancels the running turn, including an in-flight request to the model. */
    fun stop(chatId: Long) {
        jobs[chatId]?.cancel()
    }

    fun dismissError(chatId: Long) = set(chatId, RunState())

    fun apply(chatId: Long, messageId: Long, proposalJson: String) {
        scope.launch {
            try {
                val changes = json.decodeFromString(ChangeSet.serializer(), proposalJson)
                val result = ledger.apply(changes, chatId)
                chats.setProposalStatus(messageId, ProposalStatus.APPLIED, result.importId)
            } catch (e: Exception) {
                set(chatId, RunState(error = "Could not apply: ${e.message}"))
            }
        }
    }

    fun discard(messageId: Long) {
        scope.launch { chats.setProposalStatus(messageId, ProposalStatus.DISCARDED) }
    }

    fun undo(importId: Long) {
        scope.launch {
            ledger.undoImport(importId)
        }
    }

    private fun set(chatId: Long, state: RunState) = _runs.update { it + (chatId to state) }
}
