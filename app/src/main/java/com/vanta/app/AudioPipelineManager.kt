package com.vanta.app

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

enum class AudioStateManager {
    IDLE_LISTENING,
    RECORDING_USER,
    PROCESSING,
    SPEAKING
}

/** Dedicated local wake-word capture loop. General speech recognition is used only after detection. */
class AudioPipelineManager(
    context: Context,
    private val scope: CoroutineScope,
    private val onWakeWord: () -> Unit,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val state = AtomicReference(AudioStateManager.PROCESSING)
    private val lock = Any()

    @Volatile private var closed = false
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var listenJob: Job? = null

    fun currentState(): AudioStateManager = state.get()

    fun startListening() {
        if (closed || state.get() == AudioStateManager.SPEAKING) return
        synchronized(lock) {
            if (closed || listenJob?.isActive == true) return
            state.set(AudioStateManager.IDLE_LISTENING)
            listenJob = scope.launch(Dispatchers.IO) { listenLoop() }
        }
    }

    /** Immediately changes state and stops the active recorder before speech capture/output starts. */
    fun requestPause(nextState: AudioStateManager) {
        state.set(nextState)
        val active = synchronized(lock) { audioRecord }
        try {
            if (active?.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
        } catch (_: RuntimeException) {
        }
        synchronized(lock) { listenJob?.cancel() }
    }

    suspend fun awaitPaused() {
        val job = synchronized(lock) { listenJob }
        job?.cancelAndJoin()
        synchronized(lock) { if (listenJob === job) listenJob = null }
    }

    suspend fun pauseForSpeech() {
        requestPause(AudioStateManager.SPEAKING)
        awaitPaused()
    }

    suspend fun beginUserRecording() {
        requestPause(AudioStateManager.RECORDING_USER)
        awaitPaused()
    }

    fun markProcessing() = requestPause(AudioStateManager.PROCESSING)

    /** Explicit recovery after failed ASR or completed non-TTS playback.
     * Wait for a cancelled capture job to release AudioRecord before starting the next one.
     */
    fun resumeListening() {
        if (closed) return
        state.set(AudioStateManager.PROCESSING)
        synchronized(lock) { listenJob?.cancel() }
        scope.launch(Dispatchers.IO) {
            awaitPaused()
            if (closed) return@launch
            state.set(AudioStateManager.IDLE_LISTENING)
            startListening()
        }
    }

    /** The automatic TTS resume path: only onDone should call this method. */
    fun resumeAfterSpeech() {
        if (closed || !state.compareAndSet(AudioStateManager.SPEAKING, AudioStateManager.IDLE_LISTENING)) return
        startListening()
    }

    private suspend fun listenLoop() {
        var localRecord: AudioRecord? = null
        var detected = false
        try {
            val engine = getOrCreatePorcupine()
            if (closed || state.get() != AudioStateManager.IDLE_LISTENING) return

            val rate = engine.sampleRate
            val frameLength = engine.frameLength
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IOException("The microphone doesn't support Porcupine's PCM format.")

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameLength * 2 * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IOException("Android could not initialize the microphone.")
            }
            localRecord = record
            synchronized(lock) { audioRecord = record }
            attachEffects(record)
            record.startRecording()
            withContext(Dispatchers.Main.immediate) { onStatus("Listening locally for Hey Argon") }

            val frame = ShortArray(frameLength)
            while (currentCoroutineContext().isActive && !closed &&
                state.get() == AudioStateManager.IDLE_LISTENING
            ) {
                var offset = 0
                while (offset < frame.size && currentCoroutineContext().isActive && !closed &&
                    state.get() == AudioStateManager.IDLE_LISTENING
                ) {
                    val count = record.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        offset += count
                    } else if (count == AudioRecord.ERROR_DEAD_OBJECT ||
                        count == AudioRecord.ERROR_INVALID_OPERATION ||
                        count == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        throw IOException("Microphone stream stopped (AudioRecord error $count).")
                    }
                }
                if (offset != frame.size) continue

                if (engine.process(frame) >= 0 &&
                    state.compareAndSet(AudioStateManager.IDLE_LISTENING, AudioStateManager.PROCESSING)
                ) {
                    // Leave the loop immediately; finally stops/releases AudioRecord before the callback.
                    detected = true
                    break
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed && state.get() != AudioStateManager.SPEAKING &&
                state.get() != AudioStateManager.RECORDING_USER
            ) {
                state.set(AudioStateManager.PROCESSING)
                val detail = error.message?.take(180) ?: "Unknown wake detector error"
                withContext(Dispatchers.Main.immediate) {
                    onStatus("Hey Argon detector unavailable: $detail")
                }
            }
        } finally {
            localRecord?.let { releaseCapture(it) }
        }

        if (detected && !closed) {
            withContext(Dispatchers.Main.immediate) {
                onStatus("Argon heard you — opening VANTA")
                onWakeWord()
            }
        }
    }

    private fun getOrCreatePorcupine(): Porcupine {
        synchronized(lock) { porcupine?.let { return it } }

        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY.trim()
        if (accessKey.isBlank()) {
            throw IllegalStateException("PICOVOICE_ACCESS_KEY is missing from GitHub Actions secrets.")
        }

        val model = File(appContext.filesDir, "wake/hey_argon_android.ppn")
        if (!model.isFile || model.length() < 32L) {
            model.parentFile?.mkdirs()
            model.delete()
            onStatus("Preparing Hey Argon detector for first use")
            Porcupine.trainWakeWordFromPhrase(accessKey, model.absolutePath, "en", "Hey Argon")
        }
        if (!model.isFile || model.length() < 32L) {
            throw IOException("Picovoice did not create the Hey Argon model.")
        }

        val created = Porcupine.Builder()
            .setAccessKey(accessKey)
            .setKeywordPath(model.absolutePath)
            .setSensitivity(0.58f)
            .build(appContext)

        synchronized(lock) {
            if (closed) {
                created.delete()
                throw CancellationException("Voice service is stopping")
            }
            porcupine?.let {
                created.delete()
                return it
            }
            porcupine = created
        }
        return created
    }

    private fun attachEffects(record: AudioRecord) {
        val sessionId = record.audioSessionId
        if (sessionId <= 0) return
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
    }

    private fun releaseCapture(record: AudioRecord) {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } catch (_: RuntimeException) {
        }
        val effects = synchronized(lock) {
            if (audioRecord === record) audioRecord = null
            val result = echoCanceler to noiseSuppressor
            echoCanceler = null
            noiseSuppressor = null
            result
        }
        runCatching { effects.first?.enabled = false }
        runCatching { effects.first?.release() }
        runCatching { effects.second?.enabled = false }
        runCatching { effects.second?.release() }
        runCatching { record.release() }
    }

    override fun close() {
        if (closed) return
        closed = true
        requestPause(AudioStateManager.SPEAKING)
        val job = synchronized(lock) { listenJob }
        job?.cancel()
        val engine = synchronized(lock) {
            val value = porcupine
            porcupine = null
            value
        }
        runCatching { engine?.delete() }
    }
}
