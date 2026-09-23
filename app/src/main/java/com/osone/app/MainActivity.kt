package com.osone.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: OsoneViewModel by viewModels()
    private val live: LiveVoiceViewModel by viewModels()
    private var speech: TextToSpeech? = null
    private var showLive by mutableStateOf(false)
    private var permissionError by mutableStateOf(false)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openLive() else permissionError = true
    }

    private fun openLive() {
        permissionError = false
        showLive = true
        live.start()
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
                    if (showLive) LiveScreen(live, onBack = { live.stop(); showLive = false })
                    else if (showSettings) SettingsScreen(viewModel, live, onBack = { showSettings = false })
                    else ChatScreen(viewModel, onMic = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                openLive()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }, permissionError = permissionError, onSettings = { showSettings = true }, readAloud = readAloud,
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
    override fun onStop() { live.stop(); super.onStop() }
    override fun onDestroy() { speech?.stop(); speech?.shutdown(); super.onDestroy() }
}

@Composable
private fun ChatScreen(viewModel: OsoneViewModel, onMic: () -> Unit, permissionError: Boolean,
    onSettings: () -> Unit, readAloud: Boolean, onReadAloud: () -> Unit,
    onAnswer: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
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
        Text("Cérebro do chat: ${viewModel.selectedModel.label}", style = MaterialTheme.typography.bodySmall)
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
        if (viewModel.busy) {
            Text("Consultando ${viewModel.activeModel?.label ?: viewModel.selectedModel.label}…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (viewModel.lastAnswerModel != null && viewModel.lastAnswerModel != viewModel.selectedModel) {
            Text("Última resposta: ${viewModel.lastAnswerModel?.label}", style = MaterialTheme.typography.bodySmall)
        }
        viewModel.error?.let { error ->
            TextButton(onClick = viewModel::dismissError) { Text("$error  ✕", color = MaterialTheme.colorScheme.error) }
        }
        if (permissionError) Text("Permita o microfone para conversar por voz.", color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Escreva sua mensagem") },
                modifier = Modifier.weight(1f), maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (viewModel.send(draft, onAnswer)) draft = "" }))
            Column {
                TextButton(onClick = onMic) { Text("🎙️ Live") }
                Button(onClick = { if (viewModel.send(draft, onAnswer)) draft = "" }, enabled = draft.isNotBlank() && !viewModel.busy) { Text("Enviar") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: OsoneViewModel, live: LiveVoiceViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Conversa") }
        Text("Configurações", style = MaterialTheme.typography.headlineMedium)
        Text(if (viewModel.configured) "✓ Chave Gemini salva neste aparelho (oculta por segurança)" else "Nenhuma chave Gemini salva")
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Nova chave Gemini") },
            placeholder = { Text(if (viewModel.configured) "Cole aqui somente para trocar a chave" else "Cole sua chave Gemini") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (viewModel.saveKey(key)) key = "" }, enabled = key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text(if (viewModel.configured) "Atualizar chave" else "Salvar chave") }
        viewModel.keyStatus?.let { Text(it, color = if (viewModel.keySaveError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("Cérebro do chat escrito", style = MaterialTheme.typography.titleMedium)
        ChatModelPicker(viewModel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trocar de modelo se falhar", modifier = Modifier.weight(1f))
            Switch(checked = viewModel.fallback, onCheckedChange = viewModel::updateFallback)
        }
        Text("Ordem: modelo escolhido → versões Flash anteriores. Só troca se o modelo estiver indisponível ou sem cota; um erro de chave interrompe a tentativa.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Voz em tempo real", style = MaterialTheme.typography.titleMedium)
        LiveModelPicker(live)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trocar de modelo se falhar", modifier = Modifier.weight(1f))
            Switch(checked = live.fallback, onCheckedChange = live::updateFallback)
        }
        TextButton(onClick = viewModel::removeKey) { Text("Remover chave deste aparelho") }
        HorizontalDivider()
        var confirmClear by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmClear = true }) { Text("Apagar conversa deste aparelho") }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text("Apagar conversa?") }, text = { Text("O histórico local será removido.") },
            confirmButton = { TextButton(onClick = { viewModel.clearConversation(); confirmClear = false }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("A chave salva é compartilhada entre o chat escrito e o Live neste aparelho. A lista de modelos do cérebro controla o chat; a lista Live controla a conversa por voz. Internet e cota Gemini são necessárias.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChatModelPicker(viewModel: OsoneViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Modelo: ${viewModel.selectedModel.label}  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChatModel.entries.forEach { model ->
                DropdownMenuItem(text = { Text(model.label) }, onClick = {
                    viewModel.selectModel(model); expanded = false
                })
            }
        }
    }
}

@Composable
private fun LiveModelPicker(live: LiveVoiceViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Modelo: ${live.selected.label}  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LiveModel.entries.forEach { model ->
                DropdownMenuItem(text = { Text(model.label) }, onClick = {
                    live.select(model); expanded = false
                    if (live.connected || live.active != null) live.start()
                })
            }
        }
    }
}

@Composable
private fun LiveScreen(live: LiveVoiceViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Chat") }
            Spacer(Modifier.weight(1f))
            Text("OSONE LIVE", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))
        LiveModelPicker(live)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fallback automático")
            Spacer(Modifier.width(12.dp))
            Switch(checked = live.fallback, onCheckedChange = {
                live.updateFallback(it)
                if (live.active != null) live.start()
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            VoiceOrb(live.inputLevel, live.outputLevel, live.connected)
        }
        Text(live.status, style = MaterialTheme.typography.titleMedium)
        if (live.active != null && live.selected != live.active)
            Text("Fallback: ${live.active?.label}", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))
        Text("Áudio direto · sem transcrição", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = live::toggleMute, enabled = live.connected) {
                Text(if (live.muted) "Ativar microfone" else "Silenciar")
            }
            Button(onClick = { if (live.active == null) live.start() else live.stop() }) {
                Text(if (live.active == null) "Tentar novamente" else "Encerrar")
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun VoiceOrb(input: Float, output: Float, connected: Boolean) {
    val motion = rememberInfiniteTransition(label = "respiração do orbe")
    val breath by motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2100, easing = EaseInOutSine), RepeatMode.Reverse), label = "respiração")
    val energy by animateFloatAsState(maxOf(input, output).coerceIn(0f, 1f),
        animationSpec = tween(100), label = "energia")
    val speaking = output > input
    val core = if (speaking) Color(0xFF24D2DE) else Color(0xFF8956EA)
    Canvas(Modifier.fillMaxWidth().height(320.dp)) {
        val radius = size.minDimension * (0.27f + breath * 0.016f + energy * 0.09f)
        val center = center
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.25f), core.copy(alpha = 0.07f),
            Color.Transparent), center = center, radius = radius * 1.55f), radius = radius * 1.55f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), core.copy(alpha = 0.9f),
            Color(0xFF292175)), center = center, radius = radius), radius = radius)
        drawCircle(core.copy(alpha = if (connected) 0.55f else 0.16f),
            radius = radius * (1.13f + energy * 0.12f), style = Stroke(width = 3.dp.toPx()))
        drawCircle(core.copy(alpha = 0.22f), radius = radius * (1.28f + energy * 0.16f),
            style = Stroke(width = 2.dp.toPx()))
    }
}
