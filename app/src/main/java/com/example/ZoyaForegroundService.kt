package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.live.LiveSessionManager
import com.example.live.MayaWakeWordManager
import com.example.live.ZoyaState
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ZoyaForegroundService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    lateinit var liveSessionManager: LiveSessionManager
    private lateinit var toolEngine: ToolExecutionEngine
    private var wakeWordManager: MayaWakeWordManager? = null

    // WakeLock to prevent the CPU from entering deep sleep and stopping background listening
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRecording = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val outputSampleRate = 24000
    private val outChannelConfig = AudioFormat.CHANNEL_OUT_MONO

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MAYA_FOREGROUND_CHANNEL"

        var currentState: ZoyaState = ZoyaState.IDLE
            private set
        var onStateChange: ((ZoyaState) -> Unit)? = null

        private val _messages = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
        val messages: kotlinx.coroutines.flow.StateFlow<List<String>> = _messages.asStateFlow()

        var activeService: ZoyaForegroundService? = null

        fun isBatteryOptimizationIgnored(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            activeService = this
            toolEngine = ToolExecutionEngine(this)

            // Acquire Partial WakeLock so background listening doesn't get throttled by Doze
            acquireWakeLock()

            val onAudioOut: (ByteArray) -> Unit = { audioData ->
                playAudio(audioData)
            }

            val onInterruptOut: () -> Unit = {
                try {
                    audioOutputQueue.clear()
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                    }
                } catch (e: Exception) {
                    Log.e("MayaService", "Error flushing track on interrupt", e)
                }
            }

            liveSessionManager = LiveSessionManager(this, toolEngine, onAudioOut, onInterruptOut)

            createNotificationChannel()
            val initialNotification = buildNotification("Maya is listening for 'Ok Maya'...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, initialNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (e: Exception) {
                    try { startForeground(NOTIFICATION_ID, initialNotification) } catch (_: Exception) {}
                }
            } else {
                try { startForeground(NOTIFICATION_ID, initialNotification) } catch (_: Exception) {}
            }

            scope.launch {
                liveSessionManager.zoyaState.collect { state ->
                    currentState = state
                    onStateChange?.invoke(state)

                    // Update persistent notification with current state
                    val stateText = when (state) {
                        ZoyaState.SPEAKING -> "Speaking..."
                        ZoyaState.LISTENING -> "Listening to you..."
                        ZoyaState.THINKING -> "Executing action..."
                        ZoyaState.IDLE -> "Maya is listening for 'Ok Maya'..."
                    }
                    updateNotification(stateText)

                    // Coordinate wake word manager with live session
                    if (state == ZoyaState.IDLE) {
                        wakeWordManager?.resumeListening()
                    } else {
                        wakeWordManager?.pauseListening()
                    }
                }
            }

            scope.launch {
                liveSessionManager.messages.collect { msgList ->
                    _messages.value = msgList
                }
            }

            initAudioTrack()
            startMicrophoneLoop()

            // Setup Wake Word Manager for "Ok Maya"
            initWakeWordManager()

            // Connect initial session
            liveSessionManager.startSession()
        } catch (e: Exception) {
            Log.e("MayaService", "Error in onCreate", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Maya::BackgroundListeningWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours lock
            }
            Log.d("MayaService", "Acquired partial WakeLock for background service.")
        } catch (e: Exception) {
            Log.e("MayaService", "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("MayaService", "Released WakeLock.")
            }
        } catch (e: Exception) {
            Log.e("MayaService", "Error releasing WakeLock: ${e.message}")
        }
    }

    private fun initWakeWordManager() {
        val prefs = getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE)
        val wakeWordEnabled = prefs.getBoolean("handsfree_wake_word", true)

        if (wakeWordEnabled) {
            wakeWordManager = MayaWakeWordManager(this) { heardPhrase ->
                Log.i("MayaService", "Wake word recognized: '$heardPhrase'. Waking up Maya!")
                triggerHapticFeedback()

                if (currentState == ZoyaState.IDLE) {
                    liveSessionManager.startSession()
                }

                val cleanCommand = heardPhrase
                    .replace(Regex("(?i)^(ok|okay|hey|hello|hi)?\\s*(maya|माया|सुनो माया)\\s*"), "")
                    .trim()

                if (cleanCommand.isNotEmpty()) {
                    liveSessionManager.sendTextMessage(cleanCommand)
                }
            }
            wakeWordManager?.startListening()
        }
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(100)
                }
            }
        } catch (_: Exception) {}
    }

    private val audioOutputQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var isAudioPlaybackActive = false

    private fun startAudioPlaybackLoop() {
        isAudioPlaybackActive = true
        scope.launch(Dispatchers.IO) {
            while (isActive && isAudioPlaybackActive) {
                try {
                    val data = audioOutputQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (data != null) {
                        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack?.play()
                        }
                        audioTrack?.write(data, 0, data.size)
                    }
                } catch (e: Exception) {
                    Log.e("MayaService", "Playback loop error", e)
                }
            }
        }
    }

    private fun initAudioTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(outputSampleRate, outChannelConfig, audioFormat)
            val finalBuf = if (minBuf > 0) minBuf * 4 else 8192

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(outputSampleRate)
                        .setChannelMask(outChannelConfig)
                        .build()
                )
                .setBufferSizeInBytes(finalBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            startAudioPlaybackLoop()
        } catch (e: Exception) {
            Log.e("MayaService", "Error initializing AudioTrack", e)
        }
    }

    private fun playAudio(data: ByteArray) {
        try {
            audioOutputQueue.offer(data)
        } catch (e: Exception) {
            Log.e("MayaService", "Error queueing audio", e)
        }
    }

    private fun startMicrophoneLoop() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("MayaService", "Missing RECORD_AUDIO permission")
            return
        }

        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val finalBuf = if (minBuf > 0) minBuf * 4 else 8192

            val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                createAttributionContext("maya_audio")
            } else {
                this
            }

            audioRecord = AudioRecord.Builder()
                .setContext(ctx)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(finalBuf)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MayaService", "AudioRecord initialization failed!")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            scope.launch(Dispatchers.IO) {
                val chunkSize = 1600
                val audioBuffer = ShortArray(chunkSize)
                while (isActive && isRecording) {
                    try {
                        val readResult = audioRecord?.read(audioBuffer, 0, chunkSize) ?: 0
                        if (readResult > 0) {
                            processAudio(audioBuffer, readResult)
                        }
                    } catch (e: Exception) {
                        Log.e("MayaService", "Error reading audio", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MayaService", "Error starting microphone", e)
        }
    }

    private fun processAudio(buffer: ShortArray, length: Int) {
        val state = liveSessionManager.zoyaState.value
        if (state != ZoyaState.IDLE) {
            liveSessionManager.sendAudioData(buffer, length)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun sendTextMessage(text: String) {
        liveSessionManager.sendTextMessage(text)
    }

    fun reconnectSession() {
        liveSessionManager.startSession()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("MayaService", "App task swiped away from recent apps. Keeping Maya alive in background!")

        // Schedule immediate restart alarm in case system tries to clean process
        try {
            val restartServiceIntent = Intent(applicationContext, ZoyaForegroundService::class.java).apply {
                setPackage(packageName)
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                101,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.RTC,
                System.currentTimeMillis() + 1000,
                restartPendingIntent
            )
        } catch (e: Exception) {
            Log.e("MayaService", "Error scheduling restart alarm onTaskRemoved", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeService = null
        isRecording = false
        isAudioPlaybackActive = false
        currentState = ZoyaState.IDLE
        onStateChange?.invoke(currentState)
        audioOutputQueue.clear()

        releaseWakeLock()

        try { wakeWordManager?.destroy() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        try { liveSessionManager.stopSession() } catch (_: Exception) {}
        job.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Maya Assistant Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Maya voice assistant running in the background and listening for 'Ok Maya'"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Stop service
        val stopIntent = Intent(this, ZoyaForegroundService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Maya AI Assistant")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_directions, "Open", pendingIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
