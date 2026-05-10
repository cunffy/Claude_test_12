package com.personalai.craig.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalai.craig.service.WakeWordService
import com.personalai.craig.ui.theme.*
import com.personalai.craig.voice.SpeechToTextManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_TRIGGER         = "trigger"
        const val TRIGGER_WAKE_WORD     = "wake_word"
        const val TRIGGER_MANUAL        = "manual"
    }

    private val viewModel: ConversationViewModel by viewModels()

    @Inject lateinit var sttManager: SpeechToTextManager

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
            .takeIf { it != -1L }
        viewModel.initConversation(conversationId)

        setContent {
            CraigTheme {
                val uiState        by viewModel.uiState.collectAsStateWithLifecycle()
                val messages       by viewModel.messages.collectAsStateWithLifecycle()
                val assistantName  by viewModel.assistantName.collectAsStateWithLifecycle()
                val sttState       by sttManager.state.collectAsStateWithLifecycle()

                // When STT produces a result, process it
                LaunchedEffect(sttState) {
                    when (val s = sttState) {
                        is SpeechToTextManager.SttState.Result -> {
                            viewModel.processUserInput(s.text)
                        }
                        is SpeechToTextManager.SttState.Error -> {
                            if (s.code != android.speech.SpeechRecognizer.ERROR_NO_MATCH) {
                                viewModel.setIdleState()
                            }
                        }
                        else -> {}
                    }
                }

                AssistantScreen(
                    assistantName = assistantName,
                    messages = messages,
                    uiState = uiState,
                    sttState = sttState,
                    onMicPressed = { requestMicAndListen() },
                    onStopSpeaking = { viewModel.stopSpeaking() },
                    onSendText = { viewModel.processUserInput(it) },
                    onClose = { finish() }
                )
            }
        }

        // Auto-start listening when triggered by wake word or manual tap
        val trigger = intent.getStringExtra(EXTRA_TRIGGER)
        if (trigger == TRIGGER_WAKE_WORD || trigger == TRIGGER_MANUAL) {
            requestMicAndListen()
        }
    }

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        viewModel.setListeningState()
        sttManager.startListening()
    }

    override fun onPause() {
        super.onPause()
        sttManager.cancel()
        // Resume wake word detection
        startService(WakeWordService.resumeIntent(this))
    }

    override fun onResume() {
        super.onResume()
        // Pause wake word detection while we're actively in the activity
        startService(WakeWordService.pauseIntent(this))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScreen(
    assistantName: String,
    messages: List<ConversationViewModel.DisplayMessage>,
    uiState: ConversationViewModel.UiState,
    sttState: SpeechToTextManager.SttState,
    onMicPressed: () -> Unit,
    onStopSpeaking: () -> Unit,
    onSendText: (String) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(assistantName) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CraigBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CraigBackground)
                .padding(padding)
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { msg ->
                    MessageBubble(message = msg)
                }

                // Live partial transcript
                if (sttState is SpeechToTextManager.SttState.Partial) {
                    item {
                        MessageBubble(
                            message = ConversationViewModel.DisplayMessage(
                                "user", sttState.text + "…"
                            ),
                            isPartial = true
                        )
                    }
                }

                // Thinking indicator
                if (uiState is ConversationViewModel.UiState.Thinking) {
                    item {
                        ThinkingIndicator(label = uiState.partial)
                    }
                }
            }

            // Text input + mic / stop controls
            StatusAndControls(
                uiState = uiState,
                sttState = sttState,
                onMicPressed = onMicPressed,
                onStopSpeaking = onStopSpeaking,
                onSendText = onSendText
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ConversationViewModel.DisplayMessage,
    isPartial: Boolean = false
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(if (isUser) UserBubble else AssistantBubble)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                color = if (isPartial) CraigOnSurface.copy(alpha = 0.5f) else CraigOnSurface,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun ThinkingIndicator(label: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(CraigAccent.copy(alpha = alpha))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label.ifBlank { "Craig is thinking…" },
            color = CraigOnSurface.copy(alpha = 0.6f),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun StatusAndControls(
    uiState: ConversationViewModel.UiState,
    sttState: SpeechToTextManager.SttState,
    onMicPressed: () -> Unit,
    onStopSpeaking: () -> Unit,
    onSendText: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    val pulsing = rememberInfiniteTransition(label = "pulse")
    val scale by pulsing.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "scale"
    )

    val isListening = uiState is ConversationViewModel.UiState.Listening ||
            sttState is SpeechToTextManager.SttState.Listening ||
            sttState is SpeechToTextManager.SttState.Partial
    val isSpeaking = uiState is ConversationViewModel.UiState.Speaking
    val isIdle = uiState is ConversationViewModel.UiState.Idle

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Status label — only shown when something is happening
        val statusText = when {
            isListening -> "Listening…"
            isSpeaking  -> (uiState as? ConversationViewModel.UiState.Speaking)?.text?.take(60) ?: "Speaking…"
            uiState is ConversationViewModel.UiState.Thinking -> uiState.partial.ifBlank { "Thinking…" }
            uiState is ConversationViewModel.UiState.Error -> uiState.message
            else -> null
        }
        if (statusText != null) {
            Text(
                statusText,
                color = CraigOnSurface.copy(alpha = 0.7f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        // Input row: text field + action button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = {
                    Text(
                        if (isListening) "Listening…" else "Type a message…",
                        fontSize = 14.sp,
                        color = CraigOnSurface.copy(alpha = 0.4f)
                    )
                },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = isIdle,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val t = textInput.trim()
                        if (t.isNotEmpty() && isIdle) {
                            onSendText(t)
                            textInput = ""
                        }
                    }
                )
            )

            Spacer(Modifier.width(8.dp))

            // Action button: Send (when typing) → Stop (when speaking) → Mic (otherwise)
            when {
                textInput.isNotBlank() && isIdle -> {
                    FilledIconButton(
                        onClick = {
                            val t = textInput.trim()
                            if (t.isNotEmpty()) { onSendText(t); textInput = "" }
                        },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CraigBlue
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send",
                            modifier = Modifier.size(26.dp))
                    }
                }
                isSpeaking -> {
                    FilledIconButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CraigError
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop speaking",
                            modifier = Modifier.size(26.dp))
                    }
                }
                isListening -> {
                    FilledIconButton(
                        onClick = { },
                        modifier = Modifier.size(56.dp).scale(scale),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CraigAccent
                        )
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Listening",
                            tint = Color.Black, modifier = Modifier.size(26.dp))
                    }
                }
                else -> {
                    FilledIconButton(
                        onClick = onMicPressed,
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CraigBlue
                        )
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Speak",
                            modifier = Modifier.size(26.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
