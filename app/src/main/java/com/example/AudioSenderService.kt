package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class AudioSenderService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var targetServerUrl = "ws://192.168.1.100:8080"
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "ws://192.168.1.100:8080"
        targetServerUrl = url
        _serverUrl.value = url

        _connectionStatus.value = "Connecting..."
        startForegroundServiceNotification()
        connectWebSocket()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        disconnectWebSocket()
        releaseLocks()
        _isRunning.value = false
        _connectionStatus.value = "Stopped"
        _currentAmplitude.value = 0f
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioMonitor::SenderWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AudioMonitor::SenderWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "WakeLock and WifiLock acquired for continuous background execution")
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    private fun connectWebSocket() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Service destroyed")

        val cleanUrl = if (!targetServerUrl.startsWith("ws://") && !targetServerUrl.startsWith("wss://")) {
            "ws://$targetServerUrl"
        } else {
            targetServerUrl
        }

        Log.d(TAG, "Connecting to WebSocket: $cleanUrl")
        val request = Request.Builder()
            .url(cleanUrl)
            .addHeader("X-Role", "sender") // Identify as sender
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connection Opened")
                _connectionStatus.value = "Connected"
                updateNotification("Connected. Streaming active...")
                startRecording()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Sender doesn't typically handle incoming text, but logs it
                Log.d(TAG, "Received frame from server: $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionStatus.value = "Reconnecting..."
                updateNotification("Connection failed. Retrying...")
                stopRecording()
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason ($code)")
                _connectionStatus.value = "Disconnected"
                stopRecording()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed")
                _connectionStatus.value = "Disconnected"
                stopRecording()
            }
        })
    }

    private fun disconnectWebSocket() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Manual stop")
        webSocket = null
        _connectionStatus.value = "Disconnected"
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(5000) // retry after 5 seconds
            if (_isRunning.value && _connectionStatus.value == "Reconnecting...") {
                Log.d(TAG, "Attempting connection retry...")
                connectWebSocket()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            4096
        } else {
            minBufSize * 2
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _connectionStatus.value = "Audio Hardware Error"
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(1024) // Extremely low latency packet chunk: ~32ms of audio
                while (isRecording) {
                    val record = audioRecord
                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        break
                    }
                    val readBytes = record.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        val payload = buffer.copyOf(readBytes)
                        webSocket?.send(payload.toByteString())

                        // Calculate live amplitude to animate the signal wave in compose
                        var sum = 0.0
                        for (i in 0 until readBytes step 2) {
                            if (i + 1 < readBytes) {
                                val sample = ((payload[i + 1].toInt() shl 8) or (payload[i].toInt() and 0xFF)).toShort()
                                sum += sample * sample
                            }
                        }
                        val rms = Math.sqrt(sum / (readBytes / 2))
                        val amp = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        _currentAmplitude.value = amp
                    }
                }
            }
            recordingThread?.start()
            Log.d(TAG, "AudioRecord streaming thread started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording background thread", e)
        }
    }

    private fun stopRecording() {
        isRecording = false
        try {
            recordingThread?.join(500)
        } catch (e: Exception) {
            // ignore
        }
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null
        _currentAmplitude.value = 0f
        Log.d(TAG, "Audio recording streaming thread stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Live Audio Monitoring Channel"
            val descriptionText = "Displays status for live audio broadcasting in background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildServiceNotification("Connecting to host server...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification(contentText))
    }

    private fun buildServiceNotification(contentText: String): Notification {
        val stopIntent = Intent(this, AudioSenderService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        // Compatibility flag for pending intent
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)

        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Monitor (Sender Running)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(activityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Stream", stopPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "AudioSenderService"
        private const val CHANNEL_ID = "audio_monitor_sender_channel"
        private const val NOTIFICATION_ID = 20261

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val ACTION_STOP_SERVICE = "action_stop_service"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _connectionStatus = MutableStateFlow("Disconnected")
        val connectionStatus = _connectionStatus.asStateFlow()

        private val _serverUrl = MutableStateFlow("ws://192.168.1.100:8080")
        val serverUrl = _serverUrl.asStateFlow()

        private val _currentAmplitude = MutableStateFlow(0f)
        val currentAmplitude = _currentAmplitude.asStateFlow()
    }
}
