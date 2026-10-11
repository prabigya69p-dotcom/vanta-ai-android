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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/** Foreground wake listener. Keep one speech-recognition session armed and re-arm it after each result/error. */
class WakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var handlingWake = false
    private var awaitingCommand = false
    private var restartCount = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, "vanta_wake")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Argon hands-free • Vanta")
            .setContentText("Listening for “Hey Argon” only")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ArgonWakeListener").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra("pause", false) == true) {
            paused = true
            destroyRecognizer()
            return START_STICKY
        }
        if (intent?.getBooleanExtra("resume", false) == true) {
            paused = false
            if (running) scheduleListen(250)
            return START_STICKY
        }
        running = true
        paused = false
        handlingWake = false
        awaitingCommand = false
        scheduleListen(250)
        return START_STICKY
    }

    private fun scheduleListen(delayMs: Long) {
        handler.removeCallbacks(rearmRunnable)
        if (running && !paused && !handlingWake) handler.postDelayed(rearmRunnable, delayMs)
    }

    private val rearmRunnable = Runnable { listenForWake() }

    private fun listenForWake() {
        if (!running || paused || handlingWake) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            handler.postDelayed(rearmRunnable, 2500)
            return
        }
        destroyRecognizer()
        try {
            val current = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = current
            current.setRecognitionListener(object : RecognitionListener {
                private var triggered = false

                private fun inspect(texts: List<String>) {
                    if (triggered || handlingWake || paused || !running) return
                    if (awaitingCommand) {
                        val command=texts.asSequence().map{it.trim()}.firstOrNull{it.isNotBlank()}?:return
                        triggered=true;awaitingCommand=false;handlingWake=true;restartCount=0;destroyRecognizer()
                        handler.post {
                            if(ArgonAccessibilityService.handleWakeCommand(this@WakeService,command)){handlingWake=false;scheduleListen(900);return@post}
                            try{startActivity(Intent(this@WakeService,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("wake_query",command))}catch(_:Exception){}
                            handlingWake=false;scheduleListen(1200)
                        }
                        return
                    }
                    val match = texts.asSequence().mapNotNull { WakeMatcher.match(it) }.firstOrNull() ?: return
                    triggered = true
                    handlingWake = true
                    restartCount = 0
                    destroyRecognizer()
                    handler.post {
                        val query = match.query
                        if (query.isBlank()) { awaitingCommand = true; handlingWake = false; scheduleListen(350); return@post }
                        if (query.isNotBlank() && ArgonAccessibilityService.handleWakeCommand(this@WakeService, query)) {
                            handlingWake = false
                            scheduleListen(900)
                            return@post
                        }
                        try {
                            startActivity(Intent(this@WakeService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra("wake_query", query))
                            // Release the wake lockout. MainActivity pauses this service when it starts command capture/TTS.
                            handlingWake = false
                            scheduleListen(1200)
                        } catch (_: Exception) {
                            handlingWake = false
                            scheduleListen(1200)
                        }
                    }
                }

                override fun onResults(results: Bundle) {
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    inspect(texts)
                    if (!triggered) {
                        restartCount = 0
                        scheduleListen(250)
                    }
                }

                override fun onPartialResults(partialResults: Bundle) {
                    // Only act on a complete result; partials frequently contain false wake-word guesses.
                }

                override fun onError(error: Int) {
                    if (!triggered && !handlingWake) {
                        restartCount = (restartCount + 1).coerceAtMost(6)
                        scheduleListen((350L * restartCount).coerceAtMost(2200L))
                    }
                }
                override fun onReadyForSpeech(params: Bundle) { restartCount = 0 }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle) {}
            })
            current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            })
        } catch (_: Exception) {
            restartCount = (restartCount + 1).coerceAtMost(6)
            scheduleListen((500L * restartCount).coerceAtMost(3000L))
        }
    }

    private fun destroyRecognizer() {
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (_: Exception) {}
        try { old?.destroy() } catch (_: Exception) {}
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("vanta_wake", "VANTA hands-free", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        running = false
        paused = true
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, WakeService::class.java))
        fun pause(context: Context) {
            if (VantaStore(context).loadWakeEnabled()) context.startService(Intent(context, WakeService::class.java).putExtra("pause", true))
        }
        fun resume(context: Context) {
            if (VantaStore(context).loadWakeEnabled()) context.startService(Intent(context, WakeService::class.java).putExtra("resume", true))
        }
    }
}

data class WakeMatch(val query: String)

object WakeMatcher {
    private val wakeWords = setOf("argon", "argan")
    fun match(value: String): WakeMatch? {
        val normalized = value.lowercase(Locale.ROOT)
            .map { ch -> if (ch.isLetterOrDigit() || ch.isWhitespace()) ch else ' ' }
            .joinToString("").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val wakeIndex = when {
            normalized.firstOrNull() in wakeWords -> 1
            normalized.size >= 2 && normalized[0] == "hey" && normalized[1] in wakeWords -> 2
            else -> return null
        }
        return WakeMatch(normalized.drop(wakeIndex).joinToString(" "))
    }
}
