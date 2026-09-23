package com.osone.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import java.text.SimpleDateFormat

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: OsoneViewModel by viewModels()
    private val live by lazy { LiveSession.get(application) }
    private val writing by lazy { WritingWorkspace.get(application) }
    private val updater by lazy { AppUpdater(this) }
    private var speech: TextToSpeech? = null
    private var showLive by mutableStateOf(false)
    private var showWriting by mutableStateOf(false)
    private var permissionError by mutableStateOf(false)
    private var darkMode by mutableStateOf(false)
    private var bubblePermission by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var overlayRequested = false
    private val diagnostics by lazy { AppDiagnostics.get(applicationContext) }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openLive() else { permissionError = true; diagnostics.record("Permissão", "Acesso ao microfone negado.") }
    }
    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null)
            LiveSessionService.command(this, LiveSessionService.SCREEN_START) {
                putExtra(LiveSessionService.SCREEN_RESULT, result.resultCode)
                putExtra(LiveSessionService.SCREEN_DATA, result.data)
            }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }
    private val pickUpdateApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { updater.chooseApk(uri) }
    }

    private fun openLive() {
        permissionError = false
        showLive = true
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        if (live.active == null) LiveSessionService.command(this, LiveSessionService.START)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics.installCrashHandler()
        showLive = live.active != null
        bubblePermission = Settings.canDrawOverlays(this)
        accessibilityEnabled = OsoneAccessibilityService.active != null
        volumeControlStream = if (showLive) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        darkMode = getSharedPreferences("osone_config", 0).getBoolean("dark_mode", false)
        speech = TextToSpeech(this, this)
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var readAloud by remember { mutableStateOf(false) }
            var showDiagnostics by remember { mutableStateOf(false) }
            val purple = Color(0xFF7044D7)
            MaterialTheme(colorScheme = if (darkMode) darkColorScheme(
                primary = Color(0xFFC4AAFF), background = Color(0xFF101018), surface = Color(0xFF101018))
                else lightColorScheme(primary = purple)) {
                Surface(Modifier.fillMaxSize()) {
                    if (showLive) LiveScreen(live, diagnostics, bubblePermission, accessibilityEnabled,
                        onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onWriting = { showWriting = true; showLive = false },
                        onOverlay = {
                            if (Settings.canDrawOverlays(this)) LiveSessionService.command(this, LiveSessionService.OVERLAY_ON)
                            else {
                                overlayRequested = true
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")))
                            }
                        },
                        onShareScreen = {
                            val manager = getSystemService(MediaProjectionManager::class.java)
                            screenPermission.launch(manager.createScreenCaptureIntent())
                        },
                        onStopScreen = { LiveSessionService.command(this, LiveSessionService.SCREEN_STOP) },
                        onEnd = { LiveSessionService.command(this, LiveSessionService.STOP); showLive = false;
                            volumeControlStream = AudioManager.STREAM_MUSIC },
                        onDiagnostics = { showDiagnostics = true }, onBack = { showLive = false;
                            volumeControlStream = AudioManager.STREAM_MUSIC })
                    else if (showSettings) SettingsScreen(viewModel, live, updater, darkMode,
                        onDarkMode = { enabled ->
                            darkMode = enabled
                            getSharedPreferences("osone_config", 0).edit().putBoolean("dark_mode", enabled).apply()
                        }, onBack = { showSettings = false },
                        onPickUpdate = { pickUpdateApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        diagnostics = diagnostics, onDiagnostics = { showDiagnostics = true })
                    else if (showWriting) WritingScreen(writing, live, diagnostics,
                        onDiagnostics = { showDiagnostics = true },
                        onBack = { showWriting = false },
                        onLive = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                openLive()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        })
                    else ChatScreen(viewModel, onAttach = { pickFile.launch(arrayOf("*/*")) }, onMic = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                openLive()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }, permissionError = permissionError, onSettings = { showSettings = true },
                        onWriting = { showWriting = true }, diagnostics = diagnostics,
                        onDiagnostics = { showDiagnostics = true }, readAloud = readAloud,
                        onReadAloud = { readAloud = !readAloud }, onAnswer = { answer ->
                            if (readAloud) speech?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "osone_resposta")
                        })
                    if (showDiagnostics) DiagnosticsDialog(diagnostics, onClose = { showDiagnostics = false })
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) speech?.language = Locale("pt", "BR")
    }
    override fun onResume() {
        super.onResume()
        bubblePermission = Settings.canDrawOverlays(this)
        accessibilityEnabled = OsoneAccessibilityService.active != null
        updater.resumeAfterPermission()
        if (overlayRequested && bubblePermission && live.active != null)
            LiveSessionService.command(this, LiveSessionService.OVERLAY_ON)
        overlayRequested = false
    }
    override fun onDestroy() { speech?.stop(); speech?.shutdown(); super.onDestroy() }
}

