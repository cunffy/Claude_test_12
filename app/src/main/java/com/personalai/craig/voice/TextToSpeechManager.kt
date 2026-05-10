package com.personalai.craig.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeechManager"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingQueue = mutableListOf<String>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        tts = TextToSpeech(context, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
            override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _isSpeaking.value = false }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
            } else {
                isInitialized = true
                // Slightly slower + lower pitch = more natural, confident tone
                tts?.setSpeechRate(0.92f)
                tts?.setPitch(0.88f)
                trySelectPremiumVoice()
                pendingQueue.forEach { speak(it) }
                pendingQueue.clear()
            }
        } else {
            Log.e(TAG, "TTS init failed with status: $status")
        }
    }

    private fun trySelectPremiumVoice() {
        val voices = tts?.voices ?: return
        // Prefer the highest-quality offline en-US voice available
        val best = voices
            .filter { v -> v.locale.language == "en" && v.locale.country == "US" }
            .minByOrNull { v ->
                // Lower quality number = better; prefer offline to avoid network latency
                v.quality * 10 - (if (!v.isNetworkConnectionRequired) 1000 else 0)
            }
        if (best != null) {
            tts?.voice = best
            Log.i(TAG, "TTS voice: ${best.name} quality=${best.quality}")
        }
    }

    /**
     * Speak text. If [flushQueue] is true, stops current speech immediately.
     */
    fun speak(text: String, flushQueue: Boolean = false) {
        if (text.isBlank()) return
        if (!isInitialized) {
            pendingQueue.add(text)
            return
        }
        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "u_${System.currentTimeMillis()}"
        tts?.speak(text, queueMode, null, utteranceId)
    }

    fun setVoiceGender(gender: String) {
        if (!isInitialized) return
        val pitch = if (gender.lowercase() == "female") 1.05f else 0.88f
        tts?.setPitch(pitch)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
