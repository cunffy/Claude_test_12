package com.personalai.craig.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.graphics.BitmapFactory
import android.util.Base64
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
        if (granted) {
            // Permission just granted for the first time — start the wake word service now.
            try { startForegroundService(WakeWordService.startIntent(this)) }
            catch (e: Exception) { /* ignore */ }
            startListening()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L).takeIf { it != -1L }
        viewModel.initConversation(conversationId)

        setContent {
            CraigTheme {
                val uiState       by viewModel.uiState.collectAsStateWithLifecycle()
                val messages      by viewModel.messages.collectAsStateWithLifecycle()
                val assistantName by viewModel.assistantName.collectAsStateWithLifecycle()
                val sttState      by sttManager.state.collectAsStateWithLifecycle()

                LaunchedEffect(sttState) {
                    when (val s = sttState) {
                        is SpeechToTextManager.SttState.Result -> viewModel.processUserInput(s.text)
                        is SpeechToTextManager.SttState.Error -> {
                            if (s.code != android.speech.SpeechRecognizer.ERROR_NO_MATCH)
                                viewModel.setIdleState()
                        }
                        else -> {}
                    }
                }

                AssistantScreen(
                    assistantName  = assistantName,
                    messages       = messages,
                    uiState        = uiState,
                    sttState       = sttState,
                    onMicPressed   = { requestMicAndListen() },
                    onStopListening = {
                        sttManager.cancel()
                        viewModel.setIdleState()
                    },
                    onStopSpeaking = { viewModel.stopSpeaking() },
                    onSendText     = { viewModel.processUserInput(it) },
                    onClose        = { finish() }
                )
            }
        }

        // Only auto-start listening for wake-word activations, not manual opens.
        val trigger = intent.getStringExtra(EXTRA_TRIGGER)
        if (trigger == TRIGGER_WAKE_WORD) requestMicAndListen()
    }

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startListening()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        viewModel.setListeningState()
        sttManager.startListening()
    }

    override fun onPause() {
        super.onPause()
        // reset() clears any pending mainHandler retries so they don't fire
        // after the activity is gone and start an unexpected listening session.
        sttManager.reset()
        try { startService(WakeWordService.resumeIntent(this)) } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        startService(WakeWordService.pauseIntent(this))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScreen(
    assistantName: String,
    messages: List<ConversationViewModel.DisplayMessage>,
    uiState: ConversationViewModel.UiState,
    sttState: SpeechToTextManager.SttState,
    onMicPressed: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
    onSendText: (String) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = CraigBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(assistantName, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "Business Assistant",
                            fontSize = 12.sp,
                            color = CraigTeal,
                            fontWeight = FontWeight.Normal
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close",
                            tint = CraigSubtle)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CraigBackground,
                    titleContentColor = CraigOnSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item { EmptyState(assistantName = assistantName) }
                }

                items(messages) { msg -> MessageBubble(message = msg) }

                // Live partial transcript
                if (sttState is SpeechToTextManager.SttState.Partial) {
                    item {
                        MessageBubble(
                            message = ConversationViewModel.DisplayMessage("user", sttState.text + "…"),
                            isPartial = true
                        )
                    }
                }

                // Thinking indicator
                if (uiState is ConversationViewModel.UiState.Thinking) {
                    item { ThinkingIndicator(label = uiState.partial) }
                }
            }

            // Input bar
            InputBar(
                uiState        = uiState,
                sttState       = sttState,
                onMicPressed   = onMicPressed,
                onStopListening = onStopListening,
                onStopSpeaking = onStopSpeaking,
                onSendText     = onSendText
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(assistantName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Avatar glow
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(CraigTeal.copy(alpha = 0.25f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CraigSurface2),
                contentAlignment = Alignment.Center
            ) {
                Text("C", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = CraigTeal)
            }
        }

        Text(
            "Hi, I'm $assistantName",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = CraigOnSurface
        )
        Text(
            "Your business assistant. Tap the mic or type to get started.",
            fontSize = 14.sp,
            color = CraigSubtle,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
            lineHeight = 21.sp
        )

        // Quick action chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            listOf("Check rankings", "List clients", "Run SEO report").forEach { action ->
                SuggestionChip(
                    onClick = {},
                    label = { Text(action, fontSize = 12.sp) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = CraigSurface2,
                        labelColor = CraigTealLight
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Message bubbles
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: ConversationViewModel.DisplayMessage,
    isPartial: Boolean = false
) {
    val isUser = message.role == "user"

    // Decode screenshot once; null if absent or malformed
    val screenshotBitmap = remember(message.imageBase64) {
        message.imageBase64?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(CraigSurface2),
                contentAlignment = Alignment.Center
            ) {
                Text("C", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CraigTeal)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 290.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Screenshot image (shown above the text bubble when present)
            if (screenshotBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = screenshotBitmap.asImageBitmap(),
                    contentDescription = "Screenshot",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                )
            }

            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp, topEnd = 18.dp,
                            bottomStart = if (isUser) 18.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 18.dp
                        )
                    )
                    .background(if (isUser) UserBubble else CraigSurface2)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.content,
                    color = if (isPartial) CraigSubtle else CraigOnSurface,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Thinking indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThinkingIndicator(label: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 36.dp)
    ) {
        repeat(3) { i ->
            val dotAlpha by rememberInfiniteTransition(label = "dot$i").animateFloat(
                initialValue = 0.2f, targetValue = 0.9f,
                animationSpec = infiniteRepeatable(
                    tween(400, delayMillis = i * 130),
                    RepeatMode.Reverse
                ),
                label = "d"
            )
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(CraigTeal.copy(alpha = dotAlpha))
            )
        }
        if (label.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = CraigSubtle, fontSize = 13.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Input bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    uiState: ConversationViewModel.UiState,
    sttState: SpeechToTextManager.SttState,
    onMicPressed: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
    onSendText: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    val pulsing = rememberInfiniteTransition(label = "pulse")
    val scale by pulsing.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "scale"
    )

    val isListening = uiState is ConversationViewModel.UiState.Listening ||
            sttState is SpeechToTextManager.SttState.Listening ||
            sttState is SpeechToTextManager.SttState.Partial
    val isSpeaking = uiState is ConversationViewModel.UiState.Speaking
    val isIdle = uiState is ConversationViewModel.UiState.Idle

    // Status strip
    val statusText: String? = when {
        isListening -> "Listening…"
        isSpeaking  -> (uiState as? ConversationViewModel.UiState.Speaking)?.text?.take(55) ?: "Speaking…"
        uiState is ConversationViewModel.UiState.Thinking -> uiState.partial.ifBlank { "Thinking…" }
        uiState is ConversationViewModel.UiState.Error -> uiState.message
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, CraigBackground),
                    startY = 0f,
                    endY = 40f
                )
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        AnimatedVisibility(visible = statusText != null) {
            Text(
                text = statusText ?: "",
                color = if (uiState is ConversationViewModel.UiState.Error) CraigError else CraigSubtle,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            )
        }

        // Input row
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = CraigSurface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = {
                        Text(
                            if (isListening) "Listening…" else "Ask anything…",
                            fontSize = 15.sp,
                            color = CraigSubtle
                        )
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    enabled = isIdle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val t = textInput.trim()
                            if (t.isNotEmpty() && isIdle) { onSendText(t); textInput = "" }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedTextColor = CraigOnSurface,
                        unfocusedTextColor = CraigOnSurface,
                        disabledTextColor = CraigSubtle,
                        cursorColor = CraigTeal
                    ),
                    shape = RoundedCornerShape(20.dp)
                )

                Spacer(Modifier.width(4.dp))

                // Action button
                when {
                    textInput.isNotBlank() && isIdle -> {
                        FilledIconButton(
                            onClick = {
                                val t = textInput.trim()
                                if (t.isNotEmpty()) { onSendText(t); textInput = "" }
                            },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CraigTeal)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send",
                                tint = CraigBackground, modifier = Modifier.size(22.dp))
                        }
                    }
                    isSpeaking -> {
                        FilledIconButton(
                            onClick = onStopSpeaking,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CraigError)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop",
                                modifier = Modifier.size(22.dp))
                        }
                    }
                    isListening -> {
                        FilledIconButton(
                            onClick = onStopListening,
                            modifier = Modifier.size(48.dp).scale(scale),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CraigTeal)
                        ) {
                            Icon(Icons.Default.MicOff, contentDescription = "Stop listening",
                                tint = CraigBackground, modifier = Modifier.size(22.dp))
                        }
                    }
                    else -> {
                        FilledIconButton(
                            onClick = onMicPressed,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CraigBlue)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Speak",
                                modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}
