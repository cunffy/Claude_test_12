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
        // Keywords that suggest OpticSEO web control is needed
        private val OPTICSEO_KEYWORDS = listOf(
            "opticseo", "optic seo", "seo check", "seo report", "client", "clients",
            "website", "rank", "ranking", "analyze", "analyse", "audit"
        )
    }

    sealed class UiState {
        object Idle       : UiState()
        object Listening  : UiState()
        data class Thinking(val partial: String = "") : UiState()
        data class Speaking(val text: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    data class DisplayMessage(val role: String, val content: String)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<DisplayMessage>>(emptyList())
    val messages: StateFlow<List<DisplayMessage>> = _messages.asStateFlow()

    private val _assistantName = prefs.assistantName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Craig")
    val assistantName: StateFlow<String> = _assistantName

    private var currentConversationId: Long? = null
    private val conversationHistory = mutableListOf<ClaudeClient.Message>()

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
     */
    fun processUserInput(userText: String) {
        if (userText.isBlank()) {
            _uiState.value = UiState.Idle
            return
        }

        viewModelScope.launch {
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
                val needsWebControl = OPTICSEO_KEYWORDS.any {
                    userText.lowercase().contains(it)
                }

                if (needsWebControl) {
                    _uiState.value = UiState.Thinking("Checking your OpticSEO site…")
                    ttsManager.speak("Let me check that on your OpticSEO site.", flushQueue = true)

                    responseText = opticSeoController.executeCommand(
                        userCommand = userText,
                        conversationHistory = conversationHistory.dropLast(1) // exclude current
                    )
                    // Speak the final response
                    ttsManager.speak(responseText, flushQueue = false)
                } else {
                    val systemPrompt = systemPromptBuilder.build(includeWebTools = false)

                    // sendMessageStreaming returns the full accumulated response — use it directly
                    responseText = claudeClient.sendMessageStreaming(
                        messages = conversationHistory,
                        systemPrompt = systemPrompt
                    ) { chunk ->
                        _uiState.value = UiState.Speaking(chunk)
                        ttsManager.speak(chunk)
                    }
                }

                // Add assistant response to display and persistence
                val assistantMsg = DisplayMessage("assistant", responseText)
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

            } catch (e: Exception) {
                Log.e(TAG, "Error processing input: ${e.message}", e)
                val errMsg = when {
                    e.message?.contains("API key") == true -> "Claude API key issue. Check Settings."
                    e.message?.contains("network") == true -> "No internet connection."
                    else -> "Something went wrong. Please try again."
                }
                ttsManager.speak(errMsg, flushQueue = true)
                _uiState.value = UiState.Error(errMsg)
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
