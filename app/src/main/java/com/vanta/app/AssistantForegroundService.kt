package com.vanta.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** Owns local wake-word capture and hands-free TTS to prevent microphone feedback loops. */
class AssistantForegroundService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "com.vanta.app.voice.START"
        const val ACTION_STOP = "com.vanta.app.voice.STOP"
        const val ACTION_PAUSE = "com.vanta.app.voice.PAUSE"
        const val ACTION_RECORDING = "com.vanta.app.voice.RECORDING"
        const val ACTION_PROCESSING = "com.vanta.app.voice.PROCESSING"
        const val ACTION_RESUME = "com.vanta.app.voice.RESUME"
        const val ACTION_SPEAK = "com.vanta.app.voice.SPEAK"
        const val EXTRA_SPEECH = "com.vanta.app.voice.SPEECH"

        private const val CHANNEL_ID = "vanta_argon_voice"
        private const val NOTIFICATION_ID = 4401

        @Volatile
        internal var instance: AssistantForegroundService? = null
            private set

        fun speak(context: Context, text: String) {
            if (text.isBlank()) return
            val intent = Intent(context.applicationContext, AssistantForegroundService::class.java)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_SPEECH, text)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: RuntimeException) {
                // MainActivity can use its local TTS fallback if service startup is blocked.
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var pipeline: AudioPipelineManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private lateinit var overlay: OverlayViewManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        overlay = OverlayViewManager(this)
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasMicrophonePermission()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting local Hey Argon detector"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (_: RuntimeException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val activePipeline = ensurePipeline()
        when (action) {
            ACTION_PAUSE -> {
                activePipeline.requestPause(AudioStateManager.SPEAKING)
                serviceScope.launch { activePipeline.awaitPaused() }
            }
            ACTION_RECORDING -> {
                activePipeline.requestPause(AudioStateManager.RECORDING_USER)
                serviceScope.launch { activePipeline.awaitPaused() }
            }
            ACTION_PROCESSING -> activePipeline.markProcessing()
            ACTION_RESUME -> activePipeline.resumeListening()
            ACTION_SPEAK -> {
                val text = intent?.getStringExtra(EXTRA_SPEECH).orEmpty()
                serviceScope.launch {
                    activePipeline.pauseForSpeech()
                    withContext(Dispatchers.Main.immediate) { queueSpeech(text) }
                }
            }
            else -> activePipeline.startListening()
        }
        return START_STICKY
    }

    @Synchronized
    private fun ensurePipeline(): AudioPipelineManager {
        pipeline?.let { return it }
        return AudioPipelineManager(
            context = applicationContext,
            scope = serviceScope,
            onWakeWord = { onWakeWordDetected() },
            onStatus = { message -> updateNotification(message) }
        ).also { pipeline = it }
    }

    /** Synchronous microphone stop used by the UI immediately before ASR or any output begins. */
    @Synchronized
    internal fun pauseImmediately(targetState: AudioStateManager) {
        val active = ensurePipeline()
        active.requestPause(targetState)
        serviceScope.launch { active.awaitPaused() }
    }

    @Synchronized
    internal fun resumeImmediately() {
        ensurePipeline().resumeListening()
    }

    @Synchronized
    internal fun processingImmediately() {
        ensurePipeline().markProcessing()
    }

    private fun onWakeWordDetected() {
        overlay.show("Hey Argon — ready")
        updateNotification("Argon activated. Listening for your command.")
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            .putExtra("wake_query", "")
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            updateNotification("Argon detected. Open VANTA to give a command.")
            // Stay paused until VANTA is opened; do not resume into accidental capture.
        }
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            updateNotification("Android speech engine unavailable.")
            return
        }
        val store = VantaStore(this)
        val languageResult = engine.setLanguage(Locale.getDefault())
        ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setSpeechRate(store.loadVoiceSpeed())
        engine.setPitch(store.loadVoicePitch())
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                // Normal TTS completion is the only automatic TTS -> wake-listening transition.
                serviceScope.launch {
                    pipeline?.resumeAfterSpeech()
                    overlay.hide()
                    updateNotification("Listening locally for Hey Argon")
                }
            }

            override fun onError(utteranceId: String?) {
                updateNotification("Speech playback failed. Wake detection remains paused for safety.")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }
        })

        val pending = pendingSpeech
        if (ttsReady && !pending.isNullOrBlank()) {
            pendingSpeech = null
            queueSpeech(pending)
        } else if (!ttsReady) {
            updateNotification("The selected Android TTS language is unavailable.")
        }
    }

    private fun queueSpeech(text: String) {
        if (text.isBlank()) return
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            // Fail closed. Do not restart the listener from an error callback.
            updateNotification("Could not start speech playback. Wake detection remains paused.")
        } else {
            updateNotification("VANTA is speaking")
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VANTA · Hey Argon")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VANTA hands-free voice", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        pipeline?.close()
        pipeline = null
        overlay.hide()
        tts?.stop()
        tts?.shutdown()
        tts = null
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
