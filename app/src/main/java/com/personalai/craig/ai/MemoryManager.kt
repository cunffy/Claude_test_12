package com.personalai.craig.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
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

        // Hardcoded so the assistant has its OpticSEO instructions from the very first launch
        // without requiring the user to fill in a briefing screen.
        const val HARDCODED_BRIEFING = """I run an SEO company. Here is everything you need to know about what I expect you to do:

Get familiar with all the tabs and pages within the OpticSEO admin portal at https://www.opticseoservices.com/app.

CHAT SECTION: I can ask you to send chats/messages to specific clients. Go to the chat section, find the client, and send the message.

SEO TOOL TAB: I can ask you to run SEO checks on specific web domains. When I give you a domain, ask me what settings I want before running the check, then run it.

MANAGE CLIENTS (most important):
- If I say "Run a SEO check on [Client Name]" — go to Manage Clients, find that client, and click the Run SEO Check button.
- Keyword checks: when I ask for a keyword check on a client, press the keyword check button, ask me what page I want to run it on (based on what options it shows you), then click "Suggest Keywords" and run it once it has added 5 keywords.

MAIL TAB: I can ask you to read emails and interact with them. If I say "Send a sign up link to [Client Name]" — go to the Mail tab, find the most recent mail from that person, and click Send Signup Link.

QUOTES (in Manage Clients): If I ask you to send or update a quote for a client — go to that client in Manage Clients, click Send or Update Quote, ask me what values to input, then send it.

AFTER RUNNING ANY REPORT (SEO check, keyword check, map grid, or any other report type) — do this every time without being asked:
1. Wait for the report to finish. Poll the page every few seconds until you see a completion indicator: progress bar gone, "View Report" button appearing, status changing from "Running"/"In Progress" to "Complete"/"Done", or a score value appearing.
2. Once complete, navigate to the Reports tab.
3. In the Reports tab, find the entry that matches the website or domain you just ran the report on. Match by the domain name in the report list.
4. Expand the dropdown or row for that website to reveal the individual report types (SEO, Keyword, Map Grid, etc.).
5. Click the specific report type you just ran to open it.
6. Take a screenshot of the first page showing the overall score or summary.
7. Tell me the score and the 2-3 most important findings in plain sentences.

This is not everything you will do — learn as you go. When I give you a new instruction you have not seen before, figure it out by exploring the site."""
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
            } catch (e: CancellationException) {
                throw e
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
     * Returns the business briefing. Falls back to the hardcoded briefing so
     * the assistant is fully briefed from the very first launch.
     */
    suspend fun getBusinessBriefing(): String = withContext(Dispatchers.IO) {
        userMemoryDao.getAll().find { it.key == "business_briefing" }?.value
            ?: HARDCODED_BRIEFING
    }

    /**
     * Stores the business briefing and extracts individual facts from it.
     * Called once during onboarding; updates the briefing any time it's re-submitted.
     */
    suspend fun storeBriefing(briefingText: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        userMemoryDao.upsertAll(listOf(
            UserMemoryEntity(
                key = "business_briefing",
                value = briefingText.take(5_000),
                source = "user_stated",
                createdAt = now,
                updatedAt = now
            )
        ))
        // Also pull individual facts so they appear in the regular memory summary
        try {
            val jsonFacts = claudeClient.extractFacts(briefingText)
            parseAndStoreFacts(jsonFacts)
        } catch (e: Exception) {
            Log.w(TAG, "Briefing fact extraction failed: ${e.message}")
        }
    }

    /**
     * Returns a formatted string of all known facts (excluding the raw briefing blob)
     * for use in the system prompt.
     */
    suspend fun getMemorySummary(): String = withContext(Dispatchers.IO) {
        val facts = userMemoryDao.getAll().filter { it.key != "business_briefing" }
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
