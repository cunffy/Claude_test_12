package com.personalai.craig.ui.setup

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.personalai.craig.data.preferences.SecurePreferences
import com.personalai.craig.ui.main.MainActivity
import com.personalai.craig.ui.onboarding.BusinessBriefingActivity
import com.personalai.craig.ui.theme.CraigTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SetupActivity : ComponentActivity() {

    private val viewModel: SetupViewModel by viewModels()
    @Inject lateinit var prefs: SecurePreferences

    private val assistantRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* proceed regardless of result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            if (prefs.isSetupComplete.first()) {
                // Setup done — skip to briefing or main
                if (prefs.isBriefingComplete.first()) navigateToMain()
                else navigateToBriefing()
                return@launch
            }

            // Stay on setup screen; watch for save completion
            viewModel.navigateNext.collect { needsBriefing ->
                requestAssistantRole()
                if (needsBriefing) navigateToBriefing() else navigateToMain()
            }
        }

        setContent {
            CraigTheme {
                val snackbarState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    viewModel.error.collect { msg -> snackbarState.showSnackbar(msg) }
                }
                SetupScreen(viewModel = viewModel, snackbarState = snackbarState)
            }
        }
    }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            assistantRoleLauncher.launch(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            )
        }
    }

    private fun navigateToBriefing() {
        startActivity(Intent(this, BusinessBriefingActivity::class.java))
        finish()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
private fun SetupScreen(viewModel: SetupViewModel, snackbarState: SnackbarHostState) {
    var claudeKey     by remember { mutableStateOf("") }
    var assistantName by remember { mutableStateOf("Craig") }
    var voiceGender   by remember { mutableStateOf("male") }
    var opticSeoUser  by remember { mutableStateOf("") }
    var opticSeoPass  by remember { mutableStateOf("") }
    var showClaudeKey by remember { mutableStateOf(false) }
    var showSeoPass   by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(snackbarHost = { SnackbarHost(snackbarState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Welcome to Craig", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Your personal business assistant. Let's get you set up.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

            Spacer(Modifier.height(8.dp))

            SectionHeader("Assistant Name")
            OutlinedTextField(
                value = assistantName,
                onValueChange = { assistantName = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Voice:  ", color = MaterialTheme.colorScheme.onSurface)
                listOf("male", "female").forEach { gender ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        RadioButton(selected = voiceGender == gender, onClick = { voiceGender = gender })
                        Text(gender.replaceFirstChar { it.uppercase() },
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionHeader("Claude API Key")
            Text("Get yours free at platform.anthropic.com",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            OutlinedTextField(
                value = claudeKey,
                onValueChange = { claudeKey = it },
                label = { Text("Claude API key") },
                visualTransformation = if (showClaudeKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showClaudeKey = !showClaudeKey }) {
                        Text(if (showClaudeKey) "Hide" else "Show", fontSize = 12.sp)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            SectionHeader("OpticSEO Login")
            Text("Craig will use these to log in and control your OpticSEO website.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            OutlinedTextField(
                value = opticSeoUser,
                onValueChange = { opticSeoUser = it },
                label = { Text("Email / username") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = opticSeoPass,
                onValueChange = { opticSeoPass = it },
                label = { Text("Password") },
                visualTransformation = if (showSeoPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showSeoPass = !showSeoPass }) {
                        Text(if (showSeoPass) "Hide" else "Show", fontSize = 12.sp)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.saveAndContinue(claudeKey, assistantName, voiceGender, opticSeoUser, opticSeoPass)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Continue", fontSize = 16.sp)
            }

            OutlinedButton(
                onClick = { viewModel.openBatteryOptimizationSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disable battery optimization (recommended)")
            }

            OutlinedButton(
                onClick = { viewModel.openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Accessibility (screen reading)")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}
