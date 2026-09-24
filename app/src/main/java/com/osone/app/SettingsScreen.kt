package com.osone.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: OsoneViewModel, live: LiveVoiceViewModel, codeAuthor: CodeAuthor, updater: AppUpdater,
    darkMode: Boolean, onDarkMode: (Boolean) -> Unit, onBack: () -> Unit,
    onPickUpdate: () -> Unit, diagnostics: AppDiagnostics, onDiagnostics: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OstieTopBar(title = "Ajustes", navigation = { BarIcon(OstieIcons.Back, "Voltar", onBack) }) {
            DiagnosticsDot(diagnostics, onDiagnostics)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard("Aparência", OstieIcons.Settings) {
                SettingSwitch("Modo noturno", darkMode, onDarkMode)
            }
            SectionCard("Chaves de API", OstieIcons.Shield) {
                Hint("Cada chave fica criptografada no aparelho e só vai ao respectivo serviço. Gemini é obrigatória para o Live.")
                ProviderKeyField(viewModel, ChatProvider.GEMINI)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProviderKeyField(viewModel, ChatProvider.OPENROUTER)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProviderKeyField(viewModel, ChatProvider.GROQ)
                viewModel.keyStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (viewModel.keySaveError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            SectionCard("Pesquisa Google", OstieIcons.Search) {
                SettingSwitch("Pesquisar na web quando precisar", viewModel.googleSearch, {
                    viewModel.updateGoogleSearch(it)
                    if (live.active != null) live.start() // O Live só recebe ferramentas ao conectar.
                }, "Notícias, preços, clima e fatos recentes. Usa a mesma chave Gemini, no chat escrito (Gemini) e no Live. Respostas do chat mostram as fontes.")
                if (viewModel.provider != ChatProvider.GEMINI)
                    Hint("Groq e OpenRouter não têm Pesquisa Google; no chat escrito ela só funciona com Gemini.")
            }
            SectionCard("Chat escrito", OstieIcons.Chat) {
                OptionPicker("Provedor", viewModel.provider.label, ChatProvider.entries, { it.label }, viewModel::selectProvider)
                when (viewModel.provider) {
                    ChatProvider.GEMINI -> {
                        OptionPicker("Modelo", viewModel.selectedModel.label, ChatModel.entries, { it.label }, viewModel::selectModel)
                        OptionPicker("Raciocínio", viewModel.thinkingMode.label, ThinkingMode.entries, { it.label }, viewModel::selectThinking)
                        Hint("Rápido responde com menos espera; Profundo pode demorar mais.")
                        SettingSwitch("Trocar de modelo se falhar", viewModel.fallback, viewModel::updateFallback,
                            "Tenta versões Flash anteriores só se o modelo estiver indisponível ou sem cota.")
                    }
                    ChatProvider.GROQ -> GroqModelPicker(viewModel)
                    ChatProvider.OPENROUTER -> OpenRouterModelField(viewModel)
                }
            }
            SectionCard("Voz em tempo real", OstieIcons.Wave) {
                LiveModelPicker(live)
                LiveVoicePicker(live)
                SettingSwitch("Trocar de modelo se falhar", live.fallback, live::updateFallback)
                SettingSwitch("Proteção de eco", live.echoGuard, live::updateEchoGuard,
                    "Evita que o OSTIE se interrompa pelo próprio alto-falante. Com fones, não é aplicada.")
                CodeAuthorPicker(codeAuthor)
                Hint("O modelo de texto é o escolhido em Chat escrito acima.")
            }
            SectionCard("Atualizações", OstieIcons.ArrowDown) {
                var custom by remember { mutableStateOf(updater.feedUrl.isNotBlank()) }
                Text("Versão instalada: ${updater.installedVersionName}", style = MaterialTheme.typography.bodyMedium)
                SettingSwitch("Atualizar automaticamente", updater.autoUpdate, updater::updateAutoUpdate,
                    "Procura versões novas ao abrir o app e uma vez por dia, avisa por notificação e instala com um toque.")
                val release = updater.available
                if (release != null) Button(onClick = { scope.launch { updater.downloadAndInstall() } }, enabled = !updater.busy) {
                    Text("Instalar OSTIE ${release.versionName}")
                } else OutlinedButton(onClick = { scope.launch { updater.check() } }, enabled = !updater.busy) {
                    Text(if (updater.busy) "Procurando…" else "Procurar agora")
                }
                if (updater.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                if (updater.status.isNotBlank()) Hint(updater.status)
                release?.notes?.takeIf { it.isNotBlank() }?.let { Hint("Novidades: $it") }
                TextButton(onClick = { custom = !custom }) {
                    Text(if (custom) "Ocultar opções avançadas" else "Opções avançadas")
                }
                if (custom) {
                    OutlinedTextField(value = updater.feedUrl, onValueChange = updater::updateFeedUrl,
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium,
                        label = { Text("Canal personalizado (vazio = oficial)") })
                    OutlinedButton(onClick = onPickUpdate, enabled = !updater.busy) { Text("Instalar APK já baixado") }
                    Hint("O Android só atualiza se o APK tiver a mesma assinatura da versão instalada.")
                }
            }
            SectionCard("Dados", OstieIcons.Delete) {
                OutlinedButton(onClick = { confirmClear = true }, colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error)) { Text("Apagar conversa deste aparelho") }
                viewModel.error?.let { Hint(it, MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Apagar conversa?") }, text = { Text("O histórico local será removido.") },
        confirmButton = { TextButton(onClick = { viewModel.clearConversation(); confirmClear = false }) { Text("Apagar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
}

@Composable
private fun ProviderKeyField(viewModel: OsoneViewModel, provider: ChatProvider) {
    var key by remember(provider) { mutableStateOf("") }
    val saved = viewModel.configuredFor(provider)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(provider.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (saved) "Salva" else "Sem chave", style = MaterialTheme.typography.labelMedium,
            color = if (saved) OstieColors.Success else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(value = key, onValueChange = { key = it },
        placeholder = { Text(if (saved) "Cole uma nova chave para substituir" else "Cole sua chave") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (viewModel.saveKey(key, provider)) key = "" }, enabled = key.isNotBlank()) {
            Text(if (saved) "Atualizar" else "Salvar")
        }
        if (saved) TextButton(onClick = { viewModel.removeKey(provider) }) { Text("Remover") }
    }
}

@Composable
private fun GroqModelPicker(viewModel: OsoneViewModel) {
    OptionPicker("Modelo", GroqModel.label(viewModel.groqModelId),
        viewModel.availableGroqModels.ifEmpty { GroqModel.entries.map { it.id } }, { GroqModel.label(it) },
        viewModel::selectGroqModel)
    TextButton(onClick = viewModel::refreshGroqModels, enabled = !viewModel.groqLoading) {
        Text(if (viewModel.groqLoading) "Consultando modelos…" else "Consultar modelos desta chave")
    }
    viewModel.groqModelStatus?.let { Hint(it) }
}

@Composable
private fun OpenRouterModelField(viewModel: OsoneViewModel) {
    var draft by remember { mutableStateOf(viewModel.openRouterModel) }
    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(), label = { Text("ID do modelo OpenRouter") })
    Button(onClick = { viewModel.updateOpenRouterModel(draft); draft = viewModel.openRouterModel },
        enabled = draft.isNotBlank() && draft != viewModel.openRouterModel) { Text("Salvar modelo") }
    Hint("openrouter/free escolhe um modelo gratuito disponível. Outros IDs podem ter custo.")
}
