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

    private val _saveSuccess = MutableSharedFlow<Unit>()
    val saveSuccess = _saveSuccess.asSharedFlow()

    private val _error = MutableSharedFlow<String>()
    val error = _error.asSharedFlow()

    fun saveAndContinue(
        claudeKey: String,
        picovoiceKey: String,
        assistantName: String,
        voiceGender: String,
        opticSeoUsername: String,
        opticSeoPassword: String
    ) {
        if (claudeKey.isBlank()) {
            viewModelScope.launch { _error.emit("Claude API key is required") }
            return
        }
        if (picovoiceKey.isBlank()) {
            viewModelScope.launch { _error.emit("Picovoice access key is required for wake word detection") }
            return
        }
        viewModelScope.launch {
            prefs.saveSetupData(
                claudeKey      = claudeKey.trim(),
                picoKey        = picovoiceKey.trim(),
                assistantName  = assistantName.trim().ifBlank { "Craig" },
                voiceGender    = voiceGender,
                opticSeoUsername = opticSeoUsername.trim(),
                opticSeoPassword = opticSeoPassword
            )
            // Start wake word service
            context.startForegroundService(WakeWordService.startIntent(context))
            _saveSuccess.emit(Unit)
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
