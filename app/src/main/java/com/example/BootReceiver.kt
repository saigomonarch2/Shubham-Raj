package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed! Checking if Live Audio Sender should auto-start...")

            val prefs = context.getSharedPreferences("AudioMonitorPrefs", Context.MODE_PRIVATE)
            val isSenderActive = prefs.getBoolean("is_sender_active_on_boot", false)
            val serverUrl = prefs.getString("server_url", "ws://192.168.1.100:8080") ?: "ws://192.168.1.100:8080"

            if (isSenderActive) {
                Log.d(TAG, "Sender mode was active prior to boot. Starting AudioSenderService to server: $serverUrl...")
                val serviceIntent = Intent(context, AudioSenderService::class.java).apply {
                    putExtra(AudioSenderService.EXTRA_SERVER_URL, serverUrl)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "Successfully triggered AudioSenderService auto-start on boot.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start AudioSenderService on boot", e)
                }
            } else {
                Log.d(TAG, "Auto-start on boot is disabled or was not active. Doing nothing.")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
