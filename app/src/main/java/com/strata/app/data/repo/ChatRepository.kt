package com.strata.app.data.repo

import com.strata.app.data.db.ChatDao
import com.strata.app.data.db.ChatEntity
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.ProposalStatus

class ChatRepository(private val dao: ChatDao) {
    val chats = dao.observeChats()
    fun chat(id: Long) = dao.observeChat(id)
    fun messages(chatId: Long) = dao.observeMessages(chatId)
    fun attachments(chatId: Long) = dao.observeAttachments(chatId)

    suspend fun newChat(): Long = dao.insertChat(ChatEntity(title = "New chat"))
    suspend fun deleteChat(id: Long) = dao.deleteChat(id)

    suspend fun rename(chatId: Long, title: String) {
        dao.chat(chatId)?.let { dao.updateChat(it.copy(title = title)) }
    }

    suspend fun setProposalStatus(messageId: Long, status: ProposalStatus, importId: Long? = null) {
        val message: MessageEntity = dao.message(messageId) ?: return
        dao.updateMessage(message.copy(proposalStatus = status, importId = importId ?: message.importId))
    }
}
