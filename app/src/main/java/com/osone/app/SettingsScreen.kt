package com.osone.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: OsoneViewModel, live: LiveVoiceViewModel, codeAuthor: CodeAuthor, updater: AppUpdater,
    memory: MemoryStore, darkMode: Boolean, onMemoryFolder: () -> Unit, onDarkMode: (Boolean) -> Unit, onBack: () -> Unit,
    onPickUpdate: () -> Unit, diagnostics: AppDiagnostics, onDiagnostics: () -> Unit,
    onWakeWord: (Boolean) -> Unit = {}, overlayAllowed: Boolean = true, onOverlay: () -> Unit = {},
    onKnowledgeFile: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OstieTopBar(title = "Ajustes", navigation = { BarIcon(OstieIcons.Back, "Voltar", onBack) }) {
            DiagnosticsDot(diagnostics, onDiagnostics)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard("Perfil e aparência", OstieIcons.Settings, collapsible = true, initiallyOpen = false) {
                val context = LocalContext.current
                val profile = remember { UserProfile.get(context) }
                var name by remember { mutableStateOf(profile.name) }
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    label = { Text("Como o OSTIE deve te chamar") },
                    placeholder = { Text("Vazio: ele pergunta na conversa") },
                    trailingIcon = { if (name.trim() != profile.name) TextButton(onClick = { profile.updateName(name) }) { Text("Salvar") } })
                SettingSwitch("Modo noturno", darkMode, onDarkMode)
                val notificationsOn = remember(profile.name, darkMode) {
                    context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notificações do app", style = MaterialTheme.typography.bodyLarge)
                        Hint(if (notificationsOn) "Ativadas: chamada Live, rotinas e atualizações."
                            else "Desativadas: rotinas e avisos de atualização não aparecem.",
                            if (notificationsOn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text(if (notificationsOn) "Gerenciar" else "Ativar") }
                }
            }
            SectionCard("Chaves de API", OstieIcons.Shield, collapsible = true, initiallyOpen = true) {
                Hint("Cada chave fica criptografada no aparelho e só vai ao respectivo serviço. Gemini é obrigatória para o Live.")
                ProviderKeyField(viewModel, ChatProvider.GEMINI)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProviderKeyField(viewModel, ChatProvider.OPENROUTER)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProviderKeyField(viewModel, ChatProvider.GROQ)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                JevField(viewModel)
                viewModel.keyStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (viewModel.keySaveError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            SectionCard("Pesquisa Google", OstieIcons.Search, collapsible = true, initiallyOpen = false) {
                SettingSwitch("Pesquisar na web quando precisar", viewModel.googleSearch, {
                    viewModel.updateGoogleSearch(it)
                    if (live.active != null) live.start() // O Live só recebe ferramentas ao conectar.
                }, "Notícias, preços, clima e fatos recentes. Usa a mesma chave Gemini, no chat escrito (Gemini) e no Live. Respostas do chat mostram as fontes.")
                if (viewModel.provider != ChatProvider.GEMINI)
                    Hint("Groq e OpenRouter não têm Pesquisa Google própria: com as ações no chat ligadas, eles pesquisam pela API de busca ou pela chave Gemini.")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SearchApiFields(viewModel, onChanged = { if (live.active != null) live.start() })
            }
            SectionCard("Chat escrito", OstieIcons.Chat, collapsible = true, initiallyOpen = true) {
                OptionPicker("Provedor", viewModel.provider.label, ChatProvider.entries, { it.label }, viewModel::selectProvider)
                val appContext = LocalContext.current
                val chatVoice = remember { ChatVoice.get(appContext) }
                OptionPicker("Voz das respostas", chatVoice.engine.label, ChatVoiceEngine.entries, { it.label },
                    chatVoice::selectEngine)
                if (chatVoice.engine != ChatVoiceEngine.ANDROID) {
                    OptionPicker("Voz", chatVoice.voice, LiveVoices.names, { it }, chatVoice::selectVoice)
                    Hint("Ligue o alto-falante no topo do chat para ouvir as respostas. Usa a chave Gemini; se ela falhar, a voz do Android lê no lugar.")
                }
                SettingSwitch("Ações no chat escrito", viewModel.chatTools, viewModel::updateChatTools,
                    "O chat cria alarmes, rotinas e anotações, lê a agenda e as notificações e prepara mensagens, como no Live. No Groq e no OpenRouter, depende de o modelo aceitar ferramentas; se não aceitar, ele só responde.")
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
            SectionCard("Voz em tempo real", OstieIcons.Wave, collapsible = true, initiallyOpen = false) {
                LiveModelPicker(live)
                LiveVoicePicker(live)
                SettingSwitch("Trocar de modelo se falhar", live.fallback, live::updateFallback)
                SettingSwitch("Proteção de eco", live.echoGuard, live::updateEchoGuard,
                    "Evita que o OSTIE se interrompa pelo próprio alto-falante. Com fones, não é aplicada.")
                CodeAuthorPicker(codeAuthor)
                Hint("O modelo de texto é o escolhido em Chat escrito acima.")
            }
            SectionCard("Escuta ativa", OstieIcons.Mic, collapsible = true, initiallyOpen = false) {
                SettingSwitch("Ouvir \"Ei, Ostie\"", WakeWord.on, onWakeWord,
                    "De qualquer tela, diga \"Ei, Ostie\" e o Live abre. O reconhecimento roda no celular e nenhum áudio sai dele até o Live abrir.")
                if (WakeWord.downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                WakeWord.status?.let { Hint(it) }
                if (WakeWord.on && !overlayAllowed) {
                    Hint("Sem \"Mostrar sobre outros apps\", o OSTIE só avisa por notificação quando ouve você; com a permissão, o Live abre direto.")
                    OutlinedButton(onClick = onOverlay) { Text("Permitir abrir sobre outros apps") }
                }
                if (WakeWord.on) Hint("Gasta um pouco mais de bateria e o Android mostra o ícone do microfone enquanto escuta. Depois de reiniciar o celular, abra o app uma vez para voltar a escutar.")
            }
            SectionCard("Atualizações", OstieIcons.ArrowDown, collapsible = true, initiallyOpen = false) {
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
            SectionCard("Memória do OSTIE", OstieIcons.Document, collapsible = true, initiallyOpen = false) {
                var editing by remember { mutableStateOf(false) }
                var draft by remember(memory.text) { mutableStateOf(memory.text) }
                if (memory.persistent) Text("Salva em ${memory.location}", color = OstieColors.Success,
                    style = MaterialTheme.typography.bodyMedium)
                else {
                    Hint("Hoje a memória está só dentro do app e some se ele for desinstalado. Permita a pasta para o OSTIE criar ${memory.location}, que ele reencontra sozinho após reinstalar.")
                    Button(onClick = onMemoryFolder) { Text("Permitir pasta de memória") }
                }
                Hint("O OSTIE anota por conta própria fatos, preferências, pessoas e projetos, e reorganiza as seções. Você pode ler e editar aqui ou em qualquer editor de texto.")
                val context = LocalContext.current
                var autoOrganize by remember { mutableStateOf(MemoryOrganizer.autoEnabled(context)) }
                LaunchedEffect(Unit) { MemoryOrganizer.load(context) }
                SettingSwitch("Organizar sozinha toda semana", autoOrganize, {
                    autoOrganize = it; MemoryOrganizer.schedule(context, it)
                }, "O modelo de texto junta repetições e tira o que venceu. Guarda a versão anterior em ${MemoryStore.BACKUP}.")
                MemoryOrganizer.status?.let { Hint(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = !editing; draft = memory.text }) {
                        Text(if (editing) "Fechar" else "Ver e editar")
                    }
                    TextButton(onClick = memory::refresh) { Text("Recarregar") }
                    TextButton(onClick = { MemoryOrganizer.runNow(context) }) { Text("Organizar agora") }
                }
                if (editing) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                        shape = MaterialTheme.shapes.medium, textStyle = MaterialTheme.typography.bodySmall)
                    Button(onClick = { memory.save(draft); editing = false }, enabled = draft != memory.text) {
                        Text("Salvar memória")
                    }
                }
            }
            val knowledgeContext = LocalContext.current
            KnowledgeSection(remember { KnowledgeBase.get(knowledgeContext) }, onKnowledgeFile)
            SectionCard("Dados", OstieIcons.Delete, collapsible = true, initiallyOpen = false) {
                Text("Cópia de segurança", style = MaterialTheme.typography.bodyLarge)
                Hint("Salva ajustes e rotinas em Documentos/OSTIE, para trocar de celular ou reinstalar. As chaves não entram.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::exportBackup) { Text("Exportar") }
                    OutlinedButton(onClick = viewModel::importBackup) { Text("Importar") }
                }
                viewModel.backupStatus?.let { Hint(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

/** API de busca do Google (Custom Search): chave do Cloud Console e ID do mecanismo de pesquisa (cx). */
@Composable
private fun SearchApiFields(viewModel: OsoneViewModel, onChanged: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var cx by remember(viewModel.searchApiCx) { mutableStateOf(viewModel.searchApiCx) }
    val saved = viewModel.searchApiConfigured
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("API de busca do Google", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (saved) "Salva" else "Opcional", style = MaterialTheme.typography.labelMedium,
            color = if (saved) OstieColors.Success else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hint("Chave da Custom Search API (Cloud Console) e ID do mecanismo (cx). Grátis até 100 buscas por dia; o Google encerra esta API em 01/01/2027.")
    OutlinedTextField(value = key, onValueChange = { key = it },
        placeholder = { Text(if (saved) "Cole uma nova chave para substituir" else "Chave da API") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = cx, onValueChange = { cx = it.trim() }, singleLine = true,
        label = { Text("ID do mecanismo (cx)") }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { if (viewModel.saveSearchApi(key, cx)) { key = ""; onChanged() } },
            enabled = cx.isNotBlank() && (key.isNotBlank() || (saved && cx != viewModel.searchApiCx))) {
            Text(if (saved) "Atualizar" else "Salvar")
        }
        if (saved) {
            OutlinedButton(onClick = viewModel::testSearchApi, enabled = !viewModel.searchApiTesting) {
                Text(if (viewModel.searchApiTesting) "Testando…" else "Testar")
            }
            TextButton(onClick = { viewModel.removeSearchApi(); onChanged() }) { Text("Remover") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    TavilyField(viewModel, onChanged)
    if (saved || viewModel.tavilyConfigured) SettingSwitch("Usar no lugar da pesquisa do Gemini", viewModel.searchApiFirst,
        { viewModel.updateSearchApiFirst(it); onChanged() },
        "Ligado: chat, Live e rotinas pesquisam pela API do Google, depois pela Tavily e, se as duas falharem, pelo Gemini. Desligado: elas só entram quando o Gemini não puder pesquisar.")
    viewModel.searchApiStatus?.let { Hint(it) }
}

/** Jev (TypeSafe AI): decisões rápidas para o chat e para rotinas de notificação. Chave e interruptor separados. */
@Composable
private fun JevField(viewModel: OsoneViewModel) {
    var key by remember { mutableStateOf("") }
    val saved = viewModel.jevConfigured
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Jev (TypeSafe)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (saved) "Salva" else "Opcional", style = MaterialTheme.typography.labelMedium,
            color = if (saved) OstieColors.Success else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hint("IA que não conversa, só decide, em menos de meio segundo. Crie a chave em console.typesafe.ai.")
    OutlinedTextField(value = key, onValueChange = { key = it },
        placeholder = { Text(if (saved) "Cole uma nova chave para substituir" else "Chave do Jev") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { if (viewModel.saveJev(key)) key = "" }, enabled = key.isNotBlank()) {
            Text(if (saved) "Atualizar" else "Salvar")
        }
        if (saved) {
            OutlinedButton(onClick = viewModel::testJev, enabled = !viewModel.jevTesting) {
                Text(if (viewModel.jevTesting) "Testando…" else "Testar")
            }
            TextButton(onClick = viewModel::removeJev) { Text("Remover") }
        }
    }
    if (saved) SettingSwitch("Usar o Jev", viewModel.jevOn, viewModel::updateJevOn,
        "Ligado, o texto das suas mensagens no chat (Groq e OpenRouter) e das notificações vigiadas por rotinas com filtro em frase " +
            "vai para a TypeSafe, que decide se precisa pesquisar, se precisa de ações e se a notificação combina. Desligado, nada sai.")
    viewModel.jevStatus?.let { Hint(it) }
}

/** Tavily: segunda opção de busca, grátis até 1.000 buscas por mês (chave criada em tavily.com). */
@Composable
private fun TavilyField(viewModel: OsoneViewModel, onChanged: () -> Unit) {
    var key by remember { mutableStateOf("") }
    val saved = viewModel.tavilyConfigured
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tavily", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (saved) "Salva" else "Opcional", style = MaterialTheme.typography.labelMedium,
            color = if (saved) OstieColors.Success else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hint("Grátis até 1.000 buscas por mês, sem cartão: crie a conta em tavily.com e cole a chave (tvly-…). Entra se a busca do Google falhar ou acabar.")
    OutlinedTextField(value = key, onValueChange = { key = it },
        placeholder = { Text(if (saved) "Cole uma nova chave para substituir" else "Chave da Tavily") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { if (viewModel.saveTavily(key)) { key = ""; onChanged() } }, enabled = key.isNotBlank()) {
            Text(if (saved) "Atualizar" else "Salvar")
        }
        if (saved) {
            OutlinedButton(onClick = viewModel::testTavily, enabled = !viewModel.searchApiTesting) {
                Text(if (viewModel.searchApiTesting) "Testando…" else "Testar")
            }
            TextButton(onClick = { viewModel.removeTavily(); onChanged() }) { Text("Remover") }
        }
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
