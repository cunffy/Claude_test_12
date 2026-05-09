package com.personalai.craig.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalai.craig.ai.MemoryManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager job that runs daily to summarize old conversations
 * and keep the memory system efficient.
 */
@HiltWorker
class MemorySummarizationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val memoryManager: MemoryManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MemorySummarizationWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting daily memory summarization")
            memoryManager.summarizeOldConversations()
            Log.i(TAG, "Memory summarization complete")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Memory summarization failed: ${e.message}", e)
            Result.retry()
        }
    }
}
