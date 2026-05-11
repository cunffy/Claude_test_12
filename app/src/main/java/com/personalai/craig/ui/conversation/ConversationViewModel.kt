package com.personalai.craig.ui.conversation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.craig.ai.ClaudeClient
import com.personalai.craig.ai.MemoryManager
import com.personalai.craig.ai.SystemPromptBuilder
import com.personalai.craig.data.preferences.SecurePreferences
import com.personalai.craig.data.repository.ConversationRepository
import com.personalai.craig.voice.TextToSpeechManager
import com.personalai.craig.web.OpticSEOController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val claudeClient: ClaudeClient,
    private val memoryManager: MemoryManager,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val conversationRepository: ConversationRepository,
    private val ttsManager: TextToSpeechManager,
    private val opticSeoController: OpticSEOController,
    private val prefs: SecurePreferences
) : ViewModel() {

    companion object {
        private const val TAG = "ConversationViewModel"
        private const val MAX_CONTEXT_MESSAGES = 20
        // Phrases that clearly mean "do something on the OpticSEO website".
        // Deliberately specific — single words like "client", "rank", "website"
        // were matching almost every business question and routing everything
        // through the web path, causing constant "something went wrong" errors.
        private val OPTICSEO_KEYWORDS = listOf(
            // Direct site name
            "opticseo", "optic seo",
            // SEO actions
            "seo check", "seo report", "run a check", "run the check", "run an audit",
            // Keyword tool
            "keyword check", "keyword analysis", "suggest keyword",
            // Mail / signup
            "signup link", "sign up link", "send a link to", "send link to",
            // Chat tab
            "send a chat to", "send chat to",
            // Quotes
            "send a quote", "send quote", "update quote", "update the quote",
            // Client lookup — must pair "client" with an action word
            "manage client", "find the client", "look up the client",
            // Explicit navigation
            "go to the site", "go to the portal", "open the portal", "open the admin",
            "check the dashboard", "check the site"
        )
        // Phrases that clearly mean "show me what's on screen" — not just any "show me"
        private val SCREENSHOT_KEYWORDS = listOf(
            "screenshot",
            "show me the screen", "what's on screen", "what is on screen",
            "capture the screen", "take a photo of the screen"
        )
    }

    sealed class UiState {
        object Idle       : UiState()
        object Listening  : UiState()
        data class Thinking(val partial: String = "") : UiState()
        data class Speaking(val text: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    data class DisplayMessage(
        val role: String,
        val content: String,
        val imageBase64: String? = null
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<DisplayMessage>>(emptyList())
    val messages: StateFlow<List<DisplayMessage>> = _messages.asStateFlow()

    private val _assistantName = prefs.assistantName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Craig")
    val assistantName: StateFlow<String> = _assistantName

    private var currentConversationId: Long? = null
    private val conversationHistory = mutableListOf<ClaudeClient.Message>()

    private val processingMutex = Mutex()

    fun initConversation(conversationId: Long?) {
        viewModelScope.launch {
            if (conversationId != null) {
                currentConversationId = conversationId
                // Load existing messages for display
                val existing = conversationRepository.getMessagesSnapshot(conversationId)
                _messages.value = existing.map { DisplayMessage(it.role, it.content) }
                // Rebuild history for API (last N messages only)
                conversationHistory.clear()
                existing.takeLast(MAX_CONTEXT_MESSAGES).forEach { msg ->
                    if (msg.role != "system") {
                        conversationHistory.add(ClaudeClient.Message(msg.role, msg.content))
                    }
                }
            }
        }
    }

    /**
     * Process a transcribed voice command end-to-end:
     * 1. Store user message
     * 2. Decide if OpticSEO automation is needed
     * 3. Call Claude (with or without web tools)
     * 4. Stream response to TTS
     * 5. Store assistant message and extract facts
     *
     * A mutex ensures only one input is processed at a time; if already locked,
     * the call returns early after setting Idle state.
     */
    fun processUserInput(userText: String) {
        if (userText.isBlank()) {
            _uiState.value = UiState.Idle
            return
        }

        viewModelScope.launch {
            // If another input is already being processed, drop this one.
            if (!processingMutex.tryLock()) {
                _uiState.value = UiState.Idle
                return@launch
            }

            try {
                _uiState.value = UiState.Thinking()

                // Add to display
                val userMsg = DisplayMessage("user", userText)
                _messages.value = _messages.value + userMsg

                // Persist conversation
                val convId = currentConversationId ?: run {
                    val id = conversationRepository.getOrCreateConversation(null)
                    currentConversationId = id
                    id
                }
                conversationRepository.addMessage(convId, "user", userText)

                // Add to in-memory history
                conversationHistory.add(ClaudeClient.Message("user", userText))
                if (conversationHistory.size > MAX_CONTEXT_MESSAGES) {
                    conversationHistory.removeAt(0)
                }

                val responseText: String
                var responseImageBase64: String? = null

                val captureScreenshot = SCREENSHOT_KEYWORDS.any { userText.lowercase().contains(it) }
                val needsWebControl = captureScreenshot || OPTICSEO_KEYWORDS.any {
                    userText.lowercase().contains(it)
                }

                if (needsWebControl) {
                    _uiState.value = UiState.Thinking("Checking your OpticSEO site…")
                    ttsManager.speak("Let me check that on your OpticSEO site.", flushQueue = true)

                    val result: OpticSEOController.CommandResult = opticSeoController.executeCommand(
                        userCommand = userText,
                        conversationHistory = conversationHistory.dropLast(1), // exclude current
                        captureScreenshot = captureScreenshot
                    )
                    responseText = result.text
                    responseImageBase64 = result.screenshotBase64

                    // Speak the final response
                    ttsManager.speak(responseText, flushQueue = false)
                } else {
                    val systemPrompt = systemPromptBuilder.build(includeWebTools = false)

                    // sendMessageStreaming returns the full accumulated response.
                    // onChunk already receives sentence-sized pieces from ClaudeClient;
                    // update UI with the latest chunk only — no extra buffering needed.
                    responseText = claudeClient.sendMessageStreaming(
                        messages = conversationHistory,
                        systemPrompt = systemPrompt
                    ) { chunk ->
                        _uiState.value = UiState.Speaking(chunk)
                        ttsManager.speak(chunk)
                    }
                }

                // Add assistant response to display and persistence
                val assistantMsg = DisplayMessage("assistant", responseText, responseImageBase64)
                _messages.value = _messages.value + assistantMsg
                conversationRepository.addMessage(convId, "assistant", responseText)

                conversationHistory.add(ClaudeClient.Message("assistant", responseText))
                if (conversationHistory.size > MAX_CONTEXT_MESSAGES) {
                    conversationHistory.removeAt(0)
                }

                // Background: extract personal facts from this exchange
                launch(Dispatchers.IO) {
                    memoryManager.extractAndStoreFactsFromExchange(userText, responseText)
                }

                _uiState.value = UiState.Idle

            } catch (e: CancellationException) {
                // Must rethrow — swallowing CancellationException breaks structured concurrency
                // and causes crashes when the activity is swiped away mid-request.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error processing input: ${e.message}", e)
                val errMsg = when {
                    e.message?.contains("API key") == true -> "Claude API key issue. Check Settings."
                    e.message?.contains("network") == true -> "No internet connection."
                    else -> "Something went wrong. Please try again."
                }
                ttsManager.speak(errMsg, flushQueue = true)
                _uiState.value = UiState.Error(errMsg)
            } finally {
                processingMutex.unlock()
            }
        }
    }

    fun stopSpeaking() {
        ttsManager.stop()
        _uiState.value = UiState.Idle
    }

    fun setListeningState() {
        _uiState.value = UiState.Listening
        ttsManager.stop()
    }

    fun setIdleState() {
        _uiState.value = UiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.stop()
    }
}
