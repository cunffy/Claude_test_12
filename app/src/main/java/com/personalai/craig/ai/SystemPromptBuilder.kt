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
        private const val MAX_MEMORY_CHARS  = 6_000
        private const val MAX_SCREEN_CHARS  = 1_500
        private const val SCREEN_MAX_AGE_MS = 30_000L
    }

    suspend fun build(includeWebTools: Boolean = false): String = withContext(Dispatchers.IO) {
        val assistantName = prefs.assistantName.first()
        val currentTime   = SimpleDateFormat("EEEE, MMMM d yyyy, h:mm a", Locale.US).format(Date())
        val briefing      = memoryManager.getBusinessBriefing()
        val memory        = memoryManager.getMemorySummary().take(MAX_MEMORY_CHARS)
        val summaries     = memoryManager.getRecentConversationSummaries(3)
        val screenCtx     = loadScreenContext().take(MAX_SCREEN_CHARS)

        buildString {
            appendLine("You are $assistantName, a dedicated business assistant.")
            appendLine("Your entire purpose is to help manage and grow this business through its OpticSEO website.")
            appendLine("You have full knowledge of the business (see briefing below) and complete control of the OpticSEO website.")
            appendLine("Current date and time: $currentTime")
            appendLine()

            if (briefing.isNotEmpty()) {
                appendLine("## Business Briefing")
                appendLine("Everything you need to know about this business:")
                appendLine(briefing)
                appendLine()
            }

            if (memory.isNotEmpty()) {
                appendLine("## What You've Learned")
                appendLine(memory)
                appendLine()
            }

            if (summaries.isNotEmpty()) {
                appendLine("## Recent Conversation History")
                appendLine(summaries)
                appendLine()
            }

            if (screenCtx.isNotEmpty()) {
                appendLine("## Current Screen")
                appendLine(screenCtx)
                appendLine()
            }

            appendLine("## Your Capabilities on OpticSEO")
            appendLine("The website is at https://opticseoservices.com/app — this is your starting point for every task.")
            appendLine("You can navigate any page, find and manage clients, run SEO checks and reports,")
            appendLine("fill forms, click buttons, read page content, and execute any multi-step task on the website.")
            appendLine("You are already logged in before any tool call runs, so go straight to the task.")
            appendLine()

            appendLine("## How to Respond")
            appendLine("- Be direct and action-oriented — you are a business tool, not a chatbot.")
            appendLine("- When executing website tasks, narrate each step briefly so the user knows what's happening.")
            appendLine("- Proactively suggest improvements and next steps based on what you see.")
            appendLine("- Remember and build on everything the user teaches you.")
            appendLine("- Keep responses concise. No markdown formatting — plain natural sentences only.")
            appendLine("- If asked something you don't know about the business, ask the user to tell you.")
        }.trimEnd()
    }

    private suspend fun loadScreenContext(): String {
        val ctx = appContextDao.getLatest() ?: return ""
        if (System.currentTimeMillis() - ctx.timestamp > SCREEN_MAX_AGE_MS) return ""
        val title = ctx.windowTitle?.let { " ($it)" } ?: ""
        return "App: ${ctx.packageName}$title\nContent: ${ctx.screenText}"
    }
}
