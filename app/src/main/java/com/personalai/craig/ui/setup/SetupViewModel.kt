package com.personalai.craig.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.craig.data.preferences.SecurePreferences
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

    // replay=1 prevents the event from being lost if the Activity collects slightly late
    private val _navigateNext = MutableSharedFlow<Boolean>(replay = 1)
    val navigateNext = _navigateNext.asSharedFlow()

    private val _saveOnlyDone = MutableSharedFlow<Unit>(replay = 1)
    val saveOnlyDone = _saveOnlyDone.asSharedFlow()

    private val _error = MutableSharedFlow<String>()
    val error = _error.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Expose current values so the settings screen can pre-populate
    val savedAssistantName: Flow<String> = prefs.assistantName
    val savedVoiceGender: Flow<String>   = prefs.voiceGender

    /** Called on first-time setup. Saves credentials and navigates forward. */
    fun saveAndContinue(
        claudeKey: String,
        assistantName: String,
        voiceGender: String,
        opticSeoUsername: String,
        opticSeoPassword: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                prefs.saveSetupData(
                    claudeKey        = claudeKey.trim(), // blank → SecurePreferences uses built-in key
                    assistantName    = assistantName.trim().ifBlank { "Craig" },
                    voiceGender      = voiceGender,
                    opticSeoUsername = opticSeoUsername.trim(),
                    opticSeoPassword = opticSeoPassword
                )
                _navigateNext.emit(false) // briefing is hardcoded, never show the briefing screen
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Called from Settings screen — saves without navigating away.
     *  Blank credential fields keep the existing stored value. */
    fun saveOnly(
        claudeKey: String,
        assistantName: String,
        voiceGender: String,
        opticSeoUsername: String,
        opticSeoPassword: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                prefs.saveSetupData(
                    claudeKey        = claudeKey.trim().ifBlank { prefs.claudeApiKey.first() ?: "" },
                    assistantName    = assistantName.trim().ifBlank { "Craig" },
                    voiceGender      = voiceGender,
                    opticSeoUsername = opticSeoUsername.trim().ifBlank { prefs.opticSeoUsername.first() ?: "" },
                    opticSeoPassword = opticSeoPassword.ifBlank { prefs.opticSeoPassword.first() ?: "" }
                )
                _saveOnlyDone.emit(Unit)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
