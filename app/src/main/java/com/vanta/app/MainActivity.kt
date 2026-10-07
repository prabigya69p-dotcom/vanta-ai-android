package com.vanta.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

data class ChatMessage(val user: Boolean, val text: String, val image: Uri? = null)

class MainActivity : ComponentActivity() {
    private lateinit var store: VantaStore
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListeningInternal {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VantaStore(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        setContent { VantaApp(store, this) }
    }

    fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vanta_reply") }

    fun listen(onText: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
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
            })
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun VantaApp(store: VantaStore, activity: MainActivity) {
    var settings by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF080F1A), surface = Color(0xFF0F1B2A),
        primary = Color(0xFF4D9FE3), secondary = Color(0xFF8EB5DC)
    )) {
        if (settings) SettingsScreen(store) { settings = false }
        else ChatScreen(store, activity) { settings = true }
    }
}

@Composable
private fun ChatScreen(store: VantaStore, activity: MainActivity, openSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var research by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var attachedUri by remember { mutableStateOf<Uri?>(null) }
    var messages by remember {
        mutableStateOf(listOf(ChatMessage(false, "Hey — I'm VANTA. What are we building?")))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachedUri = uri
    }

    fun send() {
        val prompt = input.trim()
        val uri = attachedUri
        if ((prompt.isEmpty() && uri == null) || busy) return
        val displayText = prompt.ifEmpty { "Analyze this photo." }
        messages = messages + ChatMessage(true, displayText, uri)
        input = ""
        attachedUri = null
        busy = true
        scope.launch {
            val smartResearch = research || Regex("\b(latest|today|current|news|search|look up|internet)\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
            val imageData = uri?.let { withContext(Dispatchers.IO) { uriToDataUrl(context, it) } }
            val result = runCatching {
                VantaApi(store.loadApiKey(), store.loadModel()).ask(displayText, smartResearch, imageData)
            }.fold(
                onSuccess = { it.text },
                onFailure = { "VANTA error: " + (it.message ?: "Unknown error") }
            )
            messages = messages + ChatMessage(false, result)
            if (result.isNotBlank()) activity.speak(result)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF080F1A)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("VANTA", fontSize = 27.sp, color = Color.White)
                Text("ADAPTIVE AI", fontSize = 13.sp, color = Color(0xFF8EB5DC))
            }
            IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (message.user) Color(0xFF173A5C) else Color(0xFF102238),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.widthIn(max = 330.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            message.image?.let { uri ->
                                val bitmap = remember(uri) {
                                    mutableStateOf<android.graphics.Bitmap?>(null)
                                }
                                LaunchedEffect(uri) {
                                    bitmap.value = withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                                    }
                                }
                                bitmap.value?.let {
                                    Image(it.asImageBitmap(), "Attached photo", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            Text(message.text, color = Color.White)
                        }
                    }
                }
            }
            if (busy) item { Text("VANTA is thinking…", color = Color(0xFF8EB5DC)) }
        }

        attachedUri?.let { uri ->
            Surface(color = Color(0xFF102238), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bitmap = remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(uri) {
                        bitmap.value = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        }
                    }
                    bitmap.value?.let { Image(it.asImageBitmap(), "Photo preview", Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                    Spacer(Modifier.width(10.dp))
                    Text("Photo attached", color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(onClick = { attachedUri = null }) { Icon(Icons.Default.Close, "Remove photo", tint = Color.White) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = research, onClick = { research = !research }, label = { Text("Research") })
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message VANTA") }, maxLines = 4
            )
            IconButton(onClick = { picker.launch("image/*") }, modifier = Modifier.size(50.dp)) {
                Icon(Icons.Default.Add, "Attach photo", tint = Color.White)
            }
            FilledIconButton(onClick = { activity.listen { input = it } }, shape = CircleShape) {
                Icon(Icons.Default.Mic, "Voice")
            }
            FilledIconButton(onClick = ::send, shape = CircleShape, enabled = !busy) {
                Icon(Icons.Default.Send, "Send")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Attach a photo with + and ask VANTA to analyze it.", color = Color(0xFF6F8BA8), fontSize = 12.sp)
    }
}

private fun uriToDataUrl(context: android.content.Context, uri: Uri): String? {
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val maxSide = 1280
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
    val resized = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val out = ByteArrayOutputStream()
    resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
    if (resized !== bitmap) resized.recycle()
    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
}

@Composable
private fun SettingsScreen(store: VantaStore, onBack: () -> Unit) {
    var key by remember { mutableStateOf(store.loadApiKey()) }
    var model by remember { mutableStateOf(store.loadModel()) }
    Column(Modifier.fillMaxSize().background(Color(0xFF080F1A)).padding(20.dp)) {
        Text("VANTA Settings", fontSize = 25.sp, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Text("OpenAI API key", color = Color(0xFF8EB5DC))
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-…") })
        Spacer(Modifier.height(14.dp))
        Text("Model", color = Color(0xFF8EB5DC))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(22.dp))
        Button(onClick = { store.saveApiKey(key); store.saveModel(model); onBack() }) { Text("Save") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
