package com.personalai.craig.ui.conversation

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.lifecycle.lifecycleScope
import com.personalai.craig.service.WakeWordService
import com.personalai.craig.ui.theme.*
import com.personalai.craig.voice.SpeechToTextManager
import com.personalai.craig.voice.TextToSpeechManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    @Inject lateinit var ttsManager: TextToSpeechManager

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
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

                // Auto-activate mic whenever Craig ends a response with a question
                LaunchedEffect(Unit) {
                    viewModel.autoListen.collect {
                        // Wait for TTS to start speaking (up to 1s), then wait for it to finish
                        withTimeoutOrNull(1_000L) { ttsManager.isSpeaking.first { it } }
                        withTimeoutOrNull(15_000L) { ttsManager.isSpeaking.first { !it } }
                        requestMicAndListen()
                    }
                }

                AssistantScreen(
                    assistantName   = assistantName,
                    messages        = messages,
                    uiState         = uiState,
                    sttState        = sttState,
                    onMicPressed    = { requestMicAndListen() },
                    onStopListening = {
                        sttManager.cancel()
                        viewModel.setIdleState()
                    },
                    onStopSpeaking  = { viewModel.stopSpeaking() },
                    onSendText      = { viewModel.processUserInput(it) },
                    onClose         = { finish() }
                )
            }
        }

        val trigger = intent.getStringExtra(EXTRA_TRIGGER)
        if (trigger == TRIGGER_WAKE_WORD) speakGreetingThenListen()
        else if (trigger == TRIGGER_MANUAL) requestMicAndListen()
    }

    private fun speakGreetingThenListen() {
        lifecycleScope.launch {
            ttsManager.speak("What can I do for you today?", flushQueue = true)
            withTimeoutOrNull(1_000L) { ttsManager.isSpeaking.first { it } }
            withTimeoutOrNull(8_000L)  { ttsManager.isSpeaking.first { !it } }
            requestMicAndListen()
        }
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
        sttManager.reset()
        try { startService(WakeWordService.resumeIntent(this)) } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        try { startService(WakeWordService.pauseIntent(this)) } catch (e: Exception) { }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_TRIGGER) == TRIGGER_WAKE_WORD) speakGreetingThenListen()
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

    // Ambient background orbs for depth
    Box(modifier = Modifier.fillMaxSize().background(CraigBackground)) {
        Box(
            modifier = Modifier
                .size(400.dp)
                .offset(x = (-80).dp, y = (-60).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CraigPurpleGlow.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = 80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CraigTeal.copy(alpha = 0.06f), Color.Transparent)
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarDot(size = 36)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    assistantName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = CraigOnSurface
                                )
                                Text(
                                    "Business Assistant",
                                    fontSize = 11.sp,
                                    color = CraigTeal,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close",
                                tint = CraigSubtle,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (messages.isEmpty()) {
                        item { EmptyState(assistantName = assistantName, onSendText = onSendText) }
                    }

                    items(messages.size, key = { it }) { index ->
                        MessageBubble(
                            message = messages[index],
                            modifier = Modifier.animateItem()
                        )
                    }

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

                    if (uiState is ConversationViewModel.UiState.Thinking) {
                        item { ThinkingIndicator(label = uiState.partial) }
                    }
                }

                InputBar(
                    uiState         = uiState,
                    sttState        = sttState,
                    onMicPressed    = onMicPressed,
                    onStopListening = onStopListening,
                    onStopSpeaking  = onStopSpeaking,
                    onSendText      = onSendText
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Avatar composable — reused in top bar and empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AvatarDot(size: Int, animate: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "avatarPulse")
    val glowAlpha by pulse.animateFloat(
        initialValue = if (animate) 0.6f else 0.3f,
        targetValue = if (animate) 1.0f else 0.3f,
        animationSpec = infiniteRepeatable(tween(1600, easing = EaseInOut), RepeatMode.Reverse),
        label = "glow"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp)) {
        // Glow ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            CraigTeal.copy(alpha = if (animate) glowAlpha * 0.4f else 0.15f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        // Inner circle
        Box(
            modifier = Modifier
                .size((size * 0.78f).dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(CraigPurpleDark, CraigSurface2)
                    )
                )
                .border(1.dp, CraigTeal.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "C",
                fontSize = (size * 0.36f).sp,
                fontWeight = FontWeight.ExtraBold,
                color = CraigTeal
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(assistantName: String, onSendText: (String) -> Unit) {
    val pulse = rememberInfiniteTransition(label = "emptyPulse")

    val ring1Scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.7f,
        animationSpec = infiniteRepeatable(tween(2200, easing = EaseOut), RepeatMode.Restart),
        label = "r1s"
    )
    val ring1Alpha by pulse.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = EaseOut), RepeatMode.Restart),
        label = "r1a"
    )
    val ring2Scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.7f,
        animationSpec = infiniteRepeatable(tween(2200, delayMillis = 800, easing = EaseOut), RepeatMode.Restart),
        label = "r2s"
    )
    val ring2Alpha by pulse.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2200, delayMillis = 800, easing = EaseOut), RepeatMode.Restart),
        label = "r2a"
    )

    val suggestions = listOf("Check rankings", "List clients", "Run SEO report", "Who's top ranked?")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Animated avatar
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
            // Outer pulsing ring 2
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(ring2Scale)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                CraigTeal.copy(alpha = ring2Alpha * 0.3f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
            // Outer pulsing ring 1
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(ring1Scale)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                CraigPurpleGlow.copy(alpha = ring1Alpha * 0.3f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
            AvatarDot(size = 96, animate = true)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Hey! I'm $assistantName",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = CraigOnSurface
            )
            Text(
                "Your voice-powered business assistant.\nTap the mic or type to get started.",
                fontSize = 14.sp,
                color = CraigSubtle,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }

        // Suggestion chips — horizontally scrollable, all wired to onSendText
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            items(suggestions) { action ->
                SuggestionChip(
                    onClick = { onSendText(action) },
                    label = {
                        Text(
                            action,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = CraigSurface2,
                        labelColor = CraigTealLight
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = CraigTeal.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(20.dp)
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
    isPartial: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"

    val screenshotBitmap = remember(message.imageBase64) {
        message.imageBase64?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            AvatarDot(size = 30)
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 295.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (screenshotBitmap != null) {
                Image(
                    bitmap = screenshotBitmap.asImageBitmap(),
                    contentDescription = "Screenshot",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, CraigSurface2, RoundedCornerShape(14.dp))
                )
            }

            if (isUser) {
                // Gradient user bubble
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = 18.dp, bottomEnd = 4.dp
                            )
                        )
                        .background(
                            Brush.linearGradient(
                                colors = listOf(CraigPurple, CraigBlue.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = message.content,
                        color = if (isPartial) CraigOnSurface.copy(alpha = 0.55f) else CraigOnSurface,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            } else {
                // Assistant bubble with subtle teal accent border
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 4.dp, topEnd = 18.dp,
                                bottomStart = 18.dp, bottomEnd = 18.dp
                            )
                        )
                        .background(CraigSurface2)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    CraigTeal.copy(alpha = 0.35f),
                                    CraigPurpleGlow.copy(alpha = 0.15f)
                                )
                            ),
                            shape = RoundedCornerShape(
                                topStart = 4.dp, topEnd = 18.dp,
                                bottomStart = 18.dp, bottomEnd = 18.dp
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 11.dp)
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

        if (isUser) Spacer(Modifier.width(4.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Thinking indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThinkingIndicator(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 44.dp, top = 4.dp)
    ) {
        repeat(3) { i ->
            val dotAlpha by rememberInfiniteTransition(label = "dot$i").animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500, delayMillis = i * 160),
                    RepeatMode.Reverse
                ),
                label = "d"
            )
            val dotScale by rememberInfiniteTransition(label = "scale$i").animateFloat(
                initialValue = 0.7f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500, delayMillis = i * 160),
                    RepeatMode.Reverse
                ),
                label = "ds"
            )
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(8.dp)
                    .scale(dotScale)
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

    val isListening = uiState is ConversationViewModel.UiState.Listening ||
            sttState is SpeechToTextManager.SttState.Listening ||
            sttState is SpeechToTextManager.SttState.Partial
    val isSpeaking  = uiState is ConversationViewModel.UiState.Speaking
    val isIdle      = uiState is ConversationViewModel.UiState.Idle

    val statusText: String? = when {
        isListening -> "Listening…"
        isSpeaking  -> (uiState as? ConversationViewModel.UiState.Speaking)?.text?.take(55) ?: "Speaking…"
        uiState is ConversationViewModel.UiState.Thinking -> uiState.partial.ifBlank { "Thinking…" }
        uiState is ConversationViewModel.UiState.Error -> uiState.message
        else -> null
    }

    // Ripple animation for listening state
    val ripple = rememberInfiniteTransition(label = "ripple")
    val r1Scale by ripple.animateFloat(
        initialValue = 1f, targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = EaseOut), RepeatMode.Restart),
        label = "r1s"
    )
    val r1Alpha by ripple.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100, easing = EaseOut), RepeatMode.Restart),
        label = "r1a"
    )
    val r2Scale by ripple.animateFloat(
        initialValue = 1f, targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1100, delayMillis = 380, easing = EaseOut), RepeatMode.Restart),
        label = "r2s"
    )
    val r2Alpha by ripple.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100, delayMillis = 380, easing = EaseOut), RepeatMode.Restart),
        label = "r2a"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, CraigBackground.copy(alpha = 0.95f)),
                    startY = 0f,
                    endY = 60f
                )
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        AnimatedVisibility(
            visible = statusText != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            Text(
                text = statusText ?: "",
                color = when {
                    uiState is ConversationViewModel.UiState.Error -> CraigError
                    isListening -> CraigTeal
                    else -> CraigSubtle
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        Surface(
            shape = RoundedCornerShape(32.dp),
            color = CraigSurface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            if (isListening) CraigTeal.copy(alpha = 0.6f) else CraigSurface2,
                            if (isListening) CraigPurpleGlow.copy(alpha = 0.4f) else CraigSurface2
                        )
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(Modifier.width(6.dp))

                when {
                    textInput.isNotBlank() && isIdle -> {
                        FilledIconButton(
                            onClick = {
                                val t = textInput.trim()
                                if (t.isNotEmpty()) { onSendText(t); textInput = "" }
                            },
                            modifier = Modifier.size(50.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CraigTeal
                            )
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Send",
                                tint = CraigBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    isSpeaking -> {
                        FilledIconButton(
                            onClick = onStopSpeaking,
                            modifier = Modifier.size(50.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CraigError
                            )
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop speaking",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    isListening -> {
                        // Mic button with expanding ripple rings behind it
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(50.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .scale(r1Scale)
                                    .background(
                                        CraigTeal.copy(alpha = r1Alpha * 0.25f),
                                        CircleShape
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .scale(r2Scale)
                                    .background(
                                        CraigTeal.copy(alpha = r2Alpha * 0.2f),
                                        CircleShape
                                    )
                            )
                            FilledIconButton(
                                onClick = onStopListening,
                                modifier = Modifier.size(50.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = CraigTeal
                                )
                            ) {
                                Icon(
                                    Icons.Default.MicOff,
                                    contentDescription = "Stop listening",
                                    tint = CraigBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        FilledIconButton(
                            onClick = onMicPressed,
                            modifier = Modifier.size(50.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CraigPurple
                            )
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Speak",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}
