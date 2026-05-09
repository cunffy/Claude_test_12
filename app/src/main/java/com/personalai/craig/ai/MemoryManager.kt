package com.personalai.craig.ai

import android.util.Log
import com.personalai.craig.data.db.dao.ConversationDao
import com.personalai.craig.data.db.dao.MessageDao
import com.personalai.craig.data.db.dao.UserMemoryDao
import com.personalai.craig.data.db.entities.UserMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor(
    private val claudeClient: ClaudeClient,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val userMemoryDao: UserMemoryDao
) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val SUMMARIZE_CUTOFF_MS = 24 * 60 * 60 * 1000L
        private const val MAX_MESSAGES_PER_SUMMARY = 50
    }

    /**
     * Called after every conversation turn to extract and persist personal facts.
     */
    suspend fun extractAndStoreFactsFromExchange(userMessage: String, assistantResponse: String) =
        withContext(Dispatchers.IO) {
            val conversationText = "User: $userMessage\nAssistant: $assistantResponse"
            try {
                val jsonFacts = claudeClient.extractFacts(conversationText)
                parseAndStoreFacts(jsonFacts)
            } catch (e: Exception) {
                Log.w(TAG, "Fact extraction failed: ${e.message}")
            }
        }

    /**
     * Run by WorkManager daily. Summarizes unsummarized conversations older than 24h.
     */
    suspend fun summarizeOldConversations() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - SUMMARIZE_CUTOFF_MS
        val conversations = conversationDao.getUnsummarized(cutoff)

        for (conv in conversations) {
            val messages = messageDao.getMessagesSnapshot(conv.id)
            if (messages.isEmpty()) continue

            val transcript = messages
                .take(MAX_MESSAGES_PER_SUMMARY)
                .joinToString("\n") { "${it.role.replaceFirstChar { c -> c.uppercase() }}: ${it.content}" }

            try {
                val summary = claudeClient.summarizeConversation(transcript)
                conversationDao.update(
                    conv.copy(
                        isSummarized = true,
                        summary = summary,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Summarization failed for conversation ${conv.id}: ${e.message}")
            }
        }
    }

    /**
     * Returns a formatted string of all known user facts for use in the system prompt.
     */
    suspend fun getMemorySummary(): String = withContext(Dispatchers.IO) {
        val facts = userMemoryDao.getAll()
        if (facts.isEmpty()) return@withContext ""
        facts.joinToString("\n") { "- ${it.key.replace('_', ' ')}: ${it.value}" }
    }

    /**
     * Returns recent conversation summaries for additional context.
     */
    suspend fun getRecentConversationSummaries(limit: Int = 5): String =
        withContext(Dispatchers.IO) {
            val summaries = conversationDao.getRecentSummarized(limit)
            if (summaries.isEmpty()) return@withContext ""
            summaries
                .mapNotNull { it.summary }
                .joinToString("\n\n") { "- $it" }
        }

    private suspend fun parseAndStoreFacts(json: String) {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return

        // Extract JSON array even if Claude adds extra text around it
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start < 0 || end <= start) return

        try {
            val array = JSONArray(trimmed.substring(start, end + 1))
            val memories = (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val key = obj.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = obj.optString("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val source = obj.optString("source", "inferred")
                val now = System.currentTimeMillis()
                UserMemoryEntity(
                    key = key,
                    value = value,
                    source = source,
                    createdAt = now,
                    updatedAt = now
                )
            }
            if (memories.isNotEmpty()) {
                userMemoryDao.upsertAll(memories)
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Could not parse facts JSON: $json")
        }
    }
}
