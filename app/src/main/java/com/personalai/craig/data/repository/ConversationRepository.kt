package com.personalai.craig.data.repository

import com.personalai.craig.data.db.dao.ConversationDao
import com.personalai.craig.data.db.dao.MessageDao
import com.personalai.craig.data.db.entities.ConversationEntity
import com.personalai.craig.data.db.entities.MessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    suspend fun getOrCreateConversation(id: Long?): Long {
        if (id != null) return id
        val now = System.currentTimeMillis()
        return conversationDao.insert(
            ConversationEntity(title = "New conversation", createdAt = now, updatedAt = now)
        )
    }

    suspend fun updateConversationTitle(id: Long, title: String) {
        val conv = conversationDao.getById(id) ?: return
        conversationDao.update(conv.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    fun getMessagesForConversation(convId: Long): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(convId)

    suspend fun getMessagesSnapshot(convId: Long): List<MessageEntity> =
        messageDao.getMessagesSnapshot(convId)

    suspend fun addMessage(
        convId: Long,
        role: String,
        content: String
    ): Long {
        val now = System.currentTimeMillis()
        val tokenEstimate = (content.length / 4).coerceAtLeast(1)
        val msgId = messageDao.insert(
            MessageEntity(
                conversationId = convId,
                role = role,
                content = content,
                timestamp = now,
                tokenCount = tokenEstimate
            )
        )
        val conv = conversationDao.getById(convId)
        if (conv != null) {
            val title = if (conv.title == "New conversation" && role == "user") {
                content.take(50)
            } else {
                conv.title
            }
            conversationDao.update(conv.copy(title = title, updatedAt = now))
        }
        return msgId
    }

    suspend fun deleteConversation(id: Long) = conversationDao.delete(id)
}
