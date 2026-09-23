package com.osone.app

import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WritingScreen(workspace: WritingWorkspace, live: LiveVoiceViewModel,
    diagnostics: AppDiagnostics, onDiagnostics: () -> Unit, onBack: () -> Unit, onLive: () -> Unit) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasContent = workspace.content.isNotBlank()
    val html = workspace.format == "html"
    // Documento HTML/SVG recém-enviado pelo OSTIE abre direto no preview.
    LaunchedEffect(workspace.revision) {
        if (workspace.previewPending && workspace.content.isNotBlank()) {
            preview = DocumentPreview.page(workspace.content)
            workspace.consumePreview()
        } else if (preview != null && workspace.format != "html") preview = null
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OstieTopBar(title = "Aba de Escrita", subtitle = workspace.title,
            navigation = { BarIcon(OstieIcons.Back, "Voltar", { if (preview != null) preview = null else onBack() }) }) {
            BarIcon(OstieIcons.Wave, if (live.connected) "Voltar à conversa Live" else "Iniciar Live", onLive,
                tint = if (live.connected) OstieColors.Success else MaterialTheme.colorScheme.onSurface)
            DiagnosticsDot(diagnostics, onDiagnostics)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val htmlMark: (@Composable () -> Unit)? = if (html) {
                { Icon(OstieIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null
            FilterChip(selected = html, onClick = { workspace.updateFormat(if (html) "text" else "html") },
                label = { Text("HTML / SVG") }, leadingIcon = htmlMark)
            Spacer(Modifier.weight(1f))
            if (preview == null) BarIcon(OstieIcons.Play, "Visualizar HTML ou SVG",
                { preview = DocumentPreview.page(workspace.content) },
                enabled = (html || DocumentPreview.looksLikeMarkup(workspace.content)) && hasContent, tint = MaterialTheme.colorScheme.primary)
            else BarIcon(OstieIcons.Close, "Fechar visualização", { preview = null })
            BarIcon(OstieIcons.Copy, "Copiar texto", {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("OSTIE · Aba de Escrita", workspace.content))
                Toast.makeText(context, "Texto copiado.", Toast.LENGTH_SHORT).show()
            }, enabled = hasContent)
            BarIcon(OstieIcons.Delete, "Apagar documento", { confirmDelete = true }, enabled = hasContent,
                tint = MaterialTheme.colorScheme.error)
        }
        if (live.connected && !live.localToolsAvailable) Text("Este modelo desativou as ferramentas; tente outro modelo Live.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            val current = preview
            if (current != null) {
                Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxSize()) {
                    AndroidView(factory = { activity -> WebView(activity).apply {
                        settings.javaScriptEnabled = true
                        settings.blockNetworkLoads = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
                        }
                        setBackgroundColor(android.graphics.Color.WHITE)
                    } }, update = { web ->
                        // Recarrega só quando o documento muda; recomposições não reiniciam a página.
                        if (web.tag != current) {
                            web.tag = current
                            web.loadDataWithBaseURL("about:blank", current, "text/html", "UTF-8", null)
                        }
                    }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize())
                }
            } else {
                OutlinedTextField(value = workspace.content, onValueChange = workspace::updateContent,
                    modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large,
                    placeholder = {
                        Text(if (live.connected) "Peça ao OSTIE por voz: escreva um texto ou crie uma página HTML aqui."
                            else "Escreva aqui, ou inicie o Live e peça ao OSTIE um texto ou código.")
                    })
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Apagar o documento?") }, text = { Text("O texto desta aba será apagado do aparelho.") },
        confirmButton = { TextButton(onClick = { workspace.clear(); preview = null; confirmDelete = false }) { Text("Apagar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
}
