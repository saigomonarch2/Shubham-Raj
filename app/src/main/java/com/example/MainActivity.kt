package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BroadcastOnHome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = getSharedPreferences("AudioMonitorPrefs", Context.MODE_PRIVATE)

        // Enqueue 48-hour automated cleanup background worker (Runs daily)
        try {
            val cleanupRequest = PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS)
                .addTag(CleanupWorker.TAG)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "recordings_cleanup_service",
                ExistingPeriodicWorkPolicy.KEEP,
                cleanupRequest
            )
            Log.d("MainActivity", "Successfully enqueued periodic background CleanupWorker")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error scheduling WorkManager task", e)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        prefs = prefs,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(prefs: SharedPreferences, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Persistent storage configuration keys
    var configServerUrl by remember {
        mutableStateOf(prefs.getString("server_url", "ws://192.168.1.100:8080") ?: "ws://192.168.1.100:8080")
    }
    var configSaveRecordings by remember {
        mutableStateOf(prefs.getBoolean("save_recordings", true))
    }
    var autoBootStart by remember {
        mutableStateOf(prefs.getBoolean("is_sender_active_on_boot", false))
    }

    // App Navigation state (role assignment: "none", "sender", "receiver")
    var userRole by remember {
        mutableStateOf(prefs.getString("user_role", "none") ?: "none")
    }

    // Foreground service tracking states from Kotlin companion state flows
    val isSenderRunning by AudioSenderService.isRunning.collectAsStateWithLifecycle()
    val senderStatus by AudioSenderService.connectionStatus.collectAsStateWithLifecycle()
    val senderURL by AudioSenderService.serverUrl.collectAsStateWithLifecycle()
    val senderAmplitude by AudioSenderService.currentAmplitude.collectAsStateWithLifecycle()

    val isReceiverRunning by AudioReceiverService.isRunning.collectAsStateWithLifecycle()
    val receiverStatus by AudioReceiverService.connectionStatus.collectAsStateWithLifecycle()
    val receiverURL by AudioReceiverService.serverUrl.collectAsStateWithLifecycle()
    val receiverAmplitude by AudioReceiverService.currentAmplitude.collectAsStateWithLifecycle()
    val isLoggingActive by AudioReceiverService.isRecordingSaved.collectAsStateWithLifecycle()

    // Recording playback and storage variables
    val recordingsList = remember { mutableStateListOf<File>() }
    var currentlyPlayingFile by remember { mutableStateOf<File?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Synchronize local UI files helper
    fun refreshRecordings() {
        recordingsList.clear()
        val dir = CleanupWorker.getRecordingsDirectory(context)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".wav") }
        if (files != null) {
            recordingsList.addAll(files.sortedByDescending { it.lastModified() })
        }
    }

    // Load initial recordings list
    LaunchedEffect(Unit) {
        refreshRecordings()
    }

    // Terminate mediaPlayer on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Permissions Request Launcher
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            Toast.makeText(context, "Permissions enabled successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Audio monitoring requires recording permission.", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header Title Block
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BroadcastOnHome,
                    contentDescription = "Broadcaster Logo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Live Audio Sync",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "2-Device Background Audio Monitor",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(bottom = 16.dp))

        // CONFIGURATION CARD (Global settings)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("config_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Global System Setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = configServerUrl,
                    onValueChange = { newVal ->
                        configServerUrl = newVal
                        prefs.edit().putString("server_url", newVal).apply()
                    },
                    label = { Text("WebSocket Server URL") },
                    placeholder = { Text("ws://192.168.1.100:8080") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_url_field")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Tip: Make sure both devices point to the exact same host server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ROLE SELECTION SHEET (if none selected)
        if (userRole == "none") {
            Text(
                text = "Select Device Role",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // SENDER ROLE OPTION CARD
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            userRole = "sender"
                            prefs
                                .edit()
                                .putString("user_role", "sender")
                                .apply()
                        }
                        .testTag("select_sender_role"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Microphone",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Sender Mode",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Transmits live microphone audio 24/7 background",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                // RECEIVER ROLE OPTION CARD
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            userRole = "receiver"
                            prefs
                                .edit()
                                .putString("user_role", "receiver")
                                .apply()
                        }
                        .testTag("select_receiver_role"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = "Receiver Listener",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Receiver Mode",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Receives streams, plays live sound, & logs recordings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        } else {
            // ROLE CONTROL PANELS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (userRole == "sender") "Sender Controls (Broadcaster)" else "Receiver Controls (Listener)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Swap Role button
                IconButton(
                    onClick = {
                        // Suppress other streams
                        context.stopService(Intent(context, AudioSenderService::class.java))
                        context.stopService(Intent(context, AudioReceiverService::class.java))
                        currentlyPlayingFile = null
                        mediaPlayer?.stop()
                        mediaPlayer = null

                        userRole = "none"
                        prefs.edit().putString("user_role", "none").apply()
                    },
                    modifier = Modifier.testTag("change_role_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Change Role",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (userRole == "sender") {
                // PART 1: SENDER CLIENT CONTROL INTERFACE
                SenderControlView(
                    isSenderRunning = isSenderRunning,
                    senderStatus = senderStatus,
                    senderAmplitude = senderAmplitude,
                    autoBootStart = autoBootStart,
                    onAutoBootChange = { checked ->
                        autoBootStart = checked
                        prefs.edit().putBoolean("is_sender_active_on_boot", checked).apply()
                    },
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = {
                        permissionLauncher.launch(requiredPermissions)
                    },
                    onToggleService = {
                        if (!permissionsGranted) {
                            permissionLauncher.launch(requiredPermissions)
                        } else {
                            val intent = Intent(context, AudioSenderService::class.java).apply {
                                putExtra(AudioSenderService.EXTRA_SERVER_URL, configServerUrl)
                            }
                            if (isSenderRunning) {
                                context.stopService(intent)
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                        }
                    }
                )
            } else {
                // PART 3: RECEIVER CLIENT CONTROL INTERFACE
                ReceiverControlView(
                    isReceiverRunning = isReceiverRunning,
                    receiverStatus = receiverStatus,
                    receiverAmplitude = receiverAmplitude,
                    isLoggingActive = isLoggingActive,
                    saveRecordings = configSaveRecordings,
                    onSaveRecordingsChange = { checked ->
                        configSaveRecordings = checked
                        prefs.edit().putBoolean("save_recordings", checked).apply()
                    },
                    onToggleService = {
                        val intent = Intent(context, AudioReceiverService::class.java).apply {
                            putExtra(AudioReceiverService.EXTRA_SERVER_URL, configServerUrl)
                            putExtra(AudioReceiverService.EXTRA_SAVE_RECORDINGS, configSaveRecordings)
                        }
                        if (isReceiverRunning) {
                            context.stopService(intent)
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        }
                    },
                    recordings = recordingsList,
                    currentlyPlaying = currentlyPlayingFile,
                    onPlayRecording = { file ->
                        try {
                            if (currentlyPlayingFile == file) {
                                mediaPlayer?.stop()
                                mediaPlayer?.release()
                                mediaPlayer = null
                                currentlyPlayingFile = null
                            } else {
                                mediaPlayer?.stop()
                                mediaPlayer?.release()
                                currentlyPlayingFile = file
                                mediaPlayer = MediaPlayer().apply {
                                    setDataSource(file.absolutePath)
                                    prepare()
                                    start()
                                    setOnCompletionListener {
                                        currentlyPlayingFile = null
                                        mediaPlayer = null
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error playing WAV audio file", Toast.LENGTH_SHORT).show()
                            currentlyPlayingFile = null
                            mediaPlayer = null
                        }
                    },
                    onDeleteRecording = { file ->
                        if (currentlyPlayingFile == file) {
                            mediaPlayer?.stop()
                            mediaPlayer?.release()
                            mediaPlayer = null
                            currentlyPlayingFile = null
                        }
                        if (file.delete()) {
                            Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
                            refreshRecordings()
                        } else {
                            Toast.makeText(context, "Failed to delete file", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRefreshRecordings = {
                        refreshRecordings()
                    },
                    onTriggerManualCleanup = {
                        val deletedCount = CleanupWorker.runManualCleanup(context)
                        Toast.makeText(context, "Purged $deletedCount old (>48h) WAV files.", Toast.LENGTH_LONG).show()
                        refreshRecordings()
                    }
                )
            }
        }
    }
}

@Composable
fun SenderControlView(
    isSenderRunning: Boolean,
    senderStatus: String,
    senderAmplitude: Float,
    autoBootStart: Boolean,
    onAutoBootChange: (Boolean) -> Unit,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleService: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("sender_panel"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSenderRunning) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Status marker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSenderRunning && senderStatus == "Connected") {
                                    Color.Green
                                } else if (isSenderRunning) {
                                    Color.Yellow
                                } else {
                                    Color.Red
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Status: $senderStatus",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isSenderRunning) {
                    Text(
                        text = "Sender Service Active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pulse Signal amplitude animation visualizer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isSenderRunning && senderStatus == "Connected") {
                    AudioWaveformVisualizer(amplitude = senderAmplitude)
                } else {
                    Text(
                        text = "No audio signal streaming | Service stopped",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Boot Autostart checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onAutoBootChange(!autoBootStart) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = autoBootStart,
                    onCheckedChange = { onAutoBootChange(it) },
                    modifier = Modifier.testTag("auto_boot_switch")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Auto-Start Broadcast on Boot",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Uses BOOT_COMPLETED receiver to launch invisibly on startup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Button
            if (!permissionsGranted) {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request_permissions_btn")
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "Permissions required")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Microphone Permissions")
                }
            } else {
                Button(
                    onClick = onToggleService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSenderRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sender_broadcast_btn")
                ) {
                    Icon(
                        imageVector = if (isSenderRunning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "Sender Toggle Button"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isSenderRunning) "Stop Live Stream (Sender)" else "Start Live Stream (Sender)")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 24/7 details text
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Details logo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "This broadcaster implements WakeLock, WifiLock, and a continuous Foreground notification to stream audio invisibly 24/7, even when screen is off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ReceiverControlView(
    isReceiverRunning: Boolean,
    receiverStatus: String,
    receiverAmplitude: Float,
    isLoggingActive: Boolean,
    saveRecordings: Boolean,
    onSaveRecordingsChange: (Boolean) -> Unit,
    onToggleService: () -> Unit,
    recordings: List<File>,
    currentlyPlaying: File?,
    onPlayRecording: (File) -> Unit,
    onDeleteRecording: (File) -> Unit,
    onRefreshRecordings: () -> Unit,
    onTriggerManualCleanup: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // RECEIVER PANEL
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .testTag("receiver_panel"),
            colors = CardDefaults.cardColors(
                containerColor = if (isReceiverRunning) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Connection tracking
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isReceiverRunning && receiverStatus == "Connected") {
                                        Color.Green
                                    } else if (isReceiverRunning) {
                                        Color.Yellow
                                    } else {
                                        Color.Red
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Status: $receiverStatus",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (isReceiverRunning) {
                        Text(
                            text = "Receiver Active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Received Audio signal tracker
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (isReceiverRunning && receiverStatus == "Connected") {
                        AudioWaveformVisualizer(amplitude = receiverAmplitude)
                    } else {
                        Text(
                            text = "No real-time signal stream playing",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // File saving config option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onSaveRecordingsChange(!saveRecordings) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = saveRecordings,
                        onCheckedChange = { onSaveRecordingsChange(it) },
                        modifier = Modifier.testTag("save_recordings_switch")
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Auto-Save Live Stream recordings",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Simultaneously saves incoming PCM to WAV on local storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // If logging/saving is in progress
                AnimatedVisibility(visible = isLoggingActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Recording indicator",
                            tint = Color.Red,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Writing live stream to WAV file logger...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Toggle play button
                Button(
                    onClick = onToggleService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReceiverRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("receiver_stream_toggle")
                ) {
                    Icon(
                        imageVector = if (isReceiverRunning) Icons.Default.Stop else Icons.Default.VolumeUp,
                        contentDescription = "Receiver Toggle Button"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isReceiverRunning) "Stop Listening" else "Start Listening / Stream Playback")
                }
            }
        }

        // STORED LOCAL REC FILE LOG LIST
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Recordings Folder icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Saved Audio WAV Files",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row {
                        IconButton(onClick = onRefreshRecordings) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Log",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = onTriggerManualCleanup,
                            modifier = Modifier.testTag("manual_cleanup_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Trigger Cleanup Manual",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // 2 day banner info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "WAV cleanup banner",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Automatic 2-day (48 hours) file retention active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }

                if (recordings.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No saved audio files found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recordings) { file ->
                            val isPlaying = currentlyPlaying == file
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isPlaying) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        }
                                    )
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Size: ${formatFileSize(file.length())} | Age: ${formatLastModified(file.lastModified())}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }

                                Row {
                                    IconButton(onClick = { onPlayRecording(file) }) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = "Playback button",
                                            tint = if (isPlaying) Color.Red else MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    IconButton(onClick = { onDeleteRecording(file) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete File",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioWaveformVisualizer(amplitude: Float, modifier: Modifier = Modifier) {
    // Generate simple animated wave lines to represent audio streams
    val transition = rememberInfiniteTransition(label = "wave_pulsing")
    val phaseShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val midY = height / 2f
        val maxAmp = (amplitude.coerceIn(0.01f, 1f)) * (height / 2f - 4f)

        val pathPoints = 40
        for (i in 0..pathPoints) {
            val progress = i.toFloat() / pathPoints
            val x = progress * width
            // Sine formula with shift
            val sinVal = Math.sin((progress * 4 * Math.PI) + phaseShift).toFloat()
            val yOffset = sinVal * maxAmp
            
            // Draw visual bounce lines
            val barHeight = (yOffset.coerceAtLeast(-maxAmp).coerceAtMost(maxAmp))
            drawLine(
                color = color.copy(alpha = 0.8f - (progress * 0.4f)),
                start = Offset(x, midY - barHeight - 2f),
                end = Offset(x, midY + barHeight + 2f),
                strokeWidth = 4f
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

private fun formatLastModified(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return "Just now"
    val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
    if (mins < 1) return "Just now"
    if (mins < 60) return "$mins m ago"
    val hours = mins / 60
    if (hours < 24) return "$hours h ago"
    val days = hours / 24
    return "$days d ago"
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
