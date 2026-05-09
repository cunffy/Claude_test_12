package com.personalai.craig.ai.tools

/**
 * Describes a tool Craig can call to control the OpticSEO website.
 * These are sent to Claude as part of the tool_use API request.
 */
data class WebToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParam>
)

data class ToolParam(
    val type: String,
    val description: String,
    val required: Boolean = true
)

/**
 * A parsed tool call returned by Claude in a tool_use content block.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val input: Map<String, String>
)

/**
 * Result of executing a tool call, sent back to Claude as a tool result.
 */
data class ToolResult(
    val toolUseId: String,
    val content: String,
    val isError: Boolean = false
)

object WebTools {
    val NAVIGATE = WebToolDefinition(
        name = "navigate",
        description = "Navigate the browser to a specific URL on the OpticSEO website.",
        parameters = mapOf(
            "url" to ToolParam("string", "The full URL to navigate to")
        )
    )

    val CLICK_BY_TEXT = WebToolDefinition(
        name = "click_by_text",
        description = "Find and click a button, link, or interactive element by its visible text content.",
        parameters = mapOf(
            "text" to ToolParam("string", "The visible text of the element to click (case-insensitive partial match)")
        )
    )

    val CLICK_BY_SELECTOR = WebToolDefinition(
        name = "click_by_selector",
        description = "Click an element using a CSS selector.",
        parameters = mapOf(
            "selector" to ToolParam("string", "CSS selector of the element to click")
        )
    )

    val FILL_INPUT = WebToolDefinition(
        name = "fill_input",
        description = "Type text into an input field identified by CSS selector.",
        parameters = mapOf(
            "selector" to ToolParam("string", "CSS selector of the input field"),
            "value" to ToolParam("string", "Text to type into the field")
        )
    )

    val READ_PAGE = WebToolDefinition(
        name = "read_page",
        description = "Read and return the current page's visible text content and URL. Use this to understand what is on screen before taking actions.",
        parameters = emptyMap()
    )

    val WAIT_FOR_ELEMENT = WebToolDefinition(
        name = "wait_for_element",
        description = "Wait for an element to appear on the page (useful after navigation or form submission).",
        parameters = mapOf(
            "selector" to ToolParam("string", "CSS selector to wait for"),
            "timeout_ms" to ToolParam("string", "Maximum wait time in milliseconds", required = false)
        )
    )

    val SUBMIT_FORM = WebToolDefinition(
        name = "submit_form",
        description = "Submit the form identified by a CSS selector.",
        parameters = mapOf(
            "selector" to ToolParam("string", "CSS selector of the form to submit (e.g. 'form', '#login-form')")
        )
    )

    val EVALUATE_JS = WebToolDefinition(
        name = "evaluate_js",
        description = "Execute custom JavaScript on the page and return the result. Use sparingly for complex interactions.",
        parameters = mapOf(
            "script" to ToolParam("string", "JavaScript code to evaluate. Must be a single expression or self-invoking function.")
        )
    )

    val ALL = listOf(NAVIGATE, CLICK_BY_TEXT, CLICK_BY_SELECTOR, FILL_INPUT, READ_PAGE, WAIT_FOR_ELEMENT, SUBMIT_FORM, EVALUATE_JS)
}
