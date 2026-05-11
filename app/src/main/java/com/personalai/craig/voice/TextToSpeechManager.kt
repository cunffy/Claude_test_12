package com.personalai.craig.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.personalai.craig.data.preferences.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SecurePreferences
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeechManager"
        // Substrings found in Google's bundled en-US TTS voice names that indicate gender.
        private val FEMALE_VOICE_HINTS = listOf("iob", "sfg", "sfs", "tpf", "female", "woman")
        private val MALE_VOICE_HINTS   = listOf("iol", "iom", "tpc", "tpd", "tpm", "male", "man")
    }

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    private val pendingQueue = mutableListOf<String>() // accessed only on main thread via onInit
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        // Re-apply gender whenever the user saves a new preference in Settings,
        // without requiring an app restart.
        scope.launch {
            prefs.voiceGender.drop(1).collect { gender ->
                if (isInitialized) applyGender(gender)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
                pendingQueue.clear()
            } else {
                isInitialized = true
                tts?.setSpeechRate(0.92f)
                // Read the saved gender preference and apply it immediately.
                // runBlocking is fine here — onInit() is called on the TTS engine's thread,
                // not the main thread, so there is no risk of deadlock or ANR.
                val gender = runBlocking { prefs.voiceGender.first() }
                applyGender(gender)
                pendingQueue.forEach { speak(it) }
                pendingQueue.clear()
            }
        } else {
            Log.e(TAG, "TTS init failed with status: $status")
            pendingQueue.clear()
        }
    }

    /** Apply pitch and select a gender-matching voice. */
    private fun applyGender(gender: String) {
        val preferFemale = gender.lowercase() == "female"
        tts?.setPitch(if (preferFemale) 1.05f else 0.88f)
        trySelectPremiumVoice(preferFemale)
    }

    /**
     * Pick the best en-US voice, preferring one that matches the desired gender.
     *
     * Google's TTS voice names embed gender hints (e.g. "iol"=male, "iob"=female).
     * We score voices on gender match first, then online (premium) availability,
     * then quality level. Lower score = better voice.
     */
    private fun trySelectPremiumVoice(preferFemale: Boolean) {
        val voices = tts?.voices ?: return
        val enUsVoices = voices.filter { v ->
            v.locale.language == "en" && v.locale.country == "US"
        }
        if (enUsVoices.isEmpty()) return

        fun genderScore(name: String): Int {
            val n = name.lowercase()
            val looksFemaleName = FEMALE_VOICE_HINTS.any { n.contains(it) }
            val looksMaleName   = MALE_VOICE_HINTS.any { n.contains(it) }
            return when {
                preferFemale && looksFemaleName   -> 0      // perfect match
                !preferFemale && looksMaleName    -> 0      // perfect match
                !looksFemaleName && !looksMaleName -> 1_000  // unknown gender — small penalty
                else                              -> 2_000  // wrong gender — larger penalty
            }
        }

        val best = enUsVoices.minByOrNull { v ->
            val onlineBonus = if (v.isNetworkConnectionRequired) -10_000 else 0
            genderScore(v.name) + onlineBonus + (1000 - v.quality)
        }
        if (best != null) {
            tts?.voice = best
            Log.i(TAG, "TTS voice: ${best.name} quality=${best.quality} online=${best.isNetworkConnectionRequired} preferFemale=$preferFemale")
        }
    }

    /**
     * Speak text. If [flushQueue] is true, stops current speech immediately.
     */
    fun speak(text: String, flushQueue: Boolean = false) {
        if (text.isBlank()) return
        if (!isInitialized) {
            if (pendingQueue.size < 10) pendingQueue.add(text)
            return
        }
        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "u_${System.currentTimeMillis()}"
        tts?.speak(text, queueMode, null, utteranceId)
    }

    /** Re-apply voice and pitch when the user changes their gender preference in Settings. */
    fun setVoiceGender(gender: String) {
        if (!isInitialized) return
        applyGender(gender)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun destroy() {
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
