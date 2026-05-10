package com.personalai.craig.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.craig.ai.MemoryManager
import com.personalai.craig.data.preferences.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BusinessBriefingViewModel @Inject constructor(
    private val memoryManager: MemoryManager,
    private val prefs: SecurePreferences
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _done = MutableSharedFlow<Unit>()
    val done = _done.asSharedFlow()

    fun saveBriefing(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                memoryManager.storeBriefing(text)
                prefs.setBriefingComplete()
                _done.emit(Unit)
            } catch (e: Exception) {
                Log.e("BusinessBriefingVM", "Failed to save briefing: ${e.message}", e)
                // Still navigate forward — briefing storage is best-effort
                prefs.setBriefingComplete()
                _done.emit(Unit)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
