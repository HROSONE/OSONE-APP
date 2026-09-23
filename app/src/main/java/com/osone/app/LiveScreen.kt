package com.osone.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(live: LiveVoiceViewModel, codeAuthor: CodeAuthor, diagnostics: AppDiagnostics, bubblePermission: Boolean,
    accessibilityEnabled: Boolean, onAccessibility: () -> Unit,
    onWriting: () -> Unit,
    onOverlay: () -> Unit, onShareScreen: () -> Unit, onStopScreen: () -> Unit,
    onCameraToggle: () -> Unit, onCameraSwitch: () -> Unit,
    onEnd: () -> Unit, onDiagnostics: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var showPanel by remember { mutableStateOf(false) }
    LaunchedEffect(live.screenSharing, live.cameraSharing) {
        val started = System.currentTimeMillis()
        var screenWarned = false
        var cameraWarned = false
        while (live.screenSharing || live.cameraSharing) {
            clock = System.currentTimeMillis()
            val stalled = live.screenSharing && live.connected && clock - maxOf(started, live.lastScreenFrameAt) > 6000
            if (stalled && !screenWarned) {
                diagnostics.record("Tela Live", if (live.screenFramesCaptured == 0)
                    "Projeção autorizada, mas o Android não forneceu imagens ao capturador."
                    else "O Android gerou imagens, mas nenhuma foi enviada à conexão Live. Capturadas: ${live.screenFramesCaptured}; descartadas: ${live.screenFramesSkipped}.")
                screenWarned = true
            }
            if (!stalled) screenWarned = false
            val cameraStalled = live.cameraSharing && live.connected &&
                clock - maxOf(started, live.lastCameraFrameAt) > 6000
            if (cameraStalled && !cameraWarned) {
                diagnostics.record("Câmera Live", if (live.cameraFramesCaptured == 0)
                    "Câmera ativa, mas nenhum quadro foi capturado." else
                    "Imagens da câmera capturadas, mas não enviadas. Capturadas: ${live.cameraFramesCaptured}; descartadas: ${live.cameraFramesSkipped}.")
                cameraWarned = true
            }
            if (!cameraStalled) cameraWarned = false
            delay(1000)
        }
    }
    val colors = MaterialTheme.colorScheme
    val session = live.active != null
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(colors.primary.copy(alpha = 0.10f), colors.background, colors.background)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            OstieTopBar(title = "OSTIE Live", subtitle = "${(live.active ?: live.selected).label} · ${live.voice}",
                navigation = { BarIcon(OstieIcons.Back, "Voltar ao chat", onBack) }) {
                BarIcon(OstieIcons.Document, "Aba de Escrita", onWriting)
                DiagnosticsDot(diagnostics, onDiagnostics)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center) {
                val orbSize = minOf(maxWidth, maxHeight) * 0.9f
                VoiceOrb(live.inputLevel, live.outputLevel, live.connected, live.muted,
                    Modifier.size(orbSize))
                if (live.cameraSharing) CameraPip(live, onCameraSwitch,
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp))
            }
            Text(live.status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            val note = when {
                !live.connected && live.attempts.isNotEmpty() -> "Falha na conexão · toque no indicador para detalhes"
                live.muted -> "Microfone silenciado"
                session && live.selected != live.active -> "Usando reserva: ${live.active?.label}"
                live.connected && !live.localToolsAvailable -> "Somente voz nesta sessão · ações locais indisponíveis"
                else -> null
            }
            note?.let {
                Spacer(Modifier.height(4.dp))
                Hint(it, if (!live.connected && live.attempts.isNotEmpty()) colors.error else colors.onSurfaceVariant)
            }
            ShareStatus(live, clock)
            Spacer(Modifier.height(20.dp))
            ControlDock(live, session,
                onMute = live::toggleMute,
                onCamera = onCameraToggle,
                onScreen = if (live.screenSharing) onStopScreen else onShareScreen,
                onPanel = { showPanel = true },
                onEnd = { if (session) onEnd() else LiveSessionService.command(context, LiveSessionService.START) })
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showPanel) {
        ModalBottomSheet(onDismissRequest = { showPanel = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surfaceContainerLow) {
            LivePanel(live, codeAuthor, session, bubblePermission, accessibilityEnabled,
                onWriting = { showPanel = false; onWriting() },
                onOverlay = { showPanel = false; onOverlay() },
                onAccessibility = { showPanel = false; onAccessibility() })
        }
    }
}

@Composable
private fun ControlDock(live: LiveVoiceViewModel, session: Boolean, onMute: () -> Unit, onCamera: () -> Unit,
    onScreen: () -> Unit, onPanel: () -> Unit, onEnd: () -> Unit) {
    Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction(if (live.muted) OstieIcons.MicOff else OstieIcons.Mic,
                if (live.muted) "Ativar microfone" else "Silenciar microfone", onMute,
                enabled = live.connected, container = if (live.muted) OstieColors.Danger else null, size = 52.dp)
            RoundAction(if (live.cameraSharing) OstieIcons.Camera else OstieIcons.CameraOff,
                if (live.cameraSharing) "Parar câmera" else "Mostrar câmera ao OSTIE", onCamera,
                active = live.cameraSharing, enabled = session, size = 52.dp)
            RoundAction(OstieIcons.Screen,
                if (live.screenSharing) "Parar de mostrar tela" else "Mostrar tela ao OSTIE", onScreen,
                active = live.screenSharing, enabled = session, size = 52.dp)
            RoundAction(OstieIcons.Tune, "Painel da sessão", onPanel, size = 52.dp)
            RoundAction(if (session) OstieIcons.CallEnd else OstieIcons.Call,
                if (session) "Encerrar conversa" else "Iniciar conversa", onEnd,
                container = if (session) OstieColors.Danger else OstieColors.Success, size = 60.dp)
        }
    }
}

