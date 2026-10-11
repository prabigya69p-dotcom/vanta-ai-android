package com.vanta.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Base64
import java.util.Locale

data class VantaAttachment(
    val uri: Uri? = null,
    val bitmap: Bitmap? = null,
    val name: String = "Photo"
)

data class ChatMessage(
    val user: Boolean,
    val text: String,
    val attachments: List<VantaAttachment> = emptyList(),
    val generatedImageDataUrl: String? = null,
    val sources: List<VantaSource> = emptyList()
)

class MainActivity : ComponentActivity() {
    private lateinit var store: VantaStore
    private lateinit var memory: VantaMemoryStore
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var fishPlayer: MediaPlayer? = null
    private val incomingImage = mutableStateOf<Uri?>(null)
    private val incomingWakeQuery = mutableStateOf<String?>(null)

    private var pendingCameraAction: (() -> Unit)? = null
    private var pendingSpeechResult: ((String) -> Unit)? = null
    private var pendingSpeechFailure: ((String) -> Unit)? = null
    private var speechSessionId = 0
    private var googleConnectCallback: ((String) -> Unit)? = null
    private val googleAuthorizationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val callback = googleConnectCallback; googleConnectCallback = null
        if (result.resultCode != RESULT_OK) callback?.invoke("Google authorization was cancelled.") else {
            val auth = runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data) }.getOrNull()
            val token = auth?.accessToken.orEmpty()
            val saved = token.isNotBlank() && runCatching { store.saveGoogleAccessToken(token) }.getOrDefault(false)
            callback?.invoke(if (saved) "Google Workspace connected. Reconnect if access expires." else "Google did not return an access token. Try again.")
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = pendingSpeechResult
        val failure = pendingSpeechFailure
        pendingSpeechResult = null
        pendingSpeechFailure = null
        if (granted && callback != null) {
            WakeService.pause(this@MainActivity)
            startListeningInternal(callback, failure ?: {})
        } else {
            WakeService.resume(this@MainActivity)
            if (!granted) failure?.invoke("Microphone permission is required to use voice input.")
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCameraAction?.invoke()
        pendingCameraAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VantaStore(this)
        memory = VantaMemoryStore(this)
        handleIncomingIntent(intent)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) applyTtsSettings()
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { WakeService.pause(this@MainActivity) }
                override fun onDone(utteranceId: String?) { WakeService.resume(this@MainActivity) }
                override fun onError(utteranceId: String?) { WakeService.resume(this@MainActivity) }
            })
        }
        setContent { VantaApp(store, memory, this, incomingImage, incomingWakeQuery) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { incomingImage.value = it }
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" }?.let { incomingImage.value = it }
        }
        incomingWakeQuery.value = if (intent.hasExtra("wake_query")) intent.getStringExtra("wake_query").orEmpty() else null
    }

    fun connectGoogleWorkspace(onStatus: (String) -> Unit) {
        googleConnectCallback = onStatus
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.readonly"), Scope("https://www.googleapis.com/auth/calendar.readonly"))).build()
        Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener { result ->
            if (result.hasResolution()) {
                val pending = result.pendingIntent
                if (pending == null) { googleConnectCallback = null; onStatus("Google authorization prompt was not returned.") }
                else runCatching { googleAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }.onFailure { googleConnectCallback = null; onStatus("Could not open Google authorization: "+(it.message ?: "unknown error")) }
            } else {
                val token=result.accessToken.orEmpty();val saved=token.isNotBlank()&&runCatching{store.saveGoogleAccessToken(token)}.getOrDefault(false)
                googleConnectCallback=null;onStatus(if(saved)"Google Workspace connected. Reconnect if access expires." else "Google did not return an access token. Try again.")
            }
        }.addOnFailureListener { error -> googleConnectCallback=null;onStatus("Google connection failed: "+(error.message ?: "authorization error")) }
    }

    fun runAccessibilityVoiceCommand(command: String): String? =
        ArgonAccessibilityService.executeVoiceCommand(this, command)

    private fun applyTtsSettings() {
        tts?.language = Locale.getDefault()
        tts?.setSpeechRate(store.loadVoiceSpeed())
        tts?.setPitch(store.loadVoicePitch())
    }

    fun speak(text: String) {
        stopListening()
        val fishKey = store.loadFishAudioApiKey()
        val fishReference = store.loadFishAudioReferenceId()
        if (fishKey.isNotBlank()) {
            WakeService.pause(this)
            Thread {
                val audio = FishAudioApi.synthesize(this, fishKey, fishReference, text)
                runOnUiThread {
                    if (audio == null) {
                        WakeService.resume(this)
                        applyTtsSettings()
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vanta_reply")
                    } else {
                        fishPlayer?.release()
                        fishPlayer = MediaPlayer().apply {
                            setDataSource(audio.absolutePath)
                            setOnCompletionListener {
                                release()
                                fishPlayer = null
                                audio.delete()
                                WakeService.resume(this@MainActivity)
                            }
                            setOnErrorListener { player, _, _ ->
                                player.release()
                                fishPlayer = null
                                audio.delete()
                                WakeService.resume(this@MainActivity)
                                applyTtsSettings()
                                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vanta_reply_fallback")
                                true
                            }
                            prepare()
                            start()
                        }
                    }
                }
            }.start()
            return
        }
        applyTtsSettings()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vanta_reply")
    }

    fun listen(onText: (String) -> Unit) {
        listen(onText, {})
    }

    fun listen(onText: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            WakeService.resume(this)
            onFailure("Speech recognition isn't available on this device.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSpeechResult = onText
            pendingSpeechFailure = onFailure
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopSpeaking()
        WakeService.pause(this)
        startListeningInternal(onText, onFailure)
    }

    private fun startListeningInternal(
        onText: (String) -> Unit,
        onFailure: (String) -> Unit = {}
    ) {
        // Invalidate the previous listener before cancelling it; cancellation can trigger a late callback.
        val sessionId = ++speechSessionId
        val previousRecognizer = recognizer
        recognizer = null
        try { previousRecognizer?.cancel() } catch (_: RuntimeException) { }
        try { previousRecognizer?.destroy() } catch (_: RuntimeException) { }

        val currentRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: RuntimeException) {
            WakeService.resume(this)
            onFailure("Couldn't start speech recognition. Try again.")
            return
        }
        recognizer = currentRecognizer
        var completed = false
        var latestPartial: String? = null

        fun finish(transcript: String?, failureMessage: String? = null) {
            if (completed || sessionId != speechSessionId) return
            completed = true
            if (recognizer === currentRecognizer) recognizer = null
            try { currentRecognizer.destroy() } catch (_: RuntimeException) { }
            WakeService.resume(this@MainActivity)

            val cleanedTranscript = transcript?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanedTranscript != null) {
                onText(cleanedTranscript)
            } else {
                onFailure(failureMessage ?: "I didn't catch that. Please try again.")
            }
        }

        currentRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val finalTranscript = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                // Some services return an empty final list after emitting a useful partial transcript.
                finish(finalTranscript ?: latestPartial, "I didn't catch that. Please try again.")
            }

            override fun onError(error: Int) {
                if (sessionId != speechSessionId) return
                val message = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out. Check your connection and try again."
                    SpeechRecognizer.ERROR_NETWORK -> "Speech recognition couldn't connect. Check your internet connection."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone audio couldn't be captured. Check microphone access."
                    SpeechRecognizer.ERROR_SERVER -> "The speech service failed. Please try again."
                    SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was interrupted. Please try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Please try again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again in a moment."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required to use voice input."
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many speech requests. Wait a moment and try again."
                    else -> "Speech recognition failed. Please try again."
                }
                finish(null, message)
            }

            override fun onPartialResults(partialResults: Bundle) {
                if (sessionId != speechSessionId) return
                latestPartial = partialResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }

            override fun onReadyForSpeech(params: Bundle) { }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray) { }
            override fun onEndOfSpeech() { }
            override fun onEvent(eventType: Int, params: Bundle) { }
        })

        try {
            currentRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            })
        } catch (_: RuntimeException) {
            finish(null, "Couldn't start speech recognition. Try again.")
        }
    }

    private fun stopListening() {
        // Ignore callbacks from a cancelled session so they cannot affect the next command.
        speechSessionId++
        val activeRecognizer = recognizer
        recognizer = null
        try { activeRecognizer?.cancel() } catch (_: RuntimeException) { }
        try { activeRecognizer?.destroy() } catch (_: RuntimeException) { }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun cameraPermissionGranted(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun requestCameraPermission(onGranted: () -> Unit) {
        if (cameraPermissionGranted()) {
            onGranted()
        } else {
            pendingCameraAction = onGranted
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        stopListening()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun VantaApp(
    store: VantaStore,
    memory: VantaMemoryStore,
    activity: MainActivity,
    incomingImage: MutableState<Uri?>,
    incomingWakeQuery: MutableState<String?>
) {
    var settings by remember { mutableStateOf(false) }
    var codeDevMode by remember { mutableStateOf(false) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF080F1A),
            surface = Color(0xFF0F1B2A),
            primary = Color(0xFF4D9FE3),
            secondary = Color(0xFF8EB5DC),
            onSurface = Color.White,
            onBackground = Color.White
        )
    ) {
        if (settings) {
            SettingsScreen(store, memory, activity) { settings = false }
        } else {
            ChatScreen(store, memory, activity, incomingImage, incomingWakeQuery, codeDevMode, { codeDevMode = it }) { settings = true }
        }
    }
}

@Composable
private fun ChatScreen(
    store: VantaStore,
    memory: VantaMemoryStore,
    activity: MainActivity,
    incomingImage: MutableState<Uri?>,
    incomingWakeQuery: MutableState<String?>,
    codeDevMode: Boolean,
    onCodeDevModeChanged: (Boolean) -> Unit,
    openSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VantaMode.SMART) }
    var modeMenu by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(listOf<VantaAttachment>()) }
    var deepResearch by remember { mutableStateOf(false) }
    var activityStatus by remember { mutableStateOf("Ready") }
    var messages by remember {
        mutableStateOf(
            store.loadChat().map { ChatMessage(it.user, it.text) }.ifEmpty {
                listOf(ChatMessage(false, "Hey — I'm Argon, your AI inside Vanta. What can I do for you?"))
            }
        )
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { attachments = attachments + VantaAttachment(uri = it, name = "Photo") }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val next = uris.map { VantaAttachment(uri = it, name = displayName(context, it)) }
        attachments = (attachments + next).take(8)
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) attachments = attachments + VantaAttachment(bitmap = bitmap, name = "Camera")
    }

    LaunchedEffect(incomingImage.value) {
        incomingImage.value?.let {
            attachments = attachments + VantaAttachment(uri = it, name = "Shared image")
            incomingImage.value = null
        }
    }

    fun persist() {
        store.saveChat(messages.map { SavedChatMessage(it.user, it.text) })
    }

    fun addAssistant(text: String, sources: List<VantaSource> = emptyList(), generated: String? = null) {
        messages = messages + ChatMessage(false, text, generatedImageDataUrl = generated, sources = sources)
        persist()
    }

    fun send() {
        val prompt = input.trim()
        val currentAttachments = attachments
        if ((prompt.isEmpty() && currentAttachments.isEmpty()) || busy) return

        if (codeDevMode) {
            if (currentAttachments.isNotEmpty()) {
                addAssistant("Code Dev Mode currently accepts text tasks only. Remove attachments and describe the code change.")
                return
            }
            if (prompt.isBlank()) return
            val githubToken = store.loadGitHubToken()
            if (githubToken.isBlank()) {
                addAssistant("Connect GitHub first in VANTA Settings → Code Dev Mode. Your token is stored encrypted on this device.")
                openSettings()
                return
            }
            messages = messages + ChatMessage(true, prompt)
            input = ""
            attachments = emptyList()
            persist()
            busy = true
            activityStatus = "Submitting code task"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { CodeDevClient.dispatch(githubToken, prompt) }
                    val result = "Code Dev task submitted. GitHub Actions will generate the patch, run tests and build an APK. Validated changes are published as a separate pull request; they do not silently replace this installed app."
                    addAssistant(result, sources = listOf(VantaSource("View Code Dev runs", "https://github.com/prabigya69p-dotcom/vanta-ai-android/actions")))
                    activity.speak("Code Dev task submitted. I will build and test a separate code update.")
                } catch (t: Throwable) {
                    val message = "Code Dev could not start: " + (t.message ?: "GitHub request failed")
                    addAssistant(message)
                    activity.speak("Code Dev could not start. Check the GitHub connection in settings.")
                } finally {
                    busy = false
                    activityStatus = "Ready"
                }
            }
            return
        }

        val text = prompt.ifEmpty { "Analyze these attachments." }
        val detectedMode = VantaSmart.modeFor(text, mode)
        if (currentAttachments.isEmpty() && VantaIntegrations.isDataCommand(text)) {
            messages = messages + ChatMessage(true, text, attachments = currentAttachments)
            input = ""
            attachments = emptyList()
            busy = true
            activityStatus = "Connecting to your account..."
            scope.launch {
                try {
                    val reply = withContext(Dispatchers.IO) { VantaIntegrations.execute(context, text) }
                    messages = messages + ChatMessage(false, reply)
                    persist()
                    activity.speak(reply)
                } catch (t: Throwable) {
                    val reply = "Integration error: " + (t.message ?: "request failed")
                    messages = messages + ChatMessage(false, reply)
                    persist()
                    activity.speak(reply)
                } finally { busy = false; activityStatus = "Ready" }
            }
            return
        }
        val local = if (currentAttachments.isEmpty()) VantaLocalCommandRouter.execute(context, text, memory) else null
        val integrationCommand = currentAttachments.isEmpty() && WorkspaceCommandRouter.isCommand(text)
        val route = VantaRouter.decide(
            prompt = text,
            selectedMode = detectedMode,
            hasAttachments = currentAttachments.isNotEmpty(),
            deepResearchRequested = deepResearch,
            hasLocalAction = local != null || integrationCommand
        )
        activityStatus = VantaRouter.status(route)

        messages = messages + ChatMessage(true, text, attachments = currentAttachments)
        input = ""
        attachments = emptyList()

        if (route == VantaRoute.ANDROID_ACTION && local != null) {
            addAssistant(local.reply)
            if (local.shouldOpenSettings) openSettings()
            activityStatus = "Ready"
            activity.speak(local.reply)
            return
        }
        if (route == VantaRoute.ANDROID_ACTION && integrationCommand) {
            busy = true; activityStatus = "Checking connected accounts"
            scope.launch {
                try { val reply=withContext(Dispatchers.IO){WorkspaceCommandRouter.execute(store,text)};addAssistant(reply);activity.speak(reply) }
                catch(error:Throwable){val reply=error.message ?: "Workspace integration failed. Check Settings.";addAssistant(reply);activity.speak(reply)}
                finally {busy=false;activityStatus="Ready"}
            }
            return
        }

        busy = true
        scope.launch {
            try {
                if (
                    detectedMode == VantaMode.CREATE &&
                    listOf("generate image", "create an image", "make an image", "generate a wallpaper", "create wallpaper")
                        .any { text.lowercase(Locale.getDefault()).contains(it) }
                ) {
                    val generated = withContext(Dispatchers.IO) {
                        VantaImageApi(store.loadApiKey()).generate(text)
                    }
                    addAssistant("Image generated.", generated = generated)
                    activity.speak("Image generated.")
                    busy = false
                    return@launch
                }

                val imageDataUrls = withContext(Dispatchers.IO) {
                    currentAttachments.flatMap { attachmentToDataUrls(context, it) }
                }.take(8)
                val attachmentContext = withContext(Dispatchers.IO) {
                    currentAttachments.mapNotNull { attachmentContextText(context, it) }.joinToString(" ")
                }
                val requestText = if (attachmentContext.isBlank()) text
                    else text + " ATTACHMENT CONTEXT: " + attachmentContext

                val history = messages.dropLast(1).takeLast(10).map { SavedChatMessage(it.user, it.text) }
                val instructions = VantaSmart.instructions(
                    detectedMode,
                    store.loadPersonality(),
                    store.loadProject(),
                    memory.list()
                )
                val api = VantaApi(store.loadApiKey(), store.loadModel())

                when (route) {
                    VantaRoute.DEEP_RESEARCH -> {
                        val result = withContext(Dispatchers.IO) {
                            VantaDeepResearch.run(api, requestText, instructions, history)
                        }
                        messages = messages + ChatMessage(false, result.text, sources = result.sources)
                        persist()
                        activity.speak(result.text)
                    }
                    VantaRoute.RESEARCH -> {
                        val reply = withContext(Dispatchers.IO) {
                            api.ask(requestText, instructions, true, imageDataUrls, history)
                        }
                        messages = messages + ChatMessage(false, reply.text, sources = reply.sources)
                        persist()
                        activity.speak(reply.text)
                    }
                    VantaRoute.ATTACHMENT_ANALYSIS, VantaRoute.CHAT -> {
                        var streamed = ""
                        messages = messages + ChatMessage(false, "")
                        val reply = withContext(Dispatchers.IO) {
                            api.askStreaming(requestText, instructions, false, imageDataUrls, history) { delta ->
                                streamed += delta
                                activity.runOnUiThread {
                                    messages = messages.dropLast(1) + ChatMessage(false, streamed)
                                }
                            }
                        }
                        messages = messages.dropLast(1) + ChatMessage(false, reply.text)
                        persist()
                        activity.speak(reply.text)
                    }
                    VantaRoute.ANDROID_ACTION -> Unit
                }
                deepResearch = false
            } catch (t: Throwable) {
                val error = "VANTA error: " + (t.message ?: "Unknown error")
                messages = messages + ChatMessage(false, error)
                persist()
                activityStatus = "Error"
                activity.speak(error)
            } finally {
                busy = false
                activityStatus = "Ready"
            }
        }
    }

    LaunchedEffect(incomingWakeQuery.value) {
        val wakeQuery = incomingWakeQuery.value ?: return@LaunchedEffect
        incomingWakeQuery.value = null
        when (wakeQuery.trim().lowercase(Locale.ROOT)) {
            "enter code dev mode", "start code dev mode", "code dev mode" -> {
                onCodeDevModeChanged(true)
                activity.speak("Code Dev Mode is on. Tell me the code change you want.")
                return@LaunchedEffect
            }
            "exit code dev mode", "stop code dev mode", "normal mode" -> {
                onCodeDevModeChanged(false)
                activity.speak("Code Dev Mode is off.")
                return@LaunchedEffect
            }
        }
        activity.runAccessibilityVoiceCommand(wakeQuery)?.let { result ->
            activityStatus = result
            activity.speak(result)
            return@LaunchedEffect
        }
        if (wakeQuery.isBlank()) {
            activityStatus = "Listening for your command…"
            activity.listen(
                onText = { spoken ->
                    input = spoken.trim()
                    if (input.isNotBlank()) send()
                },
                onFailure = { failure ->
                    activityStatus = failure
                    messages = messages + ChatMessage(false, failure)
                    persist()
                }
            )
        } else {
            input = wakeQuery.trim()
            send()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF080F1A))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("VANTA", fontSize = 28.sp, color = Color.White)
                Text("ADAPTIVE AI • SMART HUD", fontSize = 12.sp, color = Color(0xFF8EB5DC))
            }
            Surface(shape = CircleShape, color = Color(0xFF173A5C)) {
                Text("●", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color(0xFF71C2FF))
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = openSettings) {
                Text("⚙", fontSize = 22.sp, color = Color.White)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onCodeDevModeChanged(!codeDevMode) },
                label = { Text(if (codeDevMode) "Code Dev Mode ✓" else "Code Dev Mode") }
            )
            if (codeDevMode) Text("Changes are built on a separate branch", color = Color(0xFF8EB5DC), fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mode:", color = Color(0xFF8EB5DC), fontSize = 12.sp)
            Box {
                TextButton(onClick = { modeMenu = true }) { Text(mode.label, color = Color.White) }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    VantaMode.entries.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = { mode = item; modeMenu = false }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Space: " + store.loadProject(), color = Color(0xFF6F8BA8), fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            AssistChip(onClick = { mode = VantaMode.MISSION }, label = { Text("Mission") })
            AssistChip(onClick = { mode = VantaMode.STUDY }, label = { Text("Study") })
            AssistChip(onClick = { mode = VantaMode.CREATE }, label = { Text("Create") })
            AssistChip(onClick = { mode = VantaMode.DEVELOPER }, label = { Text("Dev") })
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (message.user) Color(0xFF173A5C) else Color(0xFF102238),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.widthIn(max = 350.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            message.attachments.forEach { attachment ->
                                AttachmentPreview(context, attachment)
                                Spacer(Modifier.height(7.dp))
                            }

                            message.generatedImageDataUrl?.let {
                                GeneratedImage(it)
                                Spacer(Modifier.height(8.dp))
                            }

                            if (message.text.isNotBlank()) Text(message.text, color = Color.White)

                            if (message.sources.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("SOURCES", color = Color(0xFF8EB5DC), fontSize = 10.sp)
                                message.sources.forEach { source ->
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                                            }
                                        },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(source.title.take(64), maxLines = 1, color = Color(0xFF71C2FF))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (busy) item { Text("VANTA • " + activityStatus + "…", color = Color(0xFF8EB5DC), fontSize = 12.sp) }
        }

        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.take(8).forEachIndexed { index, attachment ->
                    Surface(color = Color(0xFF102238), shape = RoundedCornerShape(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(attachment.name.take(16), color = Color.White, fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = { attachments = attachments.filterIndexed { i, _ -> i != index } },
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("×") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { imagePicker.launch("image/*") }) { Text("+ Photo") }
            TextButton(onClick = {
                if (activity.cameraPermissionGranted()) cameraPicker.launch(null)
                else activity.requestCameraPermission { cameraPicker.launch(null) }
            }) { Text("Camera") }
            TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Files") }
            TextButton(onClick = { mode = VantaMode.FOCUS }) { Text("Focus") }
            AssistChip(
                onClick = { deepResearch = !deepResearch },
                label = { Text(if (deepResearch) "Deep ✓" else "Deep Research") }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Argon") },
                maxLines = 4
            )
            FilledIconButton(onClick = {
                activity.listen(
                    onText = { spoken ->
                        input = spoken.trim()
                        if (input.isNotBlank()) send()
                    },
                    onFailure = { failure ->
                        activityStatus = failure
                        messages = messages + ChatMessage(false, failure)
                        persist()
                    }
                )
            }, shape = CircleShape) {
                Text("🎙")
            }
            FilledIconButton(onClick = ::send, shape = CircleShape, enabled = !busy) {
                Text("➤")
            }
        }

        Spacer(Modifier.height(3.dp))
        Text(
            "Smart routing • memory • research • vision • creation • voice • local actions",
            color = Color(0xFF5F7891),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun AttachmentPreview(context: Context, attachment: VantaAttachment) {
    val bitmapState = remember(attachment) { mutableStateOf(attachment.bitmap) }
    LaunchedEffect(attachment) {
        if (bitmapState.value == null && attachment.uri != null) {
            bitmapState.value = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(attachment.uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull() ?: renderPdfFirstPage(context, attachment.uri)
            }
        }
    }
    bitmapState.value?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = attachment.name,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun GeneratedImage(data: String) {
    val bitmap = remember(data) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(data) {
        bitmap.value = withContext(Dispatchers.IO) { decodeImageReference(data) }
    }
    bitmap.value?.let {
        Image(
            it.asImageBitmap(),
            "Generated image",
            Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun SettingsScreen(
    store: VantaStore,
    memory: VantaMemoryStore,
    activity: MainActivity,
    onBack: () -> Unit
) {
    var model by remember { mutableStateOf(store.loadModel()) }
    var personality by remember { mutableStateOf(store.loadPersonality()) }
    var project by remember { mutableStateOf(store.loadProject()) }
    var speed by remember { mutableFloatStateOf(store.loadVoiceSpeed()) }
    var pitch by remember { mutableFloatStateOf(store.loadVoicePitch()) }
    var temporary by remember { mutableStateOf(store.loadTemporaryChat()) }
    var wake by remember { mutableStateOf(store.loadWakeEnabled()) }
    var saveStatus by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var notionToken by remember { mutableStateOf("") }
    var notionDatabaseId by remember { mutableStateOf(store.loadNotionDatabaseId()) }
    var notionTask by remember { mutableStateOf("") }
    var integrationBusy by remember { mutableStateOf(false) }
    val integrationScope = rememberCoroutineScope()
    var notionToken by remember { mutableStateOf("") }
    var notionDatabaseId by remember { mutableStateOf(store.loadNotionDatabaseId()) }
    val context = LocalContext.current
    val wakePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        wake = granted
        store.saveWakeEnabled(granted)
        if (granted) WakeService.start(context) else WakeService.stop(context)
        saveStatus = if (granted) "Argon hands-free enabled" else "Microphone permission is required for Argon hands-free"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF080F1A))
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("VANTA Settings", fontSize = 25.sp, color = Color.White)
        Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(12.dp))
        Text("Model", color = Color(0xFF8EB5DC))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true)

        Spacer(Modifier.height(12.dp))
        Text("Personality", color = Color(0xFF8EB5DC))
        OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth(), minLines = 2)

        Spacer(Modifier.height(12.dp))
        Text("Project / Space", color = Color(0xFF8EB5DC))
        OutlinedTextField(project, { project = it }, Modifier.fillMaxWidth(), singleLine = true)

        Spacer(Modifier.height(14.dp))
        Text("Voice speed: " + "%.2f".format(speed), color = Color.White, fontSize = 12.sp)
        Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.65f..1.5f)

        Text("Voice pitch: " + "%.2f".format(pitch), color = Color.White, fontSize = 12.sp)
        Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.75f..1.3f)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Temporary chat", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = temporary, onCheckedChange = { temporary = it })
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Hey Argon hands-free", color = Color.White)
                Text("Say “Hey Argon” followed by a command. Random sounds should not start a chat.", color = Color(0xFF6F8BA8), fontSize = 11.sp)
            }
            Switch(
                checked = wake,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        wake = false
                        store.saveWakeEnabled(false)
                        WakeService.stop(context)
                        saveStatus = "Argon hands-free disabled"
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        wake = true
                        store.saveWakeEnabled(true)
                        WakeService.start(context)
                        saveStatus = "Argon hands-free enabled; keep its notification active"
                    } else {
                        wakePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("Saved memory (" + memory.list().size + "/30)", color = Color(0xFF8EB5DC), fontSize = 12.sp)
        memory.list().takeLast(6).forEach { Text("• " + it, color = Color(0xFFD5E5F5), fontSize = 12.sp) }

        Spacer(Modifier.height(18.dp))
        Button(onClick = {
            store.saveModel(model)
            store.savePersonality(personality)
            store.saveProject(project)
            store.saveVoiceSpeed(speed)
            store.saveVoicePitch(pitch)
            store.saveTemporaryChat(temporary)
            saveStatus = "Settings saved ✓"
        }) { Text("Save settings") }

        if (saveStatus.isNotBlank()) {
            Text(
                saveStatus,
                color = if (saveStatus.startsWith("Saved")) Color(0xFF73E6A8) else Color(0xFFFF8A8A),
                fontSize = 12.sp
            )
        }

        TextButton(onClick = { store.clearChat(); onBack() }) { Text("New chat / clear history") }
        TextButton(onClick = { memory.clear() }) { Text("Clear memory") }
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                })
            }
        }) { Text("App permissions") }
        TextButton(onClick = { ArgonAccessibilityService.openAccessibilitySettings(context) }) {
            Text("Accessibility controls")
        }
        Text(
            "Optional: let Argon count supported mail/calendar notification groups and flag missed-call alerts. It does not read full inbox contents.",
            color = Color(0xFF6F8BA8), fontSize = 11.sp
        )
        TextButton(onClick = { NotificationDigestService.openAccessSettings(context) }) {
            Text(if (NotificationDigestService.hasAccess(context)) "Notification access settings" else "Connect email / calendar / missed-call notifications")
        }
        Spacer(Modifier.height(18.dp))
        Divider(color = Color(0xFF20364D))
        Spacer(Modifier.height(14.dp))
        Text("Account integrations", color = Color(0xFF8EB5DC), fontSize = 18.sp)
        Text("Google: read-only Gmail inbox unread count and upcoming Calendar events. Notion: read tasks and add a task to the database you share.", color = Color(0xFF6F8BA8), fontSize = 11.sp)
        Button(onClick = { activity.connectGoogleWorkspace { saveStatus = it } }, modifier = Modifier.fillMaxWidth()) { Text(if (store.loadGoogleAccessToken().isBlank()) "Connect Google Workspace" else "Reconnect Google Workspace") }
        Button(onClick = {
            integrationBusy = true
            integrationScope.launch {
                saveStatus = try { withContext(Dispatchers.IO) { GoogleWorkspaceApi.summary(store.loadGoogleAccessToken()) } }
                catch(error:Throwable) { error.message ?: "Google check failed." }
                finally { integrationBusy = false }
            }
        }, enabled = !integrationBusy, modifier = Modifier.fillMaxWidth()) { Text("Test Gmail + Calendar access") }
        Spacer(Modifier.height(10.dp))
        Text("Notion integration token", color = Color.White)
        OutlinedTextField(value=notionToken,onValueChange={notionToken=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Internal integration token")},visualTransformation=PasswordVisualTransformation())
        Text("Task database ID", color = Color.White)
        OutlinedTextField(value=notionDatabaseId,onValueChange={notionDatabaseId=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Notion database ID")})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={
                val saved=runCatching{store.saveNotionToken(notionToken)}.getOrDefault(false)
                store.saveNotionDatabaseId(notionDatabaseId);notionToken=""
                saveStatus=if(saved)"Notion token saved encrypted on this device." else "Could not save Notion token. Paste it and try again."
            }) { Text("Save Notion") }
            OutlinedButton(onClick={
                integrationBusy=true
                integrationScope.launch {
                    saveStatus=try{withContext(Dispatchers.IO){NotionApi.testConnection(store.loadNotionToken())}}
                    catch(error:Throwable){error.message ?: "Notion test failed."}
                    finally{integrationBusy=false}
                }
            },enabled=!integrationBusy){Text("Test Notion")}
        }
        OutlinedTextField(value=notionTask,onValueChange={notionTask=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Task to add to Notion")})
        Button(onClick={
            store.saveNotionDatabaseId(notionDatabaseId);integrationBusy=true
            integrationScope.launch {
                saveStatus=try{withContext(Dispatchers.IO){NotionApi.createTask(store.loadNotionToken(),store.loadNotionDatabaseId(),notionTask)}}
                catch(error:Throwable){error.message ?: "Could not create Notion task."}
                finally{integrationBusy=false}
            }
        },enabled=!integrationBusy&&notionTask.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Add task to Notion")}
        Text("Voice commands: check my unread emails; what's on my calendar; show my Notion tasks; add task to Notion buy notebooks.",color=Color(0xFF6F8BA8),fontSize=11.sp)
        Spacer(Modifier.height(12.dp))
        Text("Integrations", color = Color(0xFF8EB5DC), fontSize = 18.sp)
        Text("Google status: " + if (store.loadGoogleAccessToken().isBlank()) "Not connected" else "Connected (reconnect if token expires)", color = Color(0xFF6F8BA8), fontSize = 12.sp)
        Button(onClick = { VantaIntegrations.openGoogleConnect(context) }) { Text("Connect Google account") }
        Text("Approve only the Gmail and Calendar permissions you want Vanta to use. Access tokens expire.", color = Color(0xFF6F8BA8), fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = notionToken, onValueChange = { notionToken = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notion integration token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(value = notionDatabaseId, onValueChange = { notionDatabaseId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notion database ID") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { VantaIntegrations.openNotionHelp(context) }) { Text("Create Notion integration") }
            Button(onClick = {
                val tokenSaved = runCatching { store.saveNotionToken(notionToken) }.getOrDefault(false)
                val dbSaved = runCatching { store.saveNotionDatabaseId(notionDatabaseId) }.getOrDefault(false)
                if (tokenSaved && dbSaved) { notionToken = ""; saveStatus = "Notion connection saved securely on this device." }
                else saveStatus = "Could not save Notion settings."
            }) { Text("Save Notion") }
        }
        Text("Share the target database with your Notion integration before asking Argon to read it.", color = Color(0xFF6F8BA8), fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))
        Divider(color = Color(0xFF20364D))
        Spacer(Modifier.height(12.dp))
        Text("Code Dev Mode", color = Color(0xFF8EB5DC), fontSize = 18.sp)
        Text(
            "Let Argon request code changes from GitHub Actions. The workflow generates a patch, tests and builds it, then creates a separate pull request. It never silently installs or merges changes. Create a fine-grained token restricted to this repository with Actions: Read and write. The token is encrypted on this device and is not embedded in the APK.",
            color = Color(0xFF6F8BA8), fontSize = 11.sp
        )
        OutlinedTextField(
            value = githubToken,
            onValueChange = { githubToken = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Fine-grained GitHub token") },
            visualTransformation = PasswordVisualTransformation()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new"))) }
            }) { Text("Create token") }
            Button(onClick = {
                saveStatus = if (githubToken.isBlank()) {
                    "Paste a repository-scoped GitHub token first."
                } else if (runCatching { store.saveGitHubToken(githubToken) }.getOrDefault(false)) {
                    githubToken = ""
                    "GitHub connection saved securely on this device."
                } else {
                    "Could not save the GitHub token. Try again."
                }
            }) { Text("Save GitHub connection") }
        }
        Text(
            "Optional: enable Argon screen controls in Android Accessibility settings to use voice commands like ‘go back’, ‘scroll down’, ‘tap Settings’, or ‘type hello’. Text is entered only in a focused non-password field and is never submitted automatically. Avoid enabling accessibility for apps you do not trust.",
            color = Color(0xFF6F8BA8),
            fontSize = 11.sp
        )
        TextButton(onClick = onBack) { Text("Back") }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment ?: "File"
        } ?: (uri.lastPathSegment ?: "File")
    }.getOrDefault("File")
}

private fun attachmentToDataUrls(context: Context, attachment: VantaAttachment): List<String> {
    attachment.bitmap?.let { return listOf(bitmapToDataUrl(it)) }
    val uri = attachment.uri ?: return emptyList()
    return runCatching {
        val mime = context.contentResolver.getType(uri).orEmpty()
        when {
            mime.startsWith("image/") ->
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { listOf(bitmapToDataUrl(it)) }.orEmpty()
            mime == "application/pdf" || attachment.name.lowercase(Locale.getDefault()).endsWith(".pdf") ->
                renderPdfPages(context, uri, 3).map { bitmapToDataUrl(it) }
            mime.startsWith("text/") -> {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) emptyList() else listOf(bitmapToDataUrl(textCard(text)))
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}

private fun attachmentContextText(context: Context, attachment: VantaAttachment): String? {
    val uri = attachment.uri ?: return attachment.bitmap?.let { "Image attachment: " + attachment.name }
    val mime = context.contentResolver.getType(uri).orEmpty()
    return runCatching {
        when {
            VantaAttachmentInspector.isDocx(mime, attachment.name) -> {
                val text = extractDocxText(context, uri)
                "Document: " + attachment.name + "\n" + text.take(12000)
            }
            VantaAttachmentInspector.isTextLike(mime, attachment.name) -> {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                "Text file: " + attachment.name + "\n" + text.take(12000)
            }
            mime == "application/pdf" || attachment.name.lowercase(Locale.getDefault()).endsWith(".pdf") -> {
                val pages = pdfPageCount(context, uri)
                "PDF: " + attachment.name + " (" + (pages?.toString() ?: "unknown") + " pages). Page previews are attached for visual analysis."
            }
            mime.startsWith("image/") -> "Image attachment: " + attachment.name
            else -> "Attachment: " + attachment.name + " (" + mime + ")"
        }
    }.getOrNull()
}

private fun extractDocxText(context: Context, uri: Uri): String {
    val temp = java.io.File.createTempFile("vanta_docx_", ".docx", context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { input ->
        temp.outputStream().use { input.copyTo(it) }
    }
    return runCatching {
        java.util.zip.ZipFile(temp).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return@use ""
            zip.getInputStream(entry).bufferedReader().use { reader ->
                reader.readText()
                    .replace("<w:tab/>", " ")
                    .replace("<w:tab />", " ")
                    .replace("</w:p>", " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex(" +"), " ")
                    .trim()
            }
        }
    }.getOrDefault("")
        .also { temp.delete() }
}

private fun pdfPageCount(context: Context, uri: Uri): Int? = runCatching {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
    descriptor.use { PdfRenderer(it).use { renderer -> renderer.pageCount } }
}.getOrNull()

private fun renderPdfPages(context: Context, uri: Uri, maxPages: Int = 3): List<Bitmap> = runCatching {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching emptyList()
    descriptor.use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            (0 until minOf(renderer.pageCount, maxPages)).mapNotNull { index ->
                renderer.openPage(index).use { page ->
                    val width = (page.width * 1.4f).toInt().coerceAtMost(1800)
                    val height = (page.height.toFloat() / page.width * width).toInt().coerceAtMost(2400)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}.getOrDefault(emptyList())

private fun bitmapToDataUrl(bitmap: Bitmap): String {
    val maxSide = 1280
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val out = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.JPEG, 78, out)
    if (resized !== bitmap) resized.recycle()
    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
}

private fun textCard(text: String): Bitmap {
    val width = 1000
    val height = minOf(1800, maxOf(300, 220 + (text.length / 55) * 28))
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(16, 34, 56))
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 26f
        isAntiAlias = true
    }
    var y = 60f
    text.take(4500).chunked(55).forEach { line ->
        if (y <= height - 30) canvas.drawText(line, 40f, y, paint)
        y += 28f
    }
    return bitmap
}

private fun renderPdfFirstPage(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val scale = 1200f / page.width.toFloat()
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt(),
                        (page.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }.getOrNull()
}

private fun decodeImageReference(value: String): Bitmap? {
    return runCatching {
        if (value.startsWith("data:image/")) {
            val b64 = value.substringAfter("base64,")
            val bytes = Base64.getDecoder().decode(b64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            URL(value).openStream().use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()
}