@Composable
private fun ChatScreen(viewModel: OsoneViewModel, onMic: () -> Unit, onAttach: () -> Unit, permissionError: Boolean,
    onSettings: () -> Unit, onWriting: () -> Unit, diagnostics: AppDiagnostics, onDiagnostics: () -> Unit,
    readAloud: Boolean, onReadAloud: () -> Unit,
    onAnswer: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics { contentDescription = "Abrir menu" }) {
                    Text("☰", style = MaterialTheme.typography.headlineSmall)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Aba de Escrita") }, onClick = {
                        menuExpanded = false; onWriting()
                    })
                }
            }
            Image(painterResource(R.drawable.ostie_orb), contentDescription = "Logo OSTIE",
                modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(8.dp))
            Text("OSTIE", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            DiagnosticsDot(diagnostics, onDiagnostics)
            IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "Ajustes" }) {
                Text("⚙", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Seu assistente no Android", color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onReadAloud) { Text(if (readAloud) "🔊 Voz ligada" else "🔇 Voz desligada") }
        }
        Text("Cérebro do chat: ${viewModel.selectedChatLabel}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = scroll, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.messages) { message ->
                val user = message.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp), modifier = if (user) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth(0.90f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(if (user) "Você" else "OSTIE", style = MaterialTheme.typography.labelMedium)
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
                        Text("OSTIE · respondendo", style = MaterialTheme.typography.labelMedium)
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
        viewModel.error?.let { message ->
            Text(if (viewModel.attachment != null) message else "Falha no chat · toque no indicador vermelho para ver o erro.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (permissionError) Text("Permita o microfone para conversar por voz.", color = MaterialTheme.colorScheme.error)
        viewModel.attachment?.let { selected ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📎 ${selected.name} · analisado pelo Gemini", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                TextButton(onClick = viewModel::removeAttachment) { Text("Remover") }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Escreva sua mensagem") },
                modifier = Modifier.weight(1f), maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (viewModel.send(draft, onAnswer)) draft = "" }))
            Column {
                TextButton(onClick = onAttach, enabled = !viewModel.busy) { Text("📎 Arquivo") }
                TextButton(onClick = onMic) { Text("🎙️ Live") }
                Button(onClick = { if (viewModel.send(draft, onAnswer)) draft = "" },
                    enabled = (draft.isNotBlank() || viewModel.attachment != null) && !viewModel.busy) { Text("Enviar") }
            }
        }
    }
}

