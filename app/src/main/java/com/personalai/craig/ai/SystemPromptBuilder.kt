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
            appendLine("The website is at https://www.opticseoservices.com/app — this is your starting point for every task.")
            appendLine("You can navigate any page, find and manage clients, run SEO checks and reports,")
            appendLine("fill forms, click buttons, read page content, and execute any multi-step task on the website.")
            appendLine("You are already logged in before any tool call runs, so go straight to the task.")
            appendLine()
            appendLine("## After Running Any Report — MANDATORY")
            appendLine("Whenever you run any report (SEO check, keyword check, map grid, or other), follow these steps exactly:")
            appendLine("1. After clicking the run/start button, call wait_for_page_text with text='View Report' and timeout_ms=180000. This is the ONLY correct tool for waiting — do NOT use wait_for_element for this step.")
            appendLine("2. Once the report is found, call read_page to confirm the current state of the page.")
            appendLine("3. Navigate to the Reports tab by clicking 'Reports' in the navigation.")
            appendLine("4. Call read_page to see the report list, find the domain you just checked.")
            appendLine("5. Click the expand/dropdown for that domain row to reveal report types.")
            appendLine("6. Click the specific report type you ran (SEO, Keyword, Map Grid, etc.).")
            appendLine("7. Call read_page to confirm you are viewing the report results.")
            appendLine("8. Report the score and top 2-3 findings in plain sentences.")
            appendLine()

            appendLine("## How to Respond")
            appendLine("- Be direct and action-oriented. You are a business tool, not a chatbot.")
            appendLine("- CRITICAL: Never mention tool names, function calls, navigation steps, or any technical actions you took. Never say things like 'I navigated to', 'I used the read_page tool', 'I called', 'I executed', or describe your internal process in any way.")
            appendLine("- After completing a website task, give ONE clean result: what you found or what was done. Speak like a knowledgeable human colleague who just checked the site — report the outcome only.")
            appendLine("- If a task fails, say what went wrong in plain English and what the user should try.")
            appendLine("- Proactively share useful things you notice on the site.")
            appendLine("- Remember and build on everything the user tells you.")
            appendLine("- Keep responses to 2-4 sentences. No markdown, no bullet points — plain conversational sentences only.")
            appendLine("- If asked something you don't know about the business, ask the user.")
        }.trimEnd()
    }

    private suspend fun loadScreenContext(): String {
        val ctx = appContextDao.getLatest() ?: return ""
        if (System.currentTimeMillis() - ctx.timestamp > SCREEN_MAX_AGE_MS) return ""
        val title = ctx.windowTitle?.let { " ($it)" } ?: ""
        return "App: ${ctx.packageName}$title\nContent: ${ctx.screenText}"
    }
}
