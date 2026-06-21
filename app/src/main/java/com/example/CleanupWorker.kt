package com.example

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileFilter
import java.util.concurrent.TimeUnit

class CleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic cleanup work for recordings older than 48 hours")
        try {
            val directory = getRecordingsDirectory(applicationContext)
            if (!directory.exists()) {
                Log.d(TAG, "Recordings directory does not exist yet. No cleanup needed.")
                return Result.success()
            }

            val thresholdMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
            val filesToDelete = directory.listFiles(FileFilter { file ->
                file.isFile && file.name.endsWith(".wav") && file.lastModified() < thresholdMillis
            })

            var count = 0
            if (filesToDelete != null) {
                for (file in filesToDelete) {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old recording file: ${file.name}")
                        count++
                    } else {
                        Log.w(TAG, "Failed to delete old recording file: ${file.name}")
                    }
                }
            }

            Log.d(TAG, "Cleanup completed. Deleted $count files.")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing cleanup worker", e)
            return Result.failure()
        }
    }

    companion object {
        const val TAG = "CleanupWorker"

        fun getRecordingsDirectory(context: Context): File {
            // Store inside standard External Files Music directory (no permissions required starting SDK 19)
            val dir = File(context.getExternalFilesDir(null), "Recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        fun runManualCleanup(context: Context): Int {
            Log.d(TAG, "Running manual cleanup helper")
            val directory = getRecordingsDirectory(context)
            if (!directory.exists()) return 0
            val thresholdMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
            val filesToDelete = directory.listFiles(FileFilter { file ->
                file.isFile && file.name.endsWith(".wav") && file.lastModified() < thresholdMillis
            }) ?: return 0

            var count = 0
            for (file in filesToDelete) {
                if (file.delete()) {
                    count++
                }
            }
            return count
        }
    }
}