/** Indicadores compactos de tela/câmera; detalhes completos ficam no diagnóstico. */
@Composable
private fun ShareStatus(live: LiveVoiceViewModel, clock: Long) {
    if (!live.screenSharing && !live.cameraSharing) return
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live.cameraSharing) StatusChip(OstieIcons.Camera, "Câmera", live.connected,
            live.lastCameraFrameAt, live.cameraFramesSent, clock)
        if (live.screenSharing) StatusChip(OstieIcons.Screen, "Tela", live.connected,
            live.lastScreenFrameAt, live.screenFramesSent, clock)
    }
}

@Composable
private fun StatusChip(icon: ImageVector, label: String, connected: Boolean, lastFrame: Long, sent: Int, clock: Long) {
    val age = if (lastFrame == 0L) Long.MAX_VALUE else clock - lastFrame
    val stalled = connected && age > 3500
    val color = when {
        !connected -> MaterialTheme.colorScheme.onSurfaceVariant
        stalled -> MaterialTheme.colorScheme.error
        else -> OstieColors.Success
    }
    val detail = when {
        !connected -> "aguardando"
        stalled -> "sem envio"
        else -> "$sent enviados"
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("$label · $detail", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CameraPip(live: LiveVoiceViewModel, onSwitch: () -> Unit, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.Black, shadowElevation = 8.dp,
        modifier = modifier.size(width = 112.dp, height = 150.dp)) {
        Box {
            live.cameraPreview?.let { preview ->
                Image(bitmap = preview.asImageBitmap(), contentDescription = "Imagem atual da câmera",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            }
            Surface(onClick = onSwitch, shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(34.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(OstieIcons.CameraSwitch, contentDescription = if (live.cameraFront)
                        "Usar câmera traseira" else "Usar câmera frontal", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LivePanel(live: LiveVoiceViewModel, codeAuthor: CodeAuthor, session: Boolean, bubblePermission: Boolean,
    accessibilityEnabled: Boolean, onWriting: () -> Unit, onOverlay: () -> Unit, onAccessibility: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        .padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Sessão", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LiveModelPicker(live)
        LiveVoicePicker(live)
        SettingSwitch("Trocar de modelo se falhar", live.fallback, {
            live.updateFallback(it)
            if (session) live.start()
        })
        SettingSwitch("Proteção de eco", live.echoGuard, live::updateEchoGuard,
            "No alto-falante, impede que a voz do OSTIE interrompa a si mesma. Fale mais alto para interromper. Com fones, fica desligada sozinha.")
        CodeAuthorPicker(codeAuthor)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Ferramentas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        PanelRow(OstieIcons.Document, "Aba de Escrita", "Textos e códigos pedidos por voz", onWriting)
        PanelRow(OstieIcons.Bubble, "Bolha flutuante",
            if (bubblePermission) "Mostrar sobre outros apps" else "Permitir sobreposição no Android",
            onOverlay, enabled = session)
        PanelRow(OstieIcons.Accessibility, "Controle do celular",
            if (accessibilityEnabled) "Acessibilidade ativada · gerenciar" else "Ativar em Acessibilidade", onAccessibility,
            trailing = if (accessibilityEnabled) OstieColors.Success else null)
        Hint("A conversa continua fora do app até você encerrar aqui, na bolha ou na notificação. Tela e câmera enviam até 1 imagem por segundo e nada é gravado.")
    }
}

@Composable
private fun PanelRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit,
    enabled: Boolean = true, trailing: Color? = null) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(enabled = enabled, onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
                Hint(subtitle)
            }
            trailing?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(it)) }
            Icon(OstieIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VoiceOrb(input: Float, output: Float, connected: Boolean, muted: Boolean, modifier: Modifier) {
    val motion = rememberInfiniteTransition(label = "respiração do orbe")
    val breath by motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2100, easing = EaseInOutSine), RepeatMode.Reverse), label = "respiração")
    val spin by motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "ondas")
    val energy by animateFloatAsState(maxOf(if (muted) 0f else input, output).coerceIn(0f, 1f),
        animationSpec = tween(100), label = "energia")
    val core = if (output > input) OstieColors.Cyan else OstieColors.Blue
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * (0.30f + breath * 0.012f + energy * 0.04f)
            drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.30f), core.copy(alpha = 0.08f),
                Color.Transparent), center = center, radius = radius * 1.6f), radius = radius * 1.6f)
            if (connected) for (wave in 0 until 3) {
                val phase = (spin + wave / 3f) % 1f
                drawCircle(core.copy(alpha = (1f - phase) * (0.18f + energy * 0.5f)),
                    radius = radius * (1.08f + phase * 0.5f), style = Stroke(width = 2.dp.toPx()))
            }
            drawCircle(core.copy(alpha = if (connected) 0.7f else 0.2f),
                radius = radius * (1.06f + energy * 0.06f), style = Stroke(width = 3.dp.toPx()))
        }
        Image(painterResource(R.drawable.ostie_orb), contentDescription = "OSTIE ouvindo e falando",
            modifier = Modifier.fillMaxSize(0.58f + energy * 0.06f))
    }
}
