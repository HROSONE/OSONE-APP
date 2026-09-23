package com.osone.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
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
    private var darkMode by mutableStateOf(false)
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
        volumeControlStream = AudioManager.STREAM_MUSIC
        darkMode = getSharedPreferences("osone_config", 0).getBoolean("dark_mode", false)
        speech = TextToSpeech(this, this)
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var readAloud by remember { mutableStateOf(false) }
            val purple = Color(0xFF7044D7)
            MaterialTheme(colorScheme = if (darkMode) darkColorScheme(
                primary = Color(0xFFC4AAFF), background = Color(0xFF101018), surface = Color(0xFF101018))
                else lightColorScheme(primary = purple)) {
                Surface(Modifier.fillMaxSize()) {
                    if (showLive) LiveScreen(live, onBack = { live.stop(); showLive = false })
                    else if (showSettings) SettingsScreen(viewModel, live, darkMode,
                        onDarkMode = { enabled ->
                            darkMode = enabled
                            getSharedPreferences("osone_config", 0).edit().putBoolean("dark_mode", enabled).apply()
                        }, onBack = { showSettings = false })
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
    LaunchedEffect(viewModel.streamingText.length) {
        if (viewModel.streamingText.isNotBlank()) scroll.scrollToItem(viewModel.messages.size)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OSONE APP", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onReadAloud) { Text(if (readAloud) "🔊 Voz ligada" else "🔇 Voz desligada") }
            TextButton(onClick = onSettings) { Text("Ajustes") }
        }
        Text("Seu assistente no Android", color = MaterialTheme.colorScheme.secondary)
        Text("Cérebro do chat: ${viewModel.selectedChatLabel}", style = MaterialTheme.typography.bodySmall)
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
            if (viewModel.streamingText.isNotBlank()) item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(0.90f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("OSONE · respondendo", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(viewModel.streamingText)
                    }
                }
            }
        }
        if (viewModel.busy) {
            Text("Consultando ${viewModel.activeTextModel ?: viewModel.selectedChatLabel}…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (viewModel.provider == ChatProvider.GEMINI && viewModel.lastAnswerModel != null && viewModel.lastAnswerModel != viewModel.selectedModel) {
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
private fun SettingsScreen(viewModel: OsoneViewModel, live: LiveVoiceViewModel, darkMode: Boolean,
    onDarkMode: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Conversa") }
        Text("Configurações", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Modo noturno", modifier = Modifier.weight(1f))
            Switch(checked = darkMode, onCheckedChange = onDarkMode)
        }
        Text("Chaves de API", style = MaterialTheme.typography.titleMedium)
        ProviderKeyField(viewModel, ChatProvider.GEMINI)
        Text("Gemini permanece como chave do Live. No chat escrito, use o provedor escolhido abaixo.", style = MaterialTheme.typography.bodySmall)
        ProviderKeyField(viewModel, ChatProvider.OPENROUTER)
        ProviderKeyField(viewModel, ChatProvider.GROQ)
        viewModel.keyStatus?.let { Text(it, color = if (viewModel.keySaveError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("Cérebro do chat escrito", style = MaterialTheme.typography.titleMedium)
        ChatProviderPicker(viewModel)
        when (viewModel.provider) {
            ChatProvider.GEMINI -> {
                ChatModelPicker(viewModel)
                ThinkingModePicker(viewModel)
                Text("Rápido responde com menos espera; Profundo pode demorar mais.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trocar de modelo Gemini se falhar", modifier = Modifier.weight(1f))
                    Switch(checked = viewModel.fallback, onCheckedChange = viewModel::updateFallback)
                }
                Text("Ordem: modelo escolhido → versões Flash anteriores. Só troca se o modelo estiver indisponível ou sem cota.", style = MaterialTheme.typography.bodySmall)
            }
            ChatProvider.GROQ -> GroqModelPicker(viewModel)
            ChatProvider.OPENROUTER -> OpenRouterModelField(viewModel)
        }
        HorizontalDivider()
        Text("Voz em tempo real", style = MaterialTheme.typography.titleMedium)
        LiveModelPicker(live)
        LiveVoicePicker(live)
        Text("Trocar de voz reinicia a chamada. O volume da voz fica no orbe Live.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trocar de modelo se falhar", modifier = Modifier.weight(1f))
            Switch(checked = live.fallback, onCheckedChange = live::updateFallback)
        }
        HorizontalDivider()
        var confirmClear by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmClear = true }) { Text("Apagar conversa deste aparelho") }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text("Apagar conversa?") }, text = { Text("O histórico local será removido.") },
            confirmButton = { TextButton(onClick = { viewModel.clearConversation(); confirmClear = false }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Gemini é obrigatório apenas para o Live; no texto, você escolhe Gemini, OpenRouter ou Groq. Cada chave é individual e só vai ao respectivo serviço. Internet e acesso aos modelos são necessários.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProviderKeyField(viewModel: OsoneViewModel, provider: ChatProvider) {
    var key by remember(provider) { mutableStateOf("") }
    val saved = viewModel.configuredFor(provider)
    Text("${provider.label}${if (provider == ChatProvider.GEMINI) " · Live e chat opcional" else " · só chat"}: ${if (saved) "chave salva ✓" else "sem chave"}",
        style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(value = key, onValueChange = { key = it },
        label = { Text("Chave ${provider.label}") },
        placeholder = { Text(if (saved) "Cole uma nova chave para substituir" else "Cole sua chave") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (viewModel.saveKey(key, provider)) key = "" }, enabled = key.isNotBlank()) {
            Text(if (saved) "Atualizar" else "Salvar")
        }
        if (saved) TextButton(onClick = { viewModel.removeKey(provider) }) { Text("Remover") }
    }
}

@Composable
private fun ChatProviderPicker(viewModel: OsoneViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Provedor: ${viewModel.provider.label}  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChatProvider.entries.forEach { value ->
                DropdownMenuItem(text = { Text(value.label) }, onClick = {
                    viewModel.selectProvider(value); expanded = false
                })
            }
        }
    }
}

@Composable
private fun GroqModelPicker(viewModel: OsoneViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Modelo: ${viewModel.groqModel.label}  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GroqModel.entries.forEach { value ->
                DropdownMenuItem(text = { Text(value.label) }, onClick = {
                    viewModel.selectGroqModel(value); expanded = false
                })
            }
        }
    }
}

