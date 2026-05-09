package com.personalai.craig.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.personalai.craig.data.db.dao.AppContextDao
import com.personalai.craig.data.db.entities.AppContextEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Reads the current screen content and stores it in the database so Craig
 * can reference what the user is looking at when answering questions.
 *
 * Must be enabled manually in Settings > Accessibility > Craig.
 */
@AndroidEntryPoint
class ContextAccessibilityService : AccessibilityService() {

    @Inject lateinit var appContextDao: AppContextDao

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ContextAccessibility"
        private const val MAX_TEXT_LENGTH = 2_000
        private val SKIP_PACKAGES = setOf(
            "com.personalai.craig",
            "com.android.systemui",
            "android"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName in SKIP_PACKAGES) return

        val rootNode = rootInActiveWindow ?: return

        scope.launch {
            try {
                val text = extractText(rootNode).take(MAX_TEXT_LENGTH)
                val windowTitle = event.text?.firstOrNull()?.toString()

                appContextDao.insert(
                    AppContextEntity(
                        packageName = packageName,
                        screenText = text,
                        windowTitle = windowTitle,
                        timestamp = System.currentTimeMillis()
                    )
                )
                appContextDao.pruneOld()
            } catch (e: Exception) {
                Log.w(TAG, "Error reading screen: ${e.message}")
            }
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            sb.append(extractText(node.getChild(i)))
        }
        return sb.toString().trim()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