@Composable
private fun WritingScreen(workspace: WritingWorkspace, live: LiveVoiceViewModel,
    diagnostics: AppDiagnostics, onDiagnostics: () -> Unit, onBack: () -> Unit, onLive: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var preview by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Conversa") }
            Spacer(Modifier.weight(1f))
            DiagnosticsDot(diagnostics, onDiagnostics)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ostie_orb), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(8.dp))
            Text("Aba de Escrita", style = MaterialTheme.typography.headlineSmall)
        }
        Text(if (live.connected) "Live ligado · peça ao OSTIE um texto ou código."
            else "Peça ao Gemini Live um texto ou código para preencher esta aba.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onLive) { Text(if (live.connected) "🎙️ Voltar à conversa Live" else "🎙️ Iniciar Live") }
        if (!live.localToolsAvailable && live.connected) Text("Este modelo desativou as ferramentas; tente outro modelo Live.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text(workspace.title, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Visualizar como HTML", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(checked = workspace.format == "html", onCheckedChange = { workspace.updateFormat(if (it) "html" else "text") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { preview = workspace.content },
                enabled = workspace.format == "html" && workspace.content.isNotBlank()) { Text("▶ Play") }
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("OSTIE · Aba de Escrita", workspace.content))
                message = "Texto copiado."
            }, enabled = workspace.content.isNotBlank()) { Text("Copiar") }
            OutlinedButton(onClick = { confirmDelete = true }, enabled = workspace.content.isNotBlank()) { Text("Apagar") }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (preview != null) {
            TextButton(onClick = { preview = null }) { Text("← Voltar ao editor") }
            AndroidView(factory = { activity -> WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
                }
                setBackgroundColor(android.graphics.Color.WHITE)
            } }, update = { web ->
                web.loadDataWithBaseURL(null, preview.orEmpty(), "text/html", "UTF-8", null)
            }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxWidth().weight(1f))
        } else {
            OutlinedTextField(value = workspace.content, onValueChange = workspace::updateContent,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("Escreva ou edite aqui") }, placeholder = {
                    Text("Converse com o Gemini Live e peça: crie um texto ou um código HTML na Aba de Escrita.")
                })
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Apagar o documento?") }, text = { Text("O texto desta aba será apagado do aparelho.") },
        confirmButton = { TextButton(onClick = { workspace.clear(); preview = null; message = ""; confirmDelete = false }) { Text("Apagar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
}

@Composable
private fun SettingsScreen(viewModel: OsoneViewModel, live: LiveVoiceViewModel, updater: AppUpdater,
    darkMode: Boolean, onDarkMode: (Boolean) -> Unit, onBack: () -> Unit,
    onPickUpdate: () -> Unit, diagnostics: AppDiagnostics, onDiagnostics: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("← Conversa") }
            DiagnosticsDot(diagnostics, onDiagnostics)
        }
        Text("Configurações", style = MaterialTheme.typography.headlineMedium)
        Text("Atualizações", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = updater.feedUrl, onValueChange = updater::updateFeedUrl,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Canal HTTPS de atualizações (latest.json)") })
        Button(onClick = { scope.launch { updater.check() } }, enabled = !updater.busy) {
            Text("Procurar atualização")
        }
        updater.available?.let { release ->
            Text("Versão ${release.versionName} disponível. ${release.notes}")
            Button(onClick = { scope.launch { updater.downloadAndInstall() } }, enabled = !updater.busy) {
                Text("Baixar e instalar atualização")
            }
        }
        OutlinedButton(onClick = onPickUpdate, enabled = !updater.busy) { Text("Escolher APK já baixado") }
        Text(updater.status, style = MaterialTheme.typography.bodySmall)
        Text("O Android só atualiza um app se o APK novo tiver o mesmo identificador e assinatura. A instalação pede sua confirmação.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
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
        Text("A voz pode continuar fora do app pela notificação e pela bolha opcional. Compartilhar a tela exige autorização do Android em cada sessão.", style = MaterialTheme.typography.bodySmall)
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
        OutlinedButton(onClick = { expanded = true }) { Text("Modelo: ${GroqModel.label(viewModel.groqModelId)}  ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (viewModel.availableGroqModels.ifEmpty { GroqModel.entries.map { it.id } }).forEach { id ->
                DropdownMenuItem(text = { Text(GroqModel.label(id)) }, onClick = {
                    viewModel.selectGroqModel(id); expanded = false
                })
            }
        }
    }
    TextButton(onClick = viewModel::refreshGroqModels, enabled = !viewModel.groqLoading) {
        Text(if (viewModel.groqLoading) "Consultando modelos…" else "Consultar modelos disponíveis nesta chave")
    }
    viewModel.groqModelStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun OpenRouterModelField(viewModel: OsoneViewModel) {
    var draft by remember { mutableStateOf(viewModel.openRouterModel) }
    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("ID do modelo OpenRouter") })
    Button(onClick = { viewModel.updateOpenRouterModel(draft); draft = viewModel.openRouterModel }) { Text("Salvar modelo") }
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
private fun LiveScreen(live: LiveVoiceViewModel, diagnostics: AppDiagnostics, bubblePermission: Boolean,
    accessibilityEnabled: Boolean, onAccessibility: () -> Unit,
    onWriting: () -> Unit,
    onOverlay: () -> Unit, onShareScreen: () -> Unit, onStopScreen: () -> Unit,
    onEnd: () -> Unit, onDiagnostics: () -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live.screenSharing) {
        val started = System.currentTimeMillis()
        var warned = false
        while (live.screenSharing) {
            clock = System.currentTimeMillis()
            val stalled = live.connected && clock - maxOf(started, live.lastScreenFrameAt) > 6000
            if (stalled && !warned) {
                diagnostics.record("Tela Live", if (live.screenFramesCaptured == 0)
                    "Projeção autorizada, mas o Android não forneceu imagens ao capturador."
                    else "O Android gerou imagens, mas nenhuma foi enviada à conexão Live. Capturadas: ${live.screenFramesCaptured}; descartadas: ${live.screenFramesSkipped}.")
                warned = true
            }
            if (!stalled) warned = false
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Chat") }
            Spacer(Modifier.weight(1f))
            Text("OSTIE LIVE", style = MaterialTheme.typography.titleLarge)
            DiagnosticsDot(diagnostics, onDiagnostics)
        }
        TextButton(onClick = onWriting) { Text("☰ Aba de Escrita") }
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
        if (!live.connected && live.attempts.isNotEmpty()) Text("Falha na conexão · veja o indicador vermelho.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (live.active != null && live.selected != live.active)
            Text("Fallback: ${live.active?.label}", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))
        Text("Áudio direto · sem transcrição", style = MaterialTheme.typography.bodySmall)
        Text(if (live.localToolsAvailable) "Agente Android: abre apps e consulta bateria. Com Acessibilidade, lê e usa controles dos apps."
            else "Este modelo aceitou somente voz; ações locais indisponíveis nesta sessão.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onAccessibility) {
            Text(if (accessibilityEnabled) "Acessibilidade ativada · gerenciar" else "Ativar controle do celular · Acessibilidade")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOverlay, enabled = live.active != null) {
            Text(if (bubblePermission) "Mostrar bolha sobre outros apps" else "Permitir bolha flutuante")
        }
        OutlinedButton(onClick = if (live.screenSharing) onStopScreen else onShareScreen,
            enabled = live.active != null) {
            Text(if (live.screenSharing) "Parar de mostrar tela" else "Mostrar tela ao OSTIE")
        }
        if (live.screenSharing) {
            val age = if (live.lastScreenFrameAt == 0L) Long.MAX_VALUE
                else clock - live.lastScreenFrameAt
            Text(if (!live.connected) "Tela autorizada; aguardando a conexão Live."
                else if (age > 3500) "Tela autorizada, mas nenhum quadro recente foi enviado. Veja o diagnóstico."
                else "Tela ativa · ${live.screenFramesCaptured} capturados · ${live.screenFramesSent} enviados · último há ${maxOf(0L, age) / 1000} s" +
                    if (live.screenFramesSkipped > 0) " · ${live.screenFramesSkipped} descartados" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (live.connected && age > 3500) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("Pergunte por voz sobre o que está na tela. O Live recebe até 1 quadro por segundo.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Pode sair do app: a conversa continua até tocar em Encerrar ou na notificação.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = live::toggleMute, enabled = live.connected) {
                Text(if (live.muted) "Ativar microfone" else "Silenciar")
            }
            Button(onClick = { if (live.active == null) LiveSessionService.command(context, LiveSessionService.START) else onEnd() }) {
                Text(if (live.active == null) "Tentar novamente" else "Encerrar")
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun DiagnosticsDot(diagnostics: AppDiagnostics, onOpen: () -> Unit) {
    IconButton(onClick = onOpen, modifier = Modifier.semantics {
        contentDescription = if (diagnostics.unread > 0) "Erros novos: ${diagnostics.unread}. Abrir diagnóstico"
            else "Estado do aplicativo: sem erros novos. Abrir diagnóstico"
    }) {
        Canvas(Modifier.size(13.dp)) {
            drawCircle(if (diagnostics.unread > 0) Color(0xFFE15555) else Color(0xFF44BD77))
        }
    }
}

@Composable
private fun DiagnosticsDialog(diagnostics: AppDiagnostics, onClose: () -> Unit) {
    LaunchedEffect(Unit) { diagnostics.markRead() }
    AlertDialog(onDismissRequest = onClose, title = { Text("Diagnóstico") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (diagnostics.events.isEmpty()) Text("Nenhuma falha registrada neste aparelho.")
                diagnostics.events.asReversed().forEach { event ->
                    Column {
                        Text("${SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR")).format(event.timestamp)} · ${event.area}",
                            style = MaterialTheme.typography.labelMedium)
                        Text(event.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = diagnostics::clear) { Text("Limpar log") } })
}

@Composable
private fun VoiceOrb(input: Float, output: Float, connected: Boolean) {
    val motion = rememberInfiniteTransition(label = "respiração do orbe")
    val breath by motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2100, easing = EaseInOutSine), RepeatMode.Reverse), label = "respiração")
    val energy by animateFloatAsState(maxOf(input, output).coerceIn(0f, 1f),
        animationSpec = tween(100), label = "energia")
    val core = if (output > input) Color(0xFF3ED6FF) else Color(0xFF488DFF)
    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            val radius = size.minDimension * (0.34f + breath * 0.015f + energy * 0.04f)
            drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.25f), core.copy(alpha = 0.08f),
                Color.Transparent), center = center, radius = radius * 1.4f), radius = radius * 1.4f)
            drawCircle(core.copy(alpha = if (connected) 0.68f else 0.18f),
                radius = radius * (1.12f + energy * 0.08f), style = Stroke(width = 3.dp.toPx()))
            drawCircle(core.copy(alpha = 0.28f), radius = radius * 1.23f,
                style = Stroke(width = 2.dp.toPx()))
        }
        Image(painterResource(R.drawable.ostie_orb), contentDescription = "OSTIE ouvindo e falando",
            modifier = Modifier.size((178f + energy * 20f).dp))
    }
}
