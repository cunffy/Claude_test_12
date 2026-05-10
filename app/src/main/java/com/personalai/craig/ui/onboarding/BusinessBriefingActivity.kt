package com.personalai.craig.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.personalai.craig.ui.main.MainActivity
import com.personalai.craig.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BusinessBriefingActivity : ComponentActivity() {

    private val viewModel: BusinessBriefingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            viewModel.done.collect {
                startActivity(Intent(this@BusinessBriefingActivity, MainActivity::class.java))
                finish()
            }
        }

        setContent {
            com.personalai.craig.ui.theme.CraigTheme {
                val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
                BusinessBriefingScreen(
                    isLoading = isLoading,
                    onSave = { viewModel.saveBriefing(it) },
                    onSkip = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun BusinessBriefingScreen(
    isLoading: Boolean,
    onSave: (String) -> Unit,
    onSkip: () -> Unit
) {
    var briefing by remember { mutableStateOf("") }

    Scaffold(
        containerColor = CraigBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(CraigTeal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Business,
                    contentDescription = null,
                    tint = CraigTeal,
                    modifier = Modifier.size(40.dp)
                )
            }

            Text(
                "Tell me about your business",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = CraigOnSurface,
                textAlign = TextAlign.Center
            )

            Text(
                "The more detail you give, the better I can help you grow. I'll remember everything you tell me.",
                fontSize = 15.sp,
                color = CraigSubtle,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            // Hint chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf("Services", "Clients", "Goals", "Tasks").forEach { hint ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(hint, fontSize = 12.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = CraigSurface2,
                            labelColor = CraigTealLight
                        )
                    )
                }
            }

            // Main text area
            OutlinedTextField(
                value = briefing,
                onValueChange = { briefing = it },
                placeholder = {
                    Text(
                        "For example:\n\n" +
                        "\"I run an SEO agency called OpticSEO. We have around 50 clients, mostly small businesses in South Africa. " +
                        "My main tasks are running monthly SEO reports, checking rankings, onboarding new clients, " +
                        "and managing their Google Business profiles. I want you to be able to run reports, " +
                        "find clients quickly, and help me spot issues before clients do.\"",
                        color = CraigSubtle,
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                minLines = 8,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CraigTeal,
                    unfocusedBorderColor = CraigSurface2,
                    focusedTextColor = CraigOnSurface,
                    unfocusedTextColor = CraigOnSurface
                )
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { onSave(briefing) },
                enabled = briefing.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CraigTeal)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = CraigBackground,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Let's go", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = CraigBackground)
                }
            }

            TextButton(onClick = onSkip) {
                Text("Skip for now", color = CraigSubtle, fontSize = 14.sp)
            }
        }
    }
}
