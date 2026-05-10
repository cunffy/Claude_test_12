package com.personalai.craig.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.personalai.craig.R
import com.personalai.craig.ui.conversation.AssistantActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var okHttpClient: OkHttpClient

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var isPaused = false
    @Volatile private var shouldStop = false
    @Volatile private var lastTriggerMs = 0L
    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        // Grammar limits Vosk to only these phrases — faster and more accurate for wake words
        private const val WAKE_GRAMMAR = """["hey craig", "help me craig", "[unk]"]"""
        private val WAKE_PHRASES = listOf("hey craig", "help me craig")
        private const val MIN_CONFIDENCE = 0.72f  // per-word minimum; below this = ambient noise

        const val NOTIF_CHANNEL_ID = "craig_wake_word"
        const val NOTIF_ID = 1001
        const val ACTION_START  = "com.personalai.craig.START_WAKE_WORD"
        const val ACTION_STOP   = "com.personalai.craig.STOP_WAKE_WORD"
        const val ACTION_PAUSE  = "com.personalai.craig.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.personalai.craig.RESUME_WAKE_WORD"

        fun startIntent(ctx: Context)  = Intent(ctx, WakeWordService::class.java).apply { action = ACTION_START }
        fun stopIntent(ctx: Context)   = Intent(ctx, WakeWordService::class.java).apply { action = ACTION_STOP }
        fun pauseIntent(ctx: Context)  = Intent(ctx, WakeWordService::class.java).apply { action = ACTION_PAUSE }
        fun resumeIntent(ctx: Context) = Intent(ctx, WakeWordService::class.java).apply { action = ACTION_RESUME }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> { shouldStop = true; stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE  -> { isPaused = true;  return START_STICKY }
            ACTION_RESUME -> { isPaused = false; return START_STICKY }
        }
        // On Android 14+, startForeground() with foregroundServiceType=microphone
        // throws SecurityException if RECORD_AUDIO is not yet granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        serviceScope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val modelDir = File(filesDir, MODEL_DIR_NAME)

        val integrityMarker = File(modelDir, "am/final.mdl")
        if (!modelDir.exists() || !integrityMarker.exists()) {
            if (modelDir.exists()) {
                Log.w(TAG, "Model dir exists but integrity check failed — re-downloading")
                modelDir.deleteRecursively()
            }
            updateNotification("Downloading wake word model (~40 MB)…")
            if (!downloadModel(modelDir)) {
                updateNotification("Model download failed — wake word disabled")
                return
            }
        }

        try {
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), WAKE_GRAMMAR)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model: ${e.message}", e)
            modelDir.deleteRecursively()
            updateNotification("Model error — will retry on next launch")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission required")
            return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 4
        )

        try {
            audioRecord?.startRecording()
            updateNotification("Listening for \"Hey Craig\"…")
            Log.i(TAG, "Vosk wake word detection started")

            val buf = ByteArray(bufSize)
            while (!shouldStop) {
                if (isPaused) { delay(200); continue }
                val nread = audioRecord?.read(buf, 0, buf.size) ?: break
                if (nread <= 0) continue
                if (recognizer?.acceptWaveForm(buf, nread) == true) {
                    // Only check the committed full result — partialResult causes too many
                    // false positives and spurious vibrations when the app is backgrounded.
                    checkForWakeWord(recognizer?.result)
                }
            }
        } finally {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun checkForWakeWord(json: String?) {
        if (json == null) return
        val lower = json.lowercase()
        if (WAKE_PHRASES.none { lower.contains(it) }) return

        // Require a minimum per-word confidence so ambient speech that vaguely
        // resembles the wake phrase doesn't trigger a false positive.
        if (minWordConfidence(json) < MIN_CONFIDENCE) {
            Log.d(TAG, "Wake phrase detected but confidence too low — ignoring: $json")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerMs > 5_000) {
            lastTriggerMs = now
            onWakeWordDetected()
        }
    }

    /**
     * Returns the minimum confidence score across all words in a Vosk result JSON.
     * Vosk result format: {"text":"hey craig","result":[{"conf":0.96,...},{"conf":0.87,...}]}
     * Returns 1.0 if no result array is present (treat as fully confident).
     */
    private fun minWordConfidence(json: String): Float {
        return try {
            val arr = JSONObject(json).optJSONArray("result") ?: return 1.0f
            var min = Float.MAX_VALUE
            for (i in 0 until arr.length()) {
                val conf = arr.getJSONObject(i).optDouble("conf", 1.0).toFloat()
                if (conf < min) min = conf
            }
            if (min == Float.MAX_VALUE) 1.0f else min
        } catch (e: Exception) {
            1.0f
        }
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "Wake word detected — launching AssistantActivity")
        isPaused = true

        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { /* ignore */ }

        startActivity(Intent(this, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AssistantActivity.EXTRA_TRIGGER, AssistantActivity.TRIGGER_WAKE_WORD)
        })

        serviceScope.launch {
            delay(5_000)
            isPaused = false
        }
    }

    private suspend fun downloadModel(destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = okHttpClient.newCall(Request.Builder().url(MODEL_URL).build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Model download HTTP error: ${response.code}")
                return@withContext false
            }
            val bodyStream = response.body?.byteStream() ?: return@withContext false
            destDir.mkdirs()

            ZipInputStream(bodyStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // Strip the single top-level directory from entry names
                    val relative = entry.name.substringAfter('/')
                    if (relative.isNotEmpty()) {
                        val out = File(destDir, relative)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            Log.i(TAG, "Model downloaded and extracted to ${destDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}", e)
            destDir.deleteRecursively()
            false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "Wake Word Detection", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Craig is listening for your wake word"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Craig")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_outline)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_mic_outline, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        shouldStop = true
        recognizer?.close()
        model?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
