package com.personalai.craig.web

import android.util.Log
import com.personalai.craig.ai.ClaudeClient
import com.personalai.craig.ai.SystemPromptBuilder
import com.personalai.craig.ai.tools.ToolCall
import com.personalai.craig.ai.tools.ToolResult
import com.personalai.craig.ai.tools.WebTools
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

    /**
     * Execute a voice command that requires OpticSEO web automation.
     * Claude decides which tool calls to make; we execute them and return the result.
     *
     * @param userCommand The transcribed voice command
     * @param conversationHistory Prior messages for context
     * @return Craig's spoken response describing what was done
     */
    suspend fun executeCommand(
        userCommand: String,
        conversationHistory: List<ClaudeClient.Message>
    ): String {
        // Ensure we're logged in before doing anything
        val loggedIn = session.ensureLoggedIn()
        if (!loggedIn) {
            return "I couldn't log in to your OpticSEO account. Please check your credentials in Craig's settings."
        }

        // Read current page state for Claude's context
        val currentPage = webManager.readPageContent()

        val augmentedCommand = """
            $userCommand

            Current OpticSEO page state:
            $currentPage
        """.trimIndent()

        val messages = conversationHistory + ClaudeClient.Message("user", augmentedCommand)
        val systemPrompt = systemPromptBuilder.build(includeWebTools = true)

        return claudeClient.sendWithTools(
            messages = messages,
            systemPrompt = systemPrompt,
            tools = WebTools.ALL,
            toolExecutor = { toolCall -> executeToolCall(toolCall) }
        )
    }

    private suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
        Log.d(TAG, "Executing tool: ${toolCall.name} with input: ${toolCall.input}")

        return try {
            val result = when (toolCall.name) {
                "navigate" -> {
                    val url = toolCall.input["url"] ?: return ToolResult(toolCall.id, "Missing url parameter", isError = true)
                    webManager.navigate(url)
                }
                "click_by_text" -> {
                    val text = toolCall.input["text"] ?: return ToolResult(toolCall.id, "Missing text parameter", isError = true)
                    webManager.clickByText(text)
                }
                "click_by_selector" -> {
                    val selector = toolCall.input["selector"] ?: return ToolResult(toolCall.id, "Missing selector parameter", isError = true)
                    webManager.clickBySelector(selector)
                }
                "fill_input" -> {
                    val selector = toolCall.input["selector"] ?: return ToolResult(toolCall.id, "Missing selector parameter", isError = true)
                    val value = toolCall.input["value"] ?: ""
                    webManager.fillInput(selector, value)
                }
                "read_page" -> {
                    webManager.readPageContent()
                }
                "wait_for_element" -> {
                    val selector = toolCall.input["selector"] ?: return ToolResult(toolCall.id, "Missing selector parameter", isError = true)
                    val timeout = toolCall.input["timeout_ms"]?.toLongOrNull() ?: 8000L
                    webManager.waitForElement(selector, timeout)
                }
                "submit_form" -> {
                    val selector = toolCall.input["selector"] ?: "form"
                    webManager.submitForm(selector)
                }
                "evaluate_js" -> {
                    val script = toolCall.input["script"] ?: return ToolResult(toolCall.id, "Missing script parameter", isError = true)
                    webManager.evaluateJs(script)
                }
                else -> "Unknown tool: ${toolCall.name}"
            }
            ToolResult(toolUseId = toolCall.id, content = result)
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed for ${toolCall.name}: ${e.message}", e)
            ToolResult(toolUseId = toolCall.id, content = "Error: ${e.message}", isError = true)
        }
    }
}
