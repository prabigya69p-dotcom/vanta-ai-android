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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import java.util.Locale

class WakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var gateRecord: AudioRecord? = null
    private var gateJob: kotlinx.coroutines.Job? = null
    private var running = false
    private var listeningForWake = false
    private var paused = false
    private var lastTriggerAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, "vanta_wake")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VANTA high-confidence hands-free")
            .setContentText("Adaptive voice gate is active while enabled.")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            11,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.getBooleanExtra("pause", false) == true) {
            paused = true
            stopWakeCapture()
            return START_STICKY
        }
        if (intent?.getBooleanExtra("resume", false) == true) {
            paused = false
            if (running) startNoiseGate()
            return START_STICKY
        }

        running = true
        paused = false
        startNoiseGate()
        return START_STICKY
    }

    private fun startNoiseGate() {
        if (!running || paused || listeningForWake || gateJob?.isActive == true) return

        gateJob = scope.launch(Dispatchers.Default) {
            val record = createGateRecord() ?: run {
                delay(1200)
                gateJob = null
                if (running && !paused) startNoiseGate()
                return@launch
            }
            gateRecord = record
            val bufferSize = minOf(record.bufferSize, 4096).coerceAtLeast(1024)
            val buffer = ShortArray(bufferSize)
            var noiseDb = -55.0
            var hotFrames = 0
            var calibratedFrames = 0

            try {
                record.startRecording()
                while (isActive && running && !paused && !listeningForWake) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue

                    var energy = 0.0
                    for (i in 0 until read) {
                        val v = buffer[i].toDouble() / 32768.0
                        energy += v * v
                    }
                    val rms = kotlin.math.sqrt(energy / read.coerceAtLeast(1))
                    val db = 20.0 * log10(rms.coerceAtLeast(1e-6))

                    calibratedFrames++
                    if (calibratedFrames < 35) {
                        noiseDb = noiseDb * 0.80 + db * 0.20
                        continue
                    }

                    noiseDb = noiseDb * 0.985 + db * 0.015
                    val threshold = maxOf(-43.0, noiseDb + 11.0)
                    if (db > threshold) {
                        hotFrames++
                    } else {
                        hotFrames = maxOf(0, hotFrames - 2)
                    }

                    if (hotFrames >= 5 && System.currentTimeMillis() - lastTriggerAt > 1800L) {
                        lastTriggerAt = System.currentTimeMillis()
                        listeningForWake = true
                        releaseGate(record)
                        gateRecord = null
                        mainHandler.post { listenForWake() }
                        break
                    }
                }
            } catch (_: Throwable) {
                releaseGate(record)
                gateRecord = null
            } finally {
                releaseGate(record)
                if (gateRecord === record) gateRecord = null
                gateJob = null
            }
        }
    }

    private fun createGateRecord(): AudioRecord? = runCatching {
        val sampleRate = 16000
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return@runCatching null

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes((min * 2).coerceAtLeast(4096))
            .build()

        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.enabled = true
        }
        record
    }.getOrNull()

    private fun listenForWake() {
        if (!running || paused || !SpeechRecognizer.isRecognitionAvailable(this)) {
            finishWakeCycle()
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            var triggered = false

            fun tryTrigger(raw: String) {
                if (triggered) return
                val match = WakeMatcher.match(raw) ?: return
                triggered = true
                val query = match.query
                mainHandler.post {
                    startActivity(
                        Intent(this@WakeService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("wake_query", query)
                    )
                }
                finishWakeCycle()
            }

            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().forEach(::tryTrigger)
                    if (!triggered) finishWakeCycle()
                }

                override fun onPartialResults(partialResults: Bundle) {
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().forEach(::tryTrigger)
                }

                override fun onError(error: Int) {
                    finishWakeCycle()
                }

                override fun onReadyForSpeech(params: Bundle) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle) {}
            })

            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 850L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            })
        }
    }

    private fun finishWakeCycle() {
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
            listeningForWake = false
            if (running && !paused) {
                scope.launch {
                    delay(120)
                    startNoiseGate()
                }
            }
        }
    }

    private fun stopWakeCapture() {
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
            listeningForWake = false
        }
        releaseGate(gateRecord)
        gateRecord = null
        gateJob?.cancel()
        gateJob = null
    }

    private fun releaseGate(record: AudioRecord?) {
        runCatching { record?.stop() }
        runCatching { record?.release() }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("vanta_wake", "VANTA hands-free", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        running = false
        paused = true
        stopWakeCapture()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }

        fun pause(context: Context) {
            if (!VantaStore(context).loadWakeEnabled()) return
            context.startService(Intent(context, WakeService::class.java).putExtra("pause", true))
        }

        fun resume(context: Context) {
            if (!VantaStore(context).loadWakeEnabled()) return
            context.startService(Intent(context, WakeService::class.java).putExtra("resume", true))
        }
    }
}

data class WakeMatch(val query: String)

object WakeMatcher {
    private val wakePattern = Regex("\b(?:hey|a)\s+(?:vanta|venta|venda|vantah|vanda)\b", RegexOption.IGNORE_CASE)

    fun match(value: String): WakeMatch? {
        val clean = normalize(value)
        val match = wakePattern.find(clean) ?: return null
        val query = clean.removeRange(match.range).trim().trimStart(',', ':', '-', '.')
        return WakeMatch(query)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.getDefault())
        .replace(Regex("[^\p{L}\p{N}\s,:.-]"), " ")
        .replace(Regex("\s+"), " ")
        .trim()
}