@Composable
private fun OpenRouterModelField(viewModel: OsoneViewModel) {
    var draft by remember { mutableStateOf(viewModel.openRouterModel) }
    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("ID do modelo OpenRouter") })
    Button(onClick = { viewModel.setOpenRouterModel(draft); draft = viewModel.openRouterModel }) { Text("Salvar modelo") }
    Text("openrouter/free é o padrão gratuito e escolhe um modelo disponível. Você pode informar outro ID; confira preços e acesso no OpenRouter.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ThinkingModePicker(viewModel: OsoneViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Raciocínio: ${viewModel.thinkingMode.label}  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThinkingMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                    viewModel.selectThinking(mode); expanded = false
                })
            }
        }
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
private fun LiveVoicePicker(live: LiveVoiceViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Voz Gemini: ${live.voice}  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LiveVoices.names.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    live.selectVoice(name); expanded = false
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
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Chat") }
            Spacer(Modifier.weight(1f))
            Text("OSONE LIVE", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))
        LiveModelPicker(live)
        LiveVoicePicker(live)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fallback automático")
            Spacer(Modifier.width(12.dp))
            Switch(checked = live.fallback, onCheckedChange = {
                live.updateFallback(it)
                if (live.active != null) live.start()
            })
        }
        Box(Modifier.height(230.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            VoiceOrb(live.inputLevel, live.outputLevel, live.connected)
        }
        Text(live.status, style = MaterialTheme.typography.titleMedium)
        if (!live.connected && live.attempts.isNotEmpty()) {
            Text("Diagnóstico: ${live.attempts.last()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (live.active != null && live.selected != live.active)
            Text("Fallback: ${live.active?.label}", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))
        Text("Áudio direto · sem transcrição", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text("Volume da voz: ${(live.gain * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(value = live.gain, onValueChange = live::setGain, valueRange = 0.5f..2f, steps = 14)
        Text("Os botões de volume do celular controlam a mídia.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
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
    Canvas(Modifier.fillMaxWidth().height(230.dp)) {
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
