package com.personalai.craig.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.personalai.craig.R
import com.personalai.craig.data.preferences.SecurePreferences
import com.personalai.craig.ui.conversation.AssistantActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import ai.picovoice.porcupine.*
import javax.inject.Inject

/**
 * Always-listening foreground service that detects "Hey Craig" and "Help Me Craig"
 * using Porcupine wake word detection. On detection it launches AssistantActivity.
 *
 * IMPORTANT: Custom .ppn wake word model files must be placed in app/src/main/assets/:
 *   - hey_craig.ppn     (generated at console.picovoice.ai)
 *   - help_me_craig.ppn (generated at console.picovoice.ai)
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var prefs: SecurePreferences

    private var porcupineManager: PorcupineManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "WakeWordService"
        const val NOTIF_CHANNEL_ID = "craig_wake_word"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.personalai.craig.START_WAKE_WORD"
        const val ACTION_STOP  = "com.personalai.craig.STOP_WAKE_WORD"
        const val ACTION_PAUSE = "com.personalai.craig.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.personalai.craig.RESUME_WAKE_WORD"

        fun startIntent(ctx: Context) = Intent(ctx, WakeWordService::class.java).apply { action = ACTION_START }
        fun stopIntent(ctx: Context)  = Intent(ctx, WakeWordService::class.java).apply { action = ACTION_STOP }
        fun pauseIntent(ctx: Context) = Intent(ctx, WakeWordService::class.java).apply { action = ACTION_PAUSE }
        fun resumeIntent(ctx: Context)= Intent(ctx, WakeWordService::class.java).apply { action = ACTION_RESUME }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE  -> { porcupineManager?.stop(); return START_STICKY }
            ACTION_RESUME -> { porcupineManager?.start(); return START_STICKY }
        }

        // Start foreground immediately before any async work (Android 14 requirement)
        startForeground(NOTIF_ID, buildNotification("Listening for Hey Craig…"))
        serviceScope.launch { initPorcupine() }

        return START_STICKY
    }

    private suspend fun initPorcupine() {
        val accessKey = prefs.picovoiceKey.first()
        if (accessKey.isNullOrBlank()) {
            Log.e(TAG, "Picovoice access key not configured")
            updateNotification("Wake word disabled — configure Picovoice key in Settings")
            return
        }

        try {
            // Both .ppn files must exist in assets/. Porcupine uses them for detection.
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf("hey_craig.ppn", "help_me_craig.ppn"))
                .setSensitivities(floatArrayOf(0.5f, 0.5f))
                .build(applicationContext) { _ ->
                    onWakeWordDetected()
                }

            porcupineManager?.start()
            updateNotification("Listening for \"Hey Craig\"…")
            Log.i(TAG, "Porcupine started successfully")

        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine init failed: ${e.message}", e)
            updateNotification("Wake word error — check Picovoice key")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error initialising Porcupine: ${e.message}", e)
        }
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "Wake word detected — launching AssistantActivity")

        // Haptic feedback — safe-call since Vibrator may be null on some devices
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { /* ignore */ }

        // Pause listening while STT is active in AssistantActivity
        porcupineManager?.stop()

        val intent = Intent(this, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AssistantActivity.EXTRA_TRIGGER, AssistantActivity.TRIGGER_WAKE_WORD)
        }
        startActivity(intent)

        // Resume listening after 5 seconds (STT will have started by then)
        serviceScope.launch {
            delay(5_000)
            porcupineManager?.start()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Wake Word Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Craig is listening for your wake word"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Craig")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_outline)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_mic_outline, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        porcupineManager?.stop()
        porcupineManager?.delete()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
