package com.personalai.craig.ai

import android.util.Log
import com.personalai.craig.ai.tools.ToolCall
import com.personalai.craig.ai.tools.ToolResult
import com.personalai.craig.ai.tools.WebToolDefinition
import com.personalai.craig.data.preferences.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Handles all communication with the Anthropic Claude API using OkHttp directly.
 * This avoids the packaging conflicts of the Anthropic Java SDK on Android
 * while providing full API access including tool use and streaming.
 */
@Singleton
class ClaudeClient @Inject constructor(
    private val prefs: SecurePreferences,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ClaudeClient"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        const val MODEL_SONNET = "claude-sonnet-4-6"
        const val MODEL_OPUS   = "claude-opus-4-7"
        const val DEFAULT_MODEL = MODEL_SONNET
        private const val MAX_TOKENS = 2048
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    data class Message(val role: String, val content: String)

    private suspend fun apiKey(): String =
        prefs.claudeApiKey.first() ?: error("Claude API key not configured")

    /** Forces OkHttp's thread pool and connection pool to initialize off the main thread. */
    suspend fun warmUp(): Unit = withContext(Dispatchers.IO) {
        try {
            okHttpClient.connectionPool.connectionCount()
        } catch (_: Exception) { /* pool init is the goal, errors are fine */ }
    }

    /**
     * Send a conversation to Claude and return the full text response.
     */
    suspend fun sendMessage(
        messages: List<Message>,
        systemPrompt: String,
        model: String = DEFAULT_MODEL
    ): String = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, systemPrompt, model)
        val response = executeRequest(body)
        parseTextResponse(response)
    }

    /**
     * Stream a response from Claude, calling [onChunk] for each sentence fragment.
     * Returns the full accumulated response.
     */
    suspend fun sendMessageStreaming(
        messages: List<Message>,
        systemPrompt: String,
        model: String = DEFAULT_MODEL,
        onChunk: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, systemPrompt, model, stream = true)
        val key = apiKey()

        val request = Request.Builder()
            .url(API_URL)
            .post(body.toRequestBody(JSON))
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .build()

        val call = okHttpClient.newCall(request)
        // Cancel the HTTP call when the coroutine is cancelled so the IO thread
        // doesn't stay blocked on readUtf8Line() after the ViewModel is cleared.
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

        val fullResponse = StringBuilder()
        val sentenceBuffer = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "Unknown error"
                    throw RuntimeException("Claude API error ${response.code}: $err")
                }
                val source = response.body?.source() ?: return@withContext ""

                while (!source.exhausted()) {
                    ensureActive() // Throw CancellationException between reads if cancelled
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val json = JSONObject(data)
                        val type = json.optString("type")
                        if (type == "content_block_delta") {
                            val delta = json.optJSONObject("delta")
                            if (delta?.optString("type") == "text_delta") {
                                val text = delta.optString("text")
                                fullResponse.append(text)
                                sentenceBuffer.append(text)

                                // Emit complete sentences for real-time TTS
                                val buf = sentenceBuffer.toString()
                                val sentenceEnd = buf.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                                if (sentenceEnd >= 0) {
                                    val sentence = buf.substring(0, sentenceEnd + 1).trim()
                                    if (sentence.isNotEmpty()) {
                                        withContext(Dispatchers.Main) { onChunk(sentence) }
                                    }
                                    sentenceBuffer.delete(0, sentenceEnd + 1)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE event: $data")
                    }
                }

                // Flush remaining buffer
                val remaining = sentenceBuffer.toString().trim()
                if (remaining.isNotEmpty()) {
                    withContext(Dispatchers.Main) { onChunk(remaining) }
                }
            }
        } catch (e: IOException) {
            // call.cancel() causes OkHttp to throw IOException("Canceled").
            // Re-check whether the coroutine is still active — if not, this is
            // expected cancellation, not a real network error.
            ensureActive()
            throw e
        } finally {
            cancellationHandle?.dispose()
        }

        fullResponse.toString()
    }

    /**
     * Agentic tool-use loop for OpticSEO control.
     * Claude decides which web tools to call; we execute them and feed results back.
     * Returns the final text response once Claude stops calling tools.
     */
    suspend fun sendWithTools(
        messages: List<Message>,
        systemPrompt: String,
        tools: List<WebToolDefinition>,
        toolExecutor: suspend (ToolCall) -> ToolResult,
        model: String = DEFAULT_MODEL
    ): String = withContext(Dispatchers.IO) {
        val mutableMessages = messages.toMutableList()

        repeat(25) { // max 25 tool-use rounds — complex OpticSEO tasks can need 12-18 steps
            val bodyJson = buildToolRequestBody(mutableMessages, systemPrompt, model, tools)
            val responseText = executeRequest(bodyJson)
            val responseJson = JSONObject(responseText)

            val stopReason = responseJson.optString("stop_reason")
            val contentArray = responseJson.optJSONArray("content") ?: JSONArray()

            if (stopReason == "end_turn" || stopReason == "stop_sequence") {
                return@withContext parseTextFromContentArray(contentArray)
            }

            if (stopReason == "tool_use") {
                // Add assistant message with tool use blocks
                mutableMessages.add(Message("assistant", responseText))

                // Collect all tool calls and execute them
                val toolResults = JSONArray()
                for (i in 0 until contentArray.length()) {
                    val block = contentArray.getJSONObject(i)
                    if (block.optString("type") == "tool_use") {
                        val toolCall = ToolCall(
                            id = block.getString("id"),
                            name = block.getString("name"),
                            input = block.optJSONObject("input")?.let { inputJson ->
                                buildMap {
                                    inputJson.keys().forEach { key ->
                                        put(key, inputJson.optString(key))
                                    }
                                }
                            } ?: emptyMap()
                        )
                        val result = toolExecutor(toolCall)
                        toolResults.put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", result.toolUseId)
                            put("content", result.content)
                            if (result.isError) put("is_error", true)
                        })
                    }
                }

                // Add tool results as user message
                mutableMessages.add(Message("tool_results", toolResults.toString()))
            } else {
                return@withContext parseTextFromContentArray(contentArray)
            }
        }

        "I ran into an issue completing that task. Please try again."
    }

    /**
     * Lightweight call to extract personal facts from a conversation exchange.
     */
    suspend fun extractFacts(conversationText: String): String = withContext(Dispatchers.IO) {
        val body = buildRequestBody(
            messages = listOf(Message("user", conversationText)),
            systemPrompt = """
                You are a memory extraction assistant. Given a conversation between a user and their AI assistant (Craig), extract important facts worth remembering for future conversations.

                Capture facts about:
                1. THE USER: name, age, location, occupation, family, personality, communication style, preferences, habits
                2. THEIR BUSINESS: business name, industry, services offered, business goals, recurring challenges
                3. THEIR WEBSITE (OpticSEO): client names encountered, SEO tasks performed, site issues found, any URLs or login details mentioned
                4. PREFERENCES & PATTERNS: how they like to be helped, topics they care about, recurring tasks, things to avoid

                Return ONLY a valid JSON array. Each object must have:
                - "key": descriptive snake_case identifier (e.g. "user_name", "client_pine_pienaar_status", "prefers_brief_answers", "opticseo_login_url")
                - "value": the specific fact to remember — be precise and useful
                - "source": "user_stated" or "inferred"

                Return [] if there is genuinely nothing worth remembering. Output ONLY the JSON array, no other text.
            """.trimIndent(),
            model = MODEL_SONNET,
            maxTokens = 768
        )
        val response = executeRequest(body)
        parseTextResponse(response)
    }

    /**
     * Summarize a conversation transcript into 2-3 sentences.
     */
    suspend fun summarizeConversation(transcript: String): String = withContext(Dispatchers.IO) {
        val body = buildRequestBody(
            messages = listOf(
                Message("user", "Summarize this conversation in 2-3 sentences, focusing on topics discussed and user preferences:\n\n$transcript")
            ),
            systemPrompt = "You are a concise summarization assistant. Be brief.",
            model = MODEL_SONNET,
            maxTokens = 256
        )
        val response = executeRequest(body)
        parseTextResponse(response)
    }

    private suspend fun executeRequest(bodyJson: String): String {
        val key = apiKey()
        val request = Request.Builder()
            .url(API_URL)
            .post(bodyJson.toRequestBody(JSON))
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw RuntimeException("Claude API error ${response.code}: $body")
            }
            body
        }
    }

    private fun buildRequestBody(
        messages: List<Message>,
        systemPrompt: String,
        model: String,
        maxTokens: Int = MAX_TOKENS,
        stream: Boolean = false
    ): String {
        val messagesArray = JSONArray()
        for (msg in messages) {
            if (msg.role == "tool_results") continue // handled separately in tool loop
            messagesArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
        return JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", messagesArray)
            if (stream) put("stream", true)
        }.toString()
    }

    private fun buildToolRequestBody(
        messages: List<Message>,
        systemPrompt: String,
        model: String,
        tools: List<WebToolDefinition>
    ): String {
        val messagesArray = JSONArray()
        for (msg in messages) {
            when (msg.role) {
                "tool_results" -> {
                    messagesArray.put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray(msg.content))
                    })
                }
                "assistant" -> {
                    // assistant messages during tool use contain raw JSON content array
                    val contentFromApi = try {
                        JSONObject(msg.content).optJSONArray("content")
                    } catch (e: Exception) { null }
                    messagesArray.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", contentFromApi ?: msg.content)
                    })
                }
                else -> {
                    messagesArray.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            }
        }

        val toolsArray = JSONArray()
        for (tool in tools) {
            val properties = JSONObject()
            val required = JSONArray()
            for ((paramName, param) in tool.parameters) {
                properties.put(paramName, JSONObject().apply {
                    put("type", param.type)
                    put("description", param.description)
                })
                if (param.required) required.put(paramName)
            }
            toolsArray.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", required)
                })
            })
        }

        return JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", systemPrompt)
            put("messages", messagesArray)
            put("tools", toolsArray)
        }.toString()
    }

    private fun parseTextResponse(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val content = json.optJSONArray("content") ?: return ""
            parseTextFromContentArray(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $responseBody", e)
            ""
        }
    }

    private fun parseTextFromContentArray(contentArray: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until contentArray.length()) {
            val block = contentArray.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                sb.append(block.optString("text"))
            }
        }
        return sb.toString()
    }
}
