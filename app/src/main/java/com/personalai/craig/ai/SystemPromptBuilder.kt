package com.personalai.craig.ai

import com.personalai.craig.data.db.dao.AppContextDao
import com.personalai.craig.data.db.dao.UserProfileDao
import com.personalai.craig.data.preferences.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptBuilder @Inject constructor(
    private val memoryManager: MemoryManager,
    private val userProfileDao: UserProfileDao,
    private val appContextDao: AppContextDao,
    private val prefs: SecurePreferences
) {
    companion object {
        private const val MAX_MEMORY_CHARS  = 3_000
        private const val MAX_SCREEN_CHARS  = 1_500
        private const val SCREEN_MAX_AGE_MS = 30_000L
    }

    /**
     * Builds the full system prompt injected into every Claude API call.
     * Includes user identity, learned facts, current screen context, and instructions.
     */
    suspend fun build(includeWebTools: Boolean = false): String = withContext(Dispatchers.IO) {
        val assistantName = prefs.assistantName.first()
        val currentTime   = SimpleDateFormat("EEEE, MMMM d yyyy, h:mm a", Locale.US).format(Date())
        val profile       = loadProfile()
        val memory        = memoryManager.getMemorySummary().take(MAX_MEMORY_CHARS)
        val summaries     = memoryManager.getRecentConversationSummaries(3)
        val screenCtx     = loadScreenContext().take(MAX_SCREEN_CHARS)

        buildString {
            appendLine("You are $assistantName, a highly personalized AI assistant running on an Android phone.")
            appendLine("You are warm, helpful, and adapt your communication style to the user's preferences.")
            appendLine("Current date and time: $currentTime")
            appendLine()

            if (profile.isNotEmpty()) {
                appendLine("## User Profile")
                appendLine(profile)
                appendLine()
            }

            if (memory.isNotEmpty()) {
                appendLine("## What You Know About the User")
                appendLine(memory)
                appendLine()
            }

            if (summaries.isNotEmpty()) {
                appendLine("## Recent Conversation Summaries")
                appendLine(summaries)
                appendLine()
            }

            if (screenCtx.isNotEmpty()) {
                appendLine("## Current Screen Context")
                appendLine(screenCtx)
                appendLine("Use this if the user refers to something on their screen.")
                appendLine()
            }

            if (includeWebTools) {
                appendLine("## OpticSEO Website Access")
                appendLine("You have full control of the user's OpticSEO website at www.opticseoservices.com.")
                appendLine("When asked to perform actions on the site, use the available web tools.")
                appendLine("Always call read_page first to understand the current state before acting.")
                appendLine("Narrate what you are doing so the user knows you are working on it.")
                appendLine()
            }

            appendLine("## Instructions")
            appendLine("- Keep voice responses concise: 2-4 sentences unless the user asks for detail.")
            appendLine("- Do not use markdown formatting in voice responses (no **, ##, or bullet symbols).")
            appendLine("- If unsure about a personal fact, ask rather than assume.")
            appendLine("- Remember and use details the user shares about themselves.")
            appendLine("- Respond in the user's language if they speak in a language other than English.")
        }.trimEnd()
    }

    private suspend fun loadProfile(): String {
        val entries = userProfileDao.getAll()
        return entries.joinToString("\n") { "- ${it.key.replace('_', ' ')}: ${it.value}" }
    }

    private suspend fun loadScreenContext(): String {
        val ctx = appContextDao.getLatest() ?: return ""
        val ageMs = System.currentTimeMillis() - ctx.timestamp
        if (ageMs > SCREEN_MAX_AGE_MS) return ""
        val title = ctx.windowTitle?.let { " ($it)" } ?: ""
        return "App: ${ctx.packageName}$title\nScreen content: ${ctx.screenText}"
    }
}
