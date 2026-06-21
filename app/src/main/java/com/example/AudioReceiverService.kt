package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
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
import okio.ByteString
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AudioReceiverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var audioTrack: AudioTrack? = null
    private var targetServerUrl = "ws://192.168.1.100:8080"
    private var reconnectJob: Job? = null

    // For file logging
    private var currentRecordingFile: File? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var isSavingRecordings = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        _isRunning.value = true
        initAudioTrack()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "ws://192.168.1.100:8080"
        isSavingRecordings = intent?.getBooleanExtra(EXTRA_SAVE_RECORDINGS, true) ?: true
        targetServerUrl = url
        _serverUrl.value = url

        _connectionStatus.value = "Connecting..."
        startForegroundServiceNotification()
        connectWebSocket()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectWebSocket()
        stopAudioTrack()
        stopFileSaving()
        _isRunning.value = false
        _connectionStatus.value = "Stopped"
        _currentAmplitude.value = 0f
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initAudioTrack() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufSize,
                    AudioTrack.MODE_STREAM
                )
            }
            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized and playing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    private fun stopAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null
    }

    private fun connectWebSocket() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Service destroyed")

        val cleanUrl = if (!targetServerUrl.startsWith("ws://") && !targetServerUrl.startsWith("wss://")) {
            "ws://$targetServerUrl"
        } else {
            targetServerUrl
        }

        Log.d(TAG, "Receiver connecting to WebSocket: $cleanUrl")
        val request = Request.Builder()
            .url(cleanUrl)
            .addHeader("X-Role", "receiver") // Identify as receiver
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connection Opened (Receiver)")
                _connectionStatus.value = "Connected"
                updateNotification("Connected. Playing live audio...")
                if (isSavingRecordings) {
                    startFileSaving()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val audioBytes = bytes.toByteArray()

                // Play back audio instantly via AudioTrack
                audioTrack?.let { track ->
                    try {
                        track.write(audioBytes, 0, audioBytes.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing bytes to AudioTrack", e)
                    }
                }

                // Append bytes to active audio file logger (if enabled)
                randomAccessFile?.let { raf ->
                    try {
                        raf.write(audioBytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing to recording file", e)
                    }
                }

                // Calculate visual amplitude to anim in GUI
                var sum = 0.0
                for (i in 0 until audioBytes.size step 2) {
                    if (i + 1 < audioBytes.size) {
                        val sample = ((audioBytes[i + 1].toInt() shl 8) or (audioBytes[i].toInt() and 0xFF)).toShort()
                        sum += sample * sample
                    }
                }
                val rms = Math.sqrt(sum / (audioBytes.size / 2))
                val amp = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                _currentAmplitude.value = amp
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure in Receiver: ${t.message}", t)
                _connectionStatus.value = "Reconnecting..."
                updateNotification("Connection lost. Reconnecting...")
                stopFileSaving()
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing (Receiver)")
                _connectionStatus.value = "Disconnected"
                stopFileSaving()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed (Receiver)")
                _connectionStatus.value = "Disconnected"
                stopFileSaving()
            }
        })
    }

    private fun disconnectWebSocket() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Receiver stop")
        webSocket = null
        _connectionStatus.value = "Disconnected"
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(5000)
            if (_isRunning.value && _connectionStatus.value == "Reconnecting...") {
                Log.d(TAG, "Attempting Receiver reconnect...")
                connectWebSocket()
            }
        }
    }

    private fun startFileSaving() {
        stopFileSaving()
        try {
            val directory = CleanupWorker.getRecordingsDirectory(this)
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val filename = "recording_${sdf.format(Date())}.wav"
            val file = File(directory, filename)
            currentRecordingFile = file

            val raf = RandomAccessFile(file, "rw")
            randomAccessFile = raf

            // Write blank WAV header first (44 bytes placeholder)
            writeWavHeader(raf, 1, 16000, 16)
            Log.d(TAG, "Started writing to file logger: ${file.absolutePath}")
            _isRecordingSaved.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting file logging save", e)
        }
    }

    private fun stopFileSaving() {
        val raf = randomAccessFile
        val file = currentRecordingFile
        randomAccessFile = null
        currentRecordingFile = null

        if (raf != null && file != null) {
            try {
                // Update WAV header sizes
                val fileLength = file.length()
                Log.d(TAG, "Finalizing file logger: ${file.name}, total path size: $fileLength bytes")

                // Update Little Endian sizes in WAV header
                raf.seek(4)
                writeIntLittleEndian(raf, (fileLength - 8).toInt())

                raf.seek(40)
                writeIntLittleEndian(raf, (fileLength - 44).toInt())

                raf.close()
                Log.d(TAG, "WAV Header rewritten correctly. File finalized.")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing final WAV header updates", e)
            }
        }
        _isRecordingSaved.value = false
    }

    private fun writeWavHeader(raf: RandomAccessFile, channels: Short, sampleRate: Int, bitsPerSample: Short) {
        raf.seek(0)
        raf.writeBytes("RIFF")               // ChunkID
        writeIntLittleEndian(raf, 0)         // ChunkSize (will rewrite on stop)
        raf.writeBytes("WAVE")               // Format
        raf.writeBytes("fmt ")               // Subchunk1ID
        writeIntLittleEndian(raf, 16)        // Subchunk1Size
        writeShortLittleEndian(raf, 1)       // AudioFormat (1 for PCM)
        writeShortLittleEndian(raf, channels)// NumChannels
        writeIntLittleEndian(raf, sampleRate)// SampleRate
        writeIntLittleEndian(raf, sampleRate * channels * bitsPerSample / 8) // ByteRate
        writeShortLittleEndian(raf, (channels * bitsPerSample / 8).toShort()) // BlockAlign
        writeShortLittleEndian(raf, bitsPerSample) // BitsPerSample
        raf.writeBytes("data")               // Subchunk2ID
        writeIntLittleEndian(raf, 0)         // Subchunk2Size (will rewrite on stop)
    }

    private fun writeIntLittleEndian(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLittleEndian(raf: RandomAccessFile, value: Short) {
        raf.write(value.toInt() and 0xFF)
        raf.write((value.toInt() ushr 8) and 0xFF)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Live Audio Receiver Channel"
            val descriptionText = "Displays status for live audio receiving and playback"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildServiceNotification("Connecting to stream...")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification(contentText))
    }

    private fun buildServiceNotification(contentText: String): Notification {
        val stopIntent = Intent(this, AudioReceiverService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, flags)

        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Monitor (Listening)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(activityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Playing", stopPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "AudioReceiverService"
        private const val CHANNEL_ID = "audio_monitor_receiver_channel"
        private const val NOTIFICATION_ID = 20262

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_SAVE_RECORDINGS = "extra_save_recordings"
        const val ACTION_STOP_SERVICE = "action_stop_receiver_service"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _connectionStatus = MutableStateFlow("Disconnected")
        val connectionStatus = _connectionStatus.asStateFlow()

        private val _serverUrl = MutableStateFlow("ws://192.168.1.100:8080")
        val serverUrl = _serverUrl.asStateFlow()

        private val _currentAmplitude = MutableStateFlow(0f)
        val currentAmplitude = _currentAmplitude.asStateFlow()

        private val _isRecordingSaved = MutableStateFlow(false)
        val isRecordingSaved = _isRecordingSaved.asStateFlow()
    }
}
