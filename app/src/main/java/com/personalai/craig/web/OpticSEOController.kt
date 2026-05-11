package com.personalai.craig.web

import android.util.Log
import com.personalai.craig.ai.ClaudeClient
import com.personalai.craig.ai.SystemPromptBuilder
import com.personalai.craig.ai.tools.ToolCall
import com.personalai.craig.ai.tools.ToolResult
import com.personalai.craig.ai.tools.WebTools
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Claude tool-use calls with WebAutomationManager to perform
 * actions on the OpticSEO website based on voice commands.
 */
@Singleton
class OpticSEOController @Inject constructor(
    private val claudeClient: ClaudeClient,
    private val webManager: WebAutomationManager,
    private val session: OpticSEOSession,
    private val systemPromptBuilder: SystemPromptBuilder
) {
    companion object {
        private const val TAG = "OpticSEOController"
    }

    data class CommandResult(val text: String, val screenshotBase64: String?)

    /**
     * Execute a voice command that requires OpticSEO web automation.
     * Claude decides which tool calls to make; we execute them and return the result.
     *
     * @param userCommand The transcribed voice command
     * @param conversationHistory Prior messages for context
     * @param captureScreenshot If true, always capture a screenshot after execution
     * @return CommandResult with Craig's spoken response and an optional screenshot
     */
    suspend fun executeCommand(
        userCommand: String,
        conversationHistory: List<ClaudeClient.Message>,
        captureScreenshot: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): CommandResult {
        val hadError = AtomicBoolean(false)

        // For pure screenshot requests, capture current WebView state without
        // forcing a login/navigate cycle that might fail.
        val isPureScreenshot = captureScreenshot &&
            userCommand.lowercase().let { u ->
                listOf("opticseo", "optic seo", "seo", "client", "website", "rank", "audit")
                    .none { u.contains(it) }
            }
        if (isPureScreenshot) {
            val shot = try { webManager.captureScreenshot() } catch (e: Exception) { null }
            return CommandResult("Here's what's currently on screen.", shot)
        }

        onProgress?.invoke("Connecting to OpticSEO…")
        val loggedIn = session.ensureLoggedIn()
        if (!loggedIn) {
            return CommandResult(
                "I couldn't log in to OpticSEO. Please check your credentials in Settings.",
                null
            )
        }

        // Navigate to app if not already there
        val currentUrl = webManager.getCurrentUrl().lowercase()
        if (!currentUrl.contains("opticseoservices.com") || currentUrl.contains("login")) {
            onProgress?.invoke("Opening your OpticSEO portal.")
            webManager.navigate(OpticSEOSession.APP_URL)
        }

        val currentPage = webManager.readPageContent()
        val augmentedCommand = """
            $userCommand

            Current OpticSEO page:
            $currentPage
        """.trimIndent()

        val messages = conversationHistory + ClaudeClient.Message("user", augmentedCommand)
        val systemPrompt = systemPromptBuilder.build(includeWebTools = true)

        val responseText = claudeClient.sendWithTools(
            messages = messages,
            systemPrompt = systemPrompt,
            tools = WebTools.ALL,
            toolExecutor = { toolCall -> executeToolCall(toolCall, hadError, onProgress) }
        )

        val screenshotIfNeeded: String? = if (captureScreenshot || hadError.get()) {
            try { webManager.captureScreenshot() }
            catch (e: Exception) { Log.e(TAG, "Screenshot failed: ${e.message}"); null }
        } else null

        return CommandResult(responseText, screenshotIfNeeded)
    }

    private suspend fun executeToolCall(
        toolCall: ToolCall,
        hadError: AtomicBoolean,
        onProgress: ((String) -> Unit)? = null
    ): ToolResult {
        Log.d(TAG, "Executing tool: ${toolCall.name} with input: ${toolCall.input}")

        return try {
            val result = when (toolCall.name) {
                "navigate" -> {
                    val url = toolCall.input["url"] ?: return ToolResult(toolCall.id, "Missing url parameter", isError = true).also { hadError.set(true) }
                    val navResult = webManager.navigate(url)
                    // Fire progress AFTER successful navigation so the message reflects reality
                    if (!navResult.contains("timed out")) {
                        when {
                            url.contains("report", ignoreCase = true) ->
                                onProgress?.invoke("Now on the reports page — looking for your report.")
                            url.contains("manage", ignoreCase = true) || url.contains("client", ignoreCase = true) ->
                                onProgress?.invoke("On the clients page — searching for your client.")
                            url.contains("opticseoservices.com") ->
                                onProgress?.invoke("Loaded the OpticSEO portal.")
                        }
                    }
                    navResult
                }
                "click_by_text" -> {
                    val text = toolCall.input["text"] ?: return ToolResult(toolCall.id, "Missing text parameter", isError = true).also { hadError.set(true) }
                    val clickResult = webManager.clickByText(text)
                    // Only announce meaningful clicks that actually worked
                    if (!clickResult.contains("not found")) {
                        val lower = text.lowercase()
                        when {
                            lower.contains("run seo") || lower.contains("seo check") ->
                                onProgress?.invoke("SEO check started — this usually takes 1 to 3 minutes.")
                            lower.contains("keyword check") || lower.contains("suggest keyword") ->
                                onProgress?.invoke("Keyword analysis started.")
                            lower.contains("view report") || lower.contains("open report") ->
                                onProgress?.invoke("Opening your report now.")
                            lower.contains("run") && (lower.contains("check") || lower.contains("report") || lower.contains("audit")) ->
                                onProgress?.invoke("Check started — waiting for it to finish.")
                        }
                    }
                    clickResult
                }
                "click_by_selector" -> {
                    val selector = toolCall.input["selector"] ?: return ToolResult(toolCall.id, "Missing selector parameter", isError = true).also { hadError.set(true) }
                    webManager.clickBySelector(selector)
                }
                "fill_input" -> {
                    val selector = toolCall.input["selector"] ?: return ToolResult(toolCall.id, "Missing selector parameter", isError = true).also { hadError.set(true) }
                    val value = toolCall.input["value"] ?: ""
                    webManager.fillInput(selector, value)
                }
                "read_page" -> {
                    webManager.readPageContent()
                }
                "wait_for_element" -> {
                    val selector = toolCall.input["selector"] ?: return ToolResult(toolCall.id, "Missing selector parameter", isError = true).also { hadError.set(true) }
                    val timeout = toolCall.input["timeout_ms"]?.toLongOrNull() ?: 8_000L
                    webManager.waitForElement(selector, timeout)
                }
                "wait_for_page_text" -> {
                    val text = toolCall.input["text"] ?: return ToolResult(toolCall.id, "Missing text parameter", isError = true).also { hadError.set(true) }
                    val timeout = toolCall.input["timeout_ms"]?.toLongOrNull() ?: 180_000L
                    onProgress?.invoke("Waiting for the report to complete — this can take a couple of minutes.")
                    val waitResult = webManager.waitForPageText(text, timeout)
                    if (waitResult.contains("text found")) {
                        onProgress?.invoke("Report is done — heading to collect the results.")
                    }
                    waitResult
                }
                "submit_form" -> {
                    val selector = toolCall.input["selector"] ?: "form"
                    webManager.submitForm(selector)
                }
                "evaluate_js" -> {
                    val script = toolCall.input["script"] ?: return ToolResult(toolCall.id, "Missing script parameter", isError = true).also { hadError.set(true) }
                    webManager.evaluateJs(script)
                }
                else -> "Unknown tool: ${toolCall.name}"
            }
            val toolResult = ToolResult(toolUseId = toolCall.id, content = result)
            if (toolResult.isError) hadError.set(true)
            toolResult
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed for ${toolCall.name}: ${e.message}", e)
            hadError.set(true)
            ToolResult(toolUseId = toolCall.id, content = "Error: ${e.message}", isError = true)
        }
    }
}
