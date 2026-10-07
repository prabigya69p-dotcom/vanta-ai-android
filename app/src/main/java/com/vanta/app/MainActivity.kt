package com.vanta.app
import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ChatMessage(val user: Boolean, val text: String)

class MainActivity : ComponentActivity() {
    private lateinit var store: VantaStore
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VantaStore(this)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        setContent { VantaApp(store, this) }
    }
    fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vanta_reply") }
    fun listen(onText: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) { results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText) }
                override fun onError(error: Int) {}
                override fun onReadyForSpeech(params: Bundle) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle) {}
                override fun onEvent(eventType: Int, params: Bundle) {}
            })
        }
        val intent = RecognizerIntent().apply {
            action = RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer?.startListening(intent)
    }
    override fun onDestroy() { recognizer?.destroy(); tts?.shutdown(); super.onDestroy() }
}

@Composable
private fun VantaApp(store: VantaStore, activity: Activity) {
    var settings by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF080B12), surface = Color(0xFF111722), primary = Color(0xFF7C9CFF), secondary = Color(0xFF65D6FF))) {
        if (settings) SettingsScreen(store) { settings = false } else ChatScreen(store, activity) { settings = true }
    }
}

@Composable
private fun ChatScreen(store: VantaStore, activity: Activity, openSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var research by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf(ChatMessage(false, "Hey — I'm VANTA. What are we building?")) }
    fun send(raw: String) {
        val prompt = raw.trim()
        if (prompt.isEmpty() || busy) return
        val smartResearch = research || prompt.contains(Regex("\\b(latest|today|current|news|search|look up|internet)\\b", RegexOption.IGNORE_CASE))
        input = ""
        messages += ChatMessage(true, prompt)
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { VantaApi(store.loadApiKey(), store.loadModel()).ask(prompt, smartResearch) }
            }
            val reply = result.fold({ it.text }, { "VANTA error: " + (it.message ?: "Unknown error") })
            messages += ChatMessage(false, reply)
            result.getOrNull()?.text?.let { (activity as? MainActivity)?.speak(it) }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF080B12)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("VANTA", fontSize = 27.sp, color = Color.White)
                Text("adaptive AI", color = Color(0xFF8B95A7), fontSize = 13.sp)
            }
            IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.user) Color(0xFF1B2942) else Color(0xFF111722), shape = RoundedCornerShape(18.dp)) {
                        Text(message.text, Modifier.padding(14.dp), color = Color.White)
                    }
                }
            }
            if (busy) item { Text("VANTA is thinking…", color = Color(0xFF8B95A7)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = research, onClick = { research = !research }, label = { Text("Research") })
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message VANTA") }, maxLines = 4)
            FilledIconButton(onClick = { (activity as? MainActivity)?.listen { send(it) } }, shape = CircleShape) { Icon(Icons.Default.Mic, "Voice") }
            FilledIconButton(onClick = { send(input) }, shape = CircleShape) { Icon(Icons.Default.Send, "Send") }
        }
    }
}

@Composable
private fun SettingsScreen(store: VantaStore, onBack: () -> Unit) {
    var key by remember { mutableStateOf(store.loadApiKey()) }
    var model by remember { mutableStateOf(store.loadModel()) }
    Column(Modifier.fillMaxSize().background(Color(0xFF080B12)).padding(20.dp)) {
        Text("VANTA Settings", fontSize = 25.sp, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Text("OpenAI API key", color = Color(0xFFB8C0CF))
        OutlinedTextField(value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true, placeholder = { Text("sk-…") })
        Spacer(Modifier.height(14.dp))
        Text("Model", color = Color(0xFFB8C0CF))
        OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(22.dp))
        Button(onClick = { store.saveApiKey(key); store.saveModel(model); onBack() }) { Text("Save") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
