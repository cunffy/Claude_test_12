package com.personalai.craig.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.craig.data.preferences.SecurePreferences
import com.personalai.craig.service.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefs: SecurePreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val isSetupComplete: StateFlow<Boolean> = prefs.isSetupComplete
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // true = go to briefing, false = go straight to main (briefing already done)
    private val _navigateNext = MutableSharedFlow<Boolean>()
    val navigateNext = _navigateNext.asSharedFlow()

    private val _error = MutableSharedFlow<String>()
    val error = _error.asSharedFlow()

    fun saveAndContinue(
        claudeKey: String,
        assistantName: String,
        voiceGender: String,
        opticSeoUsername: String,
        opticSeoPassword: String
    ) {
        if (claudeKey.isBlank()) {
            viewModelScope.launch { _error.emit("Claude API key is required") }
            return
        }
        viewModelScope.launch {
            prefs.saveSetupData(
                claudeKey        = claudeKey.trim(),
                assistantName    = assistantName.trim().ifBlank { "Craig" },
                voiceGender      = voiceGender,
                opticSeoUsername = opticSeoUsername.trim(),
                opticSeoPassword = opticSeoPassword
            )
            context.startForegroundService(WakeWordService.startIntent(context))
            val needsBriefing = !prefs.isBriefingComplete.first()
            _navigateNext.emit(needsBriefing)
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
