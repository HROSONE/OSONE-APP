package com.osone.app

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: OsoneViewModel by viewModels()
    private var speech: TextToSpeech? = null
    private var recognizedText by mutableStateOf("")
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            recognizedText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = TextToSpeech(this, this)
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var readAloud by remember { mutableStateOf(false) }
            val purple = Color(0xFF7044D7)
            MaterialTheme(colorScheme = lightColorScheme(primary = purple)) {
                Surface(Modifier.fillMaxSize()) {
                    if (showSettings) SettingsScreen(viewModel, onBack = { showSettings = false })
                    else ChatScreen(viewModel, recognizedText, onRecognizedConsumed = { recognizedText = "" },
                        onMic = {
                            try {
                                speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR"))
                            } catch (_: Exception) { /* Reconhecedor indisponível: campo de texto segue funcional. */ }
                        }, onSettings = { showSettings = true }, readAloud = readAloud,
                        onReadAloud = { readAloud = !readAloud }, onAnswer = { answer ->
                            if (readAloud) speech?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "osone_resposta")
                        })
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) speech?.language = Locale("pt", "BR")
    }
    override fun onDestroy() { speech?.stop(); speech?.shutdown(); super.onDestroy() }
}

@Composable
private fun ChatScreen(viewModel: OsoneViewModel, recognizedText: String, onRecognizedConsumed: () -> Unit,
    onMic: () -> Unit, onSettings: () -> Unit, readAloud: Boolean, onReadAloud: () -> Unit,
    onAnswer: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(recognizedText) {
        if (recognizedText.isNotBlank()) { draft = recognizedText; onRecognizedConsumed() }
    }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) scope.launch { scroll.animateScrollToItem(viewModel.messages.lastIndex) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OSONE APP", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onReadAloud) { Text(if (readAloud) "🔊 Voz ligada" else "🔇 Voz desligada") }
            TextButton(onClick = onSettings) { Text("Ajustes") }
        }
        Text("Seu assistente no Android", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = scroll, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.messages) { message ->
                val user = message.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.90f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(if (user) "Você" else "OSONE", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(message.text)
                        }
                    }
                }
            }
        }
        if (viewModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        viewModel.error?.let { error ->
            TextButton(onClick = viewModel::dismissError) { Text("$error  ✕", color = MaterialTheme.colorScheme.error) }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Escreva ou dite") },
                modifier = Modifier.weight(1f), maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (viewModel.send(draft, onAnswer)) draft = "" }))
            Column {
                TextButton(onClick = onMic) { Text("🎙️") }
                Button(onClick = { if (viewModel.send(draft, onAnswer)) draft = "" }, enabled = draft.isNotBlank() && !viewModel.busy) { Text("Enviar") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: OsoneViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(viewModel.model) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Conversa") }
        Text("Configurações", style = MaterialTheme.typography.headlineMedium)
        Text(if (viewModel.configured) "Chave Gemini salva neste aparelho" else "Adicione sua chave Gemini para conversar")
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Nova chave Gemini") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Modelo") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.saveSettings(key, model); key = "" }, modifier = Modifier.fillMaxWidth()) { Text("Salvar") }
        TextButton(onClick = viewModel::removeKey) { Text("Remover chave deste aparelho") }
        HorizontalDivider()
        var confirmClear by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmClear = true }) { Text("Apagar conversa deste aparelho") }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text("Apagar conversa?") }, text = { Text("O histórico local será removido.") },
            confirmButton = { TextButton(onClick = { viewModel.clearConversation(); confirmClear = false }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("A voz usa o reconhecimento e o sintetizador disponíveis no Android. A resposta de IA requer internet e pode consumir sua cota Gemini.", style = MaterialTheme.typography.bodySmall)
    }
}
