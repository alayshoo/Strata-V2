package com.strata.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.app.Session
import com.strata.app.ai.RunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatListViewModel(private val session: Session) : ViewModel() {
    val chats = session.chats.chats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runs = session.chatController.runs

    suspend fun newChat(): Long = session.chats.newChat()
    fun delete(id: Long) = viewModelScope.launch { session.chats.deleteChat(id) }
}

class ConversationViewModel(private val session: Session, private val chatId: Long) : ViewModel() {
    data class PendingFile(val uri: Uri, val name: String)

    private val _pending = MutableStateFlow<List<PendingFile>>(emptyList())
    val pending: StateFlow<List<PendingFile>> = _pending

    private val lookup = combine(
        session.ledger.products, session.setup.sources, session.setup.spendingCategories,
        session.ledger.snapshots, session.ledger.transactions,
    ) { p, s, c, snaps, txs -> Lookup(p, s, c, snaps, txs) }

    private val items = combine(session.chats.messages(chatId), session.chats.attachments(chatId), lookup) { m, a, l ->
        buildChatItems(m, a, l)
    }.flowOn(Dispatchers.Default)

    val ui: StateFlow<ConversationUi> = combine(
        session.chats.chat(chatId),
        items,
        session.chatController.runs.map { it[chatId] ?: RunState() },
        session.settings.apiKey,
        session.settings.model,
    ) { chat, items, run, key, model ->
        ConversationUi(chat?.title ?: "Chat", items, run, hasApiKey = !key.isNullOrBlank(), model = model)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUi("", emptyList()))

    fun addFile(uri: Uri, name: String) = _pending.update { it + PendingFile(uri, name) }
    fun removeFile(index: Int) = _pending.update { it.filterIndexed { i, _ -> i != index } }

    fun send(text: String) {
        val files = _pending.value
        if (text.isBlank() && files.isEmpty()) return
        _pending.value = emptyList()
        val message = text.ifBlank { "Here is a statement. Please record what it shows." }
        session.chatController.send(chatId, message, files.map { it.uri })
    }

    fun apply(p: ProposalUi) = session.chatController.apply(chatId, p.messageId, p.json)
    fun discard(p: ProposalUi) = session.chatController.discard(p.messageId)
    fun undo(p: ProposalUi) { p.importId?.let { session.chatController.undo(it) } }
    fun dismissError() = session.chatController.dismissError(chatId)
    fun stop() = session.chatController.stop(chatId)
}
