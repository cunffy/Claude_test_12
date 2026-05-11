package com.personalai.craig.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's SpeechRecognizer for use in the UI layer.
 *
 * IMPORTANT: SpeechRecognizer must be created and used on the MAIN THREAD.
 * All public methods here are @MainThread — call them from an Activity or
 * via withContext(Dispatchers.Main).
 */
@Singleton
class SpeechToTextManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SpeechToTextManager"
    }

    sealed class SttState {
        object Idle      : SttState()
        object Listening : SttState()
        data class Partial(val text: String) : SttState()
        data class Result(val text: String)  : SttState()
        data class Error(val code: Int, val message: String) : SttState()
    }

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var lastLanguage = "en-US"
    private var busyRetryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    // Set to true by reset(); cleared by startListening(). Prevents a delayed retry
    // callback posted by onError() from creating a zombie recognizer after the
    // activity has already called reset() and gone away.
    @Volatile private var isReset = false

    @MainThread
    fun startListening(language: String = "en-US") {
        isReset = false
        lastLanguage = language
        busyRetryCount = 0
        startListeningInternal(language)
    }

    @MainThread
    private fun startListeningInternal(language: String) {
        if (isReset) return  // activity is gone; don't create a zombie recognizer
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(buildListener())

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        recognizer?.startListening(intent)
        _state.value = SttState.Listening
        Log.d(TAG, "Started listening")
    }

    @MainThread
    fun stopListening() {
        recognizer?.stopListening()
    }

    @MainThread
    fun cancel() {
        recognizer?.cancel()
        _state.value = SttState.Idle
    }

    /**
     * Full reset — clears any pending retry callbacks and destroys the recognizer.
     * Call from Activity.onPause() to prevent stale retries from firing after the
     * activity is gone.
     */
    @MainThread
    fun reset() {
        isReset = true
        mainHandler.removeCallbacksAndMessages(null)
        busyRetryCount = 0
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = SttState.Idle
    }

    @MainThread
    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        _state.value = SttState.Idle
    }

    private fun buildListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SttState.Listening
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(results: Bundle?) {
            val partial = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            if (partial.isNotBlank()) {
                _state.value = SttState.Partial(partial)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            _state.value = if (text.isNotBlank()) SttState.Result(text) else SttState.Error(
                SpeechRecognizer.ERROR_NO_MATCH, "No speech detected"
            )
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetryCount < 3) {
                busyRetryCount++
                Log.w(TAG, "STT busy — retry #$busyRetryCount in 400ms")
                recognizer?.destroy()
                recognizer = null
                mainHandler.postDelayed({ startListeningInternal(lastLanguage) }, 400L)
                return
            }
            busyRetryCount = 0
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO              -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT             -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                SpeechRecognizer.ERROR_NETWORK            -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH           -> "No speech matched"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER             -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "No speech detected"
                else -> "Unknown error ($error)"
            }
            Log.w(TAG, "STT error: $msg")
            _state.value = SttState.Error(error, msg)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
