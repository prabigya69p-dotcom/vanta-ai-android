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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
    private val incomingImage = mutableStateOf<Uri?>(null)
    private val incomingWakeQuery = mutableStateOf<String?>(null)

    private var pendingCameraAction: (() -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListeningInternal {}
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
        incomingWakeQuery.value = intent.getStringExtra("wake_query")?.takeIf { it.isNotBlank() }
    }

    private fun applyTtsSettings() {
        tts?.language = Locale.getDefault()
        tts?.setSpeechRate(store.loadVoiceSpeed())
        tts?.setPitch(store.loadVoicePitch())
    }

    fun speak(text: String) {
        stopListening()
        applyTtsSettings()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vanta_reply")
    }

    fun listen(onText: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopSpeaking()
        startListeningInternal(onText)
    }

    private fun startListeningInternal(onText: (String) -> Unit) {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
                }
                override fun onError(error: Int) {}
                override fun onReadyForSpeech(params: Bundle) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle) {}
                override fun onEvent(eventType: Int, params: Bundle) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        }
    }

    private fun stopListening() {
        recognizer?.cancel()
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
        recognizer?.destroy()
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
            SettingsScreen(store, memory) { settings = false }
        } else {
            ChatScreen(store, memory, activity, incomingImage, incomingWakeQuery) { settings = true }
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
    openSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VantaMode.SMART) }
    var modeMenu by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(listOf<VantaAttachment>()) }
    var messages by remember {
        mutableStateOf(
            store.loadChat().map { ChatMessage(it.user, it.text) }.ifEmpty {
                listOf(ChatMessage(false, "Hey — I'm VANTA. What are we building?"))
            }
        )
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { attachments = attachments + VantaAttachment(uri = it, name = "Photo") }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachments = attachments + VantaAttachment(uri = uri, name = displayName(context, uri))
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) attachments = attachments + VantaAttachment(bitmap = bitmap, name = "Camera") }
    }

    LaunchedEffect(incomingImage.value) {
        incomingImage.value?.let {
            attachments = attachments + VantaAttachment(uri = it, name = "Shared image")
            incomingImage.value = null
        }
    }

    LaunchedEffect(incomingWakeQuery.value) {
        incomingWakeQuery.value?.let {
            input = it
            incomingWakeQuery.value = null
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

        val text = prompt.ifEmpty { "Analyze these attachments." }
        val detectedMode = VantaSmart.modeFor(text, mode)
        val research = VantaSmart.shouldResearch(text, mode)
        val local = if (currentAttachments.isEmpty()) VantaLocalCommandRouter.execute(context, text, memory) else null

        messages = messages + ChatMessage(true, text, attachments = currentAttachments)
        input = ""
        attachments = emptyList()

        if (local != null) {
            addAssistant(local.reply)
            if (local.shouldOpenSettings) openSettings()
            activity.speak(local.reply)
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
                }.take(4)

                val history = messages.dropLast(1).takeLast(10).map { SavedChatMessage(it.user, it.text) }
                val instructions = VantaSmart.instructions(
                    detectedMode,
                    store.loadPersonality(),
                    store.loadProject(),
                    memory.list()
                )
                val api = VantaApi(store.loadApiKey(), store.loadModel())

                if (research) {
                    val reply = withContext(Dispatchers.IO) {
                        api.ask(text, instructions, true, imageDataUrls, history)
                    }
                    messages = messages + ChatMessage(false, reply.text, sources = reply.sources)
                    persist()
                    activity.speak(reply.text)
                } else {
                    var streamed = ""
                    messages = messages + ChatMessage(false, "")
                    val reply = withContext(Dispatchers.IO) {
                        api.askStreaming(text, instructions, false, imageDataUrls, history) { delta ->
                            streamed += delta
                            runOnUiThread {
                                messages = messages.dropLast(1) + ChatMessage(false, streamed)
                            }
                        }
                    }
                    messages = messages.dropLast(1) + ChatMessage(false, reply.text)
                    persist()
                    activity.speak(reply.text)
                }
            } catch (t: Throwable) {
                val error = "VANTA error: " + (t.message ?: "Unknown error")
                messages = messages + ChatMessage(false, error)
                persist()
                activity.speak(error)
            } finally {
                busy = false
            }
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
            if (busy) item { Text("VANTA is working…", color = Color(0xFF8EB5DC), fontSize = 12.sp) }
        }

        if (attachments.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.take(4).forEachIndexed { index, attachment ->
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
            TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("File") }
            TextButton(onClick = {
                mode = VantaMode.FOCUS
            }) { Text("Focus") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message VANTA") },
                maxLines = 4
            )
            FilledIconButton(onClick = { activity.listen { input = it } }, shape = CircleShape) {
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
    onBack: () -> Unit
) {
    var key by remember { mutableStateOf(store.loadApiKey()) }
    var model by remember { mutableStateOf(store.loadModel()) }
    var personality by remember { mutableStateOf(store.loadPersonality()) }
    var project by remember { mutableStateOf(store.loadProject()) }
    var speed by remember { mutableFloatStateOf(store.loadVoiceSpeed()) }
    var pitch by remember { mutableFloatStateOf(store.loadVoicePitch()) }
    var temporary by remember { mutableStateOf(store.loadTemporaryChat()) }
    var wake by remember { mutableStateOf(store.loadWakeEnabled()) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF080F1A))
            .padding(20.dp)
    ) {
        Text("VANTA Settings", fontSize = 25.sp, color = Color.White)
        Spacer(Modifier.height(16.dp))

        Text("OpenAI API key", color = Color(0xFF8EB5DC))
        OutlinedTextField(
            key, { key = it }, Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-…") }
        )

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
                Text("Hey VANTA hands-free", color = Color.White)
                Text("Visible foreground listener; toggle on only when needed.", color = Color(0xFF6F8BA8), fontSize = 11.sp)
            }
            Switch(
                checked = wake,
                onCheckedChange = {
                    wake = it
                    store.saveWakeEnabled(it)
                    if (it) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            WakeService.start(context)
                        }
                    } else {
                        WakeService.stop(context)
                    }
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("Saved memory (" + memory.list().size + "/30)", color = Color(0xFF8EB5DC), fontSize = 12.sp)
        memory.list().takeLast(6).forEach { Text("• " + it, color = Color(0xFFD5E5F5), fontSize = 12.sp) }

        Spacer(Modifier.height(18.dp))
        Button(onClick = {
            store.saveApiKey(key)
            store.saveModel(model)
            store.savePersonality(personality)
            store.saveProject(project)
            store.saveVoiceSpeed(speed)
            store.saveVoicePitch(pitch)
            store.saveTemporaryChat(temporary)
            onBack()
        }) { Text("Save") }

        TextButton(onClick = { store.clearChat(); onBack() }) { Text("New chat / clear history") }
        TextButton(onClick = { memory.clear() }) { Text("Clear memory") }
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                })
            }
        }) { Text("App permissions") }
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
            mime == "application/pdf" ->
                renderPdfFirstPage(context, uri)?.let { listOf(bitmapToDataUrl(it)) }.orEmpty()
            mime.startsWith("text/") -> {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) emptyList() else listOf(bitmapToDataUrl(textCard(text)))
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}

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