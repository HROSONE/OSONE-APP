package com.osone.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OsoneViewModel(application: Application) : AndroidViewModel(application) {
    private val secrets = SecureKeyStore(application)
    private val routerSecrets = SecureKeyStore(application, "key_openrouter")
    private val groqSecrets = SecureKeyStore(application, "key_groq")
    private val history = ConversationStore(application)
    private val diagnostics = AppDiagnostics.get(application)
    private val memory = MemoryStore.get(application)
    private val settings = application.getSharedPreferences("osone_config", 0)
    private val main = Handler(Looper.getMainLooper())
    var messages by androidx.compose.runtime.mutableStateOf(history.read())
        private set
    var busy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var error by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var selectedModel by androidx.compose.runtime.mutableStateOf(ChatModel.fromId(settings.getString("model", null)))
        private set
    var provider by androidx.compose.runtime.mutableStateOf(ChatProvider.fromValue(settings.getString("chat_provider", null)))
        private set
    var groqModelId by androidx.compose.runtime.mutableStateOf(settings.getString("groq_model", null) ?: GroqModel.GPT_OSS_20B.id)
        private set
    var availableGroqModels by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set
    var groqModelStatus by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var groqLoading by androidx.compose.runtime.mutableStateOf(false)
        private set
    var openRouterModel by androidx.compose.runtime.mutableStateOf(settings.getString("openrouter_model", "openrouter/free") ?: "openrouter/free")
        private set
    var fallback by androidx.compose.runtime.mutableStateOf(settings.getBoolean("chat_fallback", true))
        private set
    /** Pesquisa Google (grounding) nas respostas Gemini do chat escrito. */
    var googleSearch by androidx.compose.runtime.mutableStateOf(settings.getBoolean("google_search", true))
        private set
    var thinkingMode by androidx.compose.runtime.mutableStateOf(ThinkingMode.fromValue(settings.getString("thinking_mode", null)))
        private set
    var streamingText by androidx.compose.runtime.mutableStateOf("")
        private set
    /** Pesquisa na web feita antes da resposta (Groq e OpenRouter, pedidos sobre fatos atuais). */
    var searching by androidx.compose.runtime.mutableStateOf(false)
        private set
    var activeModel by androidx.compose.runtime.mutableStateOf<ChatModel?>(null)
        private set
    var activeTextModel by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var lastAnswerModel by androidx.compose.runtime.mutableStateOf<ChatModel?>(null)
        private set
    var keyStatus by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var keySaveError by androidx.compose.runtime.mutableStateOf(false)
        private set
    var attachment by androidx.compose.runtime.mutableStateOf<AttachmentRef?>(null)
        private set
    val selectedChatLabel get() = when (provider) {
        ChatProvider.GEMINI -> "Gemini · ${selectedModel.label}"
        ChatProvider.GROQ -> "Groq · ${GroqModel.label(groqModelId)}"
        ChatProvider.OPENROUTER -> "OpenRouter · $openRouterModel"
    }

    private fun storeFor(value: ChatProvider): SecureKeyStore = when (value) {
        ChatProvider.GEMINI -> secrets
        ChatProvider.OPENROUTER -> routerSecrets
        ChatProvider.GROQ -> groqSecrets
    }
    fun configuredFor(value: ChatProvider) = storeFor(value).read() != null

    fun selectProvider(value: ChatProvider) {
        provider = value
        settings.edit().putString("chat_provider", value.value).apply()
    }

    fun selectGroqModel(id: String) {
        groqModelId = id
        settings.edit().putString("groq_model", id).apply()
    }

    fun refreshGroqModels() {
        val key = groqSecrets.read()
        if (key == null) { groqModelStatus = "Salve uma chave Groq para consultar seus modelos."; return }
        groqLoading = true
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) { GroqCatalog().available(key) }
                availableGroqModels = models
                groqModelStatus = if (models.isEmpty()) "A conta não retornou modelos de chat."
                    else "${models.size} modelos encontrados para esta chave."
            } catch (failure: Exception) {
                diagnostics.record("Catálogo Groq", "Falhou a consulta de modelos (${failure.javaClass.simpleName}${if (failure is ChatProviderHttpException) " HTTP ${failure.status}" else ""}).")
                groqModelStatus = "Não foi possível consultar os modelos; veja o diagnóstico."
            } finally { groqLoading = false }
        }
    }

    fun updateOpenRouterModel(value: String) {
        openRouterModel = value.trim().take(120).ifEmpty { "openrouter/free" }
        settings.edit().putString("openrouter_model", openRouterModel).apply()
    }

    fun selectModel(value: ChatModel) {
        selectedModel = value
        settings.edit().putString("model", value.id).apply()
    }

    fun updateGoogleSearch(value: Boolean) {
        googleSearch = value
        settings.edit().putBoolean("google_search", value).apply()
    }

    /** API de busca do Google (Cloud Console): chave no cofre, ID do mecanismo (cx) nas preferências. */
    private val searchSecrets = SecureKeyStore(application, WebSearch.KEY_SLOT)
    var searchApiConfigured by androidx.compose.runtime.mutableStateOf(WebSearch.configured(application))
        private set
    var searchApiCx by androidx.compose.runtime.mutableStateOf(settings.getString(WebSearch.CX, null).orEmpty())
        private set
    var searchApiFirst by androidx.compose.runtime.mutableStateOf(settings.getBoolean(WebSearch.PREFER_API, true))
        private set
    var searchApiStatus by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var searchApiTesting by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** Chave vazia mantém a já salva (para trocar só o cx). */
    fun saveSearchApi(key: String, cx: String): Boolean {
        val cleanCx = cx.trim().take(120)
        val cleanKey = key.trim()
        if (cleanCx.isEmpty()) { searchApiStatus = "Informe o ID do mecanismo de pesquisa (cx)."; return false }
        if (cleanKey.isEmpty() && searchSecrets.read() == null) { searchApiStatus = "Cole a chave da API do Cloud Console."; return false }
        val saved = try { cleanKey.isEmpty() || (searchSecrets.save(cleanKey) && searchSecrets.read() == cleanKey) }
            catch (_: Exception) { false }
        if (!saved) {
            diagnostics.record("Busca Google", "Falha ao salvar a chave da API de busca.")
            searchApiStatus = "Não foi possível salvar a chave com segurança. Tente de novo."
            return false
        }
        settings.edit().putString(WebSearch.CX, cleanCx).apply()
        searchApiCx = cleanCx
        searchApiConfigured = true
        searchApiStatus = "Busca Google salva. Toque em Testar para conferir."
        return true
    }

    fun removeSearchApi() {
        searchSecrets.clear()
        settings.edit().remove(WebSearch.CX).apply()
        searchApiCx = ""
        searchApiConfigured = false
        searchApiStatus = "API de busca removida; a pesquisa volta a usar o Gemini."
    }

    /** Jev (TypeSafe): só é consultado com a chave salva E o interruptor ligado (começa desligado). */
    private val jevSecrets = SecureKeyStore(application, JevApi.KEY_SLOT)
    var jevConfigured by androidx.compose.runtime.mutableStateOf(jevSecrets.read() != null)
        private set
    var jevOn by androidx.compose.runtime.mutableStateOf(settings.getBoolean(JevDecisions.PREF, false))
        private set
    var jevStatus by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var jevTesting by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun saveJev(key: String): Boolean {
        val clean = key.trim()
        if (clean.isEmpty()) { jevStatus = "Cole a chave criada em console.typesafe.ai."; return false }
        val saved = try { jevSecrets.save(clean) && jevSecrets.read() == clean } catch (_: Exception) { false }
        jevConfigured = jevSecrets.read() != null
        jevStatus = if (saved) "Chave do Jev salva. Toque em Testar e depois ligue \"Usar o Jev\"." else "Não foi possível salvar a chave do Jev."
        return saved
    }

    fun removeJev() {
        jevSecrets.clear()
        jevConfigured = false
        updateJevOn(false)
        jevStatus = "Chave do Jev removida; o OSTIE volta às regras de antes."
    }

    fun updateJevOn(value: Boolean) {
        jevOn = value
        settings.edit().putBoolean(JevDecisions.PREF, value).apply()
    }

    /** O teste manda só uma frase fixa de exemplo, nunca mensagens do usuário. */
    fun testJev() {
        val key = jevSecrets.read() ?: run { jevStatus = "Salve a chave do Jev antes de testar."; return }
        jevTesting = true
        jevStatus = null
        viewModelScope.launch {
            jevStatus = try {
                val started = System.currentTimeMillis()
                val answers = withContext(Dispatchers.IO) {
                    JevApi.ask(key, JevDecisions.chatState(JevDecisions.SAMPLE, null), JevDecisions.chatQuestions(), 10_000)
                }
                val search = answers[JevDecisions.SEARCH]?.noul
                "Jev funcionou em ${System.currentTimeMillis() - started} ms. \"${JevDecisions.SAMPLE}\" precisa de pesquisa: " +
                    (if (search != null) "${(search * 100).toInt()}%." else "sem resposta.")
            } catch (failure: JevException) {
                diagnostics.record("Jev", failure.message.orEmpty())
                failure.message
            } catch (failure: Exception) {
                "Sem conexão com o Jev (${failure.javaClass.simpleName})."
            } finally { jevTesting = false }
        }
    }

    /** Decisão do Jev para o chat; null se desligado, sem chave ou sem resposta a tempo (aí valem só as regras). */
    private suspend fun jevChatAnswers(message: String, previous: String?): Map<String, JevApi.Answer>? {
        if (!jevOn) return null
        val key = jevSecrets.read() ?: return null
        return withContext(Dispatchers.IO) {
            try { JevApi.ask(key, JevDecisions.chatState(message, previous), JevDecisions.chatQuestions(), 2_500) }
            catch (failure: Exception) {
                diagnostics.record("Jev", (failure as? JevException)?.message ?: "Sem resposta (${failure.javaClass.simpleName}).")
                null
            }
        }
    }

    private val tavilySecrets = SecureKeyStore(application, TavilyApi.KEY_SLOT)
    var tavilyConfigured by androidx.compose.runtime.mutableStateOf(tavilySecrets.read() != null)
        private set

    fun saveTavily(key: String): Boolean {
        val clean = key.trim()
        if (clean.isEmpty()) { searchApiStatus = "Cole a chave da Tavily (começa com tvly-)."; return false }
        val saved = try { tavilySecrets.save(clean) && tavilySecrets.read() == clean } catch (_: Exception) { false }
        tavilyConfigured = tavilySecrets.read() != null
        searchApiStatus = if (saved) "Chave da Tavily salva. Toque em Testar para conferir." else "Não foi possível salvar a chave da Tavily."
        return saved
    }

    fun removeTavily() {
        tavilySecrets.clear()
        tavilyConfigured = false
        searchApiStatus = "Chave da Tavily removida."
    }

    fun testTavily() {
        val key = tavilySecrets.read() ?: run { searchApiStatus = "Salve a chave da Tavily antes de testar."; return }
        searchApiTesting = true
        searchApiStatus = null
        viewModelScope.launch {
            searchApiStatus = try {
                val result = withContext(Dispatchers.IO) { TavilyApi.search(key, "previsão do tempo hoje", 3) }
                val count = result.optJSONArray("resultados")?.length() ?: 0
                if (count > 0) "Tavily funcionou: $count resultados." else "A Tavily respondeu, mas sem resultados."
            } catch (failure: GoogleSearchException) {
                diagnostics.record("Busca Tavily", failure.message.orEmpty())
                failure.message
            } catch (failure: Exception) {
                "Sem conexão com a Tavily (${failure.javaClass.simpleName})."
            } finally { searchApiTesting = false }
        }
    }

    fun updateSearchApiFirst(value: Boolean) {
        searchApiFirst = value
        settings.edit().putBoolean(WebSearch.PREFER_API, value).apply()
    }

    fun testSearchApi() {
        val key = searchSecrets.read()
        if (key == null || searchApiCx.isBlank()) { searchApiStatus = "Salve a chave e o cx antes de testar."; return }
        searchApiTesting = true
        searchApiStatus = null
        viewModelScope.launch {
            searchApiStatus = try {
                val result = withContext(Dispatchers.IO) { GoogleSearchApi.search(key, searchApiCx, "previsão do tempo hoje", 3) }
                val count = result.optJSONArray("resultados")?.length() ?: 0
                if (count > 0) "Funcionou: $count resultados. Primeiro: ${result.getJSONArray("resultados").getJSONObject(0).optString("titulo").take(80)}"
                else "A API respondeu, mas sem resultados. Confira se o mecanismo pesquisa a web inteira."
            } catch (failure: GoogleSearchException) {
                diagnostics.record("Busca Google", failure.message.orEmpty())
                failure.message
            } catch (failure: Exception) {
                "Sem conexão com a busca Google (${failure.javaClass.simpleName})."
            } finally { searchApiTesting = false }
        }
    }

    fun updateFallback(value: Boolean) {
        fallback = value
        settings.edit().putBoolean("chat_fallback", value).apply()
    }

    fun selectThinking(value: ThinkingMode) {
        thinkingMode = value
        settings.edit().putString("thinking_mode", value.value).apply()
    }

    /** Só limpa o campo da tela quando a gravação e a leitura de volta funcionam. */
    fun saveKey(input: String, forProvider: ChatProvider = ChatProvider.GEMINI): Boolean {
        val apiKey = input.trim()
        val target = storeFor(forProvider)
        if (apiKey.isEmpty()) {
            keyStatus = "Digite uma chave para salvar; a chave atual não foi alterada."
            keySaveError = true
            return false
        }
        return try {
            if (!target.save(apiKey) || target.read() != apiKey) {
                diagnostics.record("Chaves", "Falha ao confirmar a gravação da chave ${forProvider.label}.")
                keyStatus = "Não foi possível confirmar a gravação. Sua chave permanece no campo para tentar novamente."
                keySaveError = true
                false
            } else {
                keyStatus = "Chave ${forProvider.label} salva e conferida neste aparelho. O campo fica vazio porque ela é mantida oculta."
                keySaveError = false
                error = null
                if (forProvider == ChatProvider.GROQ) refreshGroqModels()
                true
            }
        } catch (_: Exception) {
            diagnostics.record("Chaves", "Falha ao salvar a chave ${forProvider.label}.")
            keyStatus = "Falha ao salvar com segurança. Sua chave permanece no campo para tentar novamente."
            keySaveError = true
            false
        }
    }

    fun removeKey(forProvider: ChatProvider) {
        storeFor(forProvider).clear()
        keyStatus = "Chave ${forProvider.label} removida deste aparelho."
        keySaveError = false; error = null
    }
    fun clearConversation() {
        if (busy) return
        messages = emptyList()
        history.clear()
        error = null
    }

    /** Cópia de segurança dos ajustes e rotinas em Documentos/OSTIE (sem chaves). */
    var backupStatus by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun exportBackup() {
        if (!memory.hasFolderAccess()) {
            backupStatus = "Permita a pasta do OSTIE (Ajustes > Memória do OSTIE) para salvar a cópia em Documentos/OSTIE."
            return
        }
        viewModelScope.launch {
            backupStatus = withContext(Dispatchers.IO) {
                try {
                    val routines = RoutineStore.get(getApplication()).routines
                    val saved = memory.writeShared(SettingsBackup.FILE, SettingsBackup.build(settings.all,
                        org.json.JSONArray(routines.map { it.toJson() }), System.currentTimeMillis()))
                    if (!saved) return@withContext "Não consegui gravar a cópia em Documentos/OSTIE. Confira a permissão da pasta " +
                        "(Ajustes > Memória do OSTIE) e o espaço livre."
                    "Cópia salva em Documentos/OSTIE/${SettingsBackup.FILE}: ajustes e ${routines.size} rotina(s). " +
                        "As chaves não entram; a memória e a base de conhecimento já ficam na mesma pasta."
                } catch (failure: Exception) { "Não consegui salvar a cópia (${failure.javaClass.simpleName})." }
            }
        }
    }

    fun importBackup() {
        viewModelScope.launch {
            backupStatus = withContext(Dispatchers.IO) {
                try {
                    val text = memory.readShared(SettingsBackup.FILE)
                        ?: return@withContext "Nenhuma cópia em Documentos/OSTIE (ou a pasta do OSTIE não está permitida)."
                    val parsed = SettingsBackup.parse(text)
                    val current = settings.all
                    val editor = settings.edit()
                    var skipped = 0
                    parsed.settings.forEach { (name, value) ->
                        if (!SettingsBackup.compatible(value, current[name])) { skipped++; return@forEach }
                        when (value) {
                            is Boolean -> editor.putBoolean(name, value)
                            is String -> editor.putString(name, value)
                            is Number -> when (val number = SettingsBackup.number(value, current[name])) {
                                is Int -> editor.putInt(name, number)
                                is Long -> editor.putLong(name, number)
                                is Float -> editor.putFloat(name, number)
                            }
                        }
                    }
                    editor.apply()
                    val routines = (0 until parsed.routines.length()).mapNotNull { i ->
                        parsed.routines.optJSONObject(i)?.let { runCatching { Routine.fromJson(it) }.getOrNull() }
                    }
                    val added = withContext(Dispatchers.Main) { RoutineStore.get(getApplication()).merge(routines) }
                    "Ajustes restaurados e $added rotina(s) adicionada(s). Feche e abra o app para aplicar tudo. " +
                        "As chaves precisam ser coladas de novo." +
                        if (skipped > 0) " $skipped ajuste(s) de outra versão do app ficaram como estavam." else ""
                } catch (failure: Exception) { "Não consegui ler a cópia (${failure.message ?: failure.javaClass.simpleName})." }
            }
        }
    }

    /** Conversas anteriores (menu do chat). */
    var savedConversations by androidx.compose.runtime.mutableStateOf<List<SavedConversation>>(emptyList())
        private set

    fun loadSavedConversations() {
        viewModelScope.launch { savedConversations = withContext(Dispatchers.IO) { history.saved() } }
    }

    /** Nova conversa: a atual vai para Conversas anteriores (nada se perde). */
    fun newConversation() {
        if (busy) return
        val current = messages
        messages = emptyList()
        error = null
        viewModelScope.launch {
            savedConversations = withContext(Dispatchers.IO) { history.archive(current); history.clear(); history.saved() }
        }
    }

    /** Abre uma conversa anterior; a atual é guardada antes. */
    fun openConversation(conversation: SavedConversation) {
        if (busy) return
        val current = messages
        messages = conversation.messages
        error = null
        viewModelScope.launch {
            savedConversations = withContext(Dispatchers.IO) {
                history.archive(current)
                history.delete(conversation.id)
                history.save(conversation.messages)
                history.saved()
            }
        }
    }

    fun deleteConversation(conversation: SavedConversation) {
        viewModelScope.launch {
            savedConversations = withContext(Dispatchers.IO) { history.delete(conversation.id); history.saved() }
        }
    }
    fun dismissError() { error = null }

    fun attach(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            attachment = AttachmentClient(getApplication()).describe(uri)
            error = null
        } catch (failure: Exception) {
            // Alguns provedores permitem leitura temporária, mas não persistente.
            try { attachment = AttachmentClient(getApplication()).describe(uri) }
            catch (_: Exception) {
                error = "Não consegui abrir este arquivo."
                diagnostics.record("Arquivo", "Não foi possível acessar arquivo (${failure.javaClass.simpleName}).")
            }
        }
    }

    fun removeAttachment() { attachment = null }

    /** Resultados de rotinas executadas em segundo plano entram na conversa. */
    fun collectRoutineResults() {
        val results = RoutineStore.get(getApplication()).drainInbox()
        if (results.isEmpty()) return
        messages = messages + results.map { (title, text) -> ChatMessage("model", "Rotina · $title\n\n$text") }
        viewModelScope.launch(Dispatchers.IO) { history.save(messages) }
    }

    /** Conversa de voz do Live (transcrita) entra no histórico, para o chat saber o que foi falado. */
    fun collectLiveTranscript() {
        val turns = LiveTranscriptInbox.drain(getApplication())
        if (turns.isEmpty()) return
        messages = (messages + turns.flatMap { (user, model) ->
            listOfNotNull(user.takeIf { it.isNotBlank() }?.let { ChatMessage("user", "Por voz: $it") },
                model.takeIf { it.isNotBlank() }?.let { ChatMessage("model", it) })
        }).takeLast(400)
        viewModelScope.launch(Dispatchers.IO) { history.save(messages) }
    }

    private fun chatSystem(base: String, canSave: Boolean = false, query: String = "", canSearch: Boolean = true) =
        base + PLAIN_TEXT + FreshInfo.instructions(canSearch = canSearch) + UserProfile.get(getApplication()).identity(canSave) + memory.promptBlock() +
            KnowledgeBase.get(getApplication()).let { it.promptBlock() + it.relevant(query) }


    /** Ferramentas do app (alarmes, agenda, rotinas, memória, mensagens) também no chat escrito, com qualquer provedor. */
    var chatTools by androidx.compose.runtime.mutableStateOf(settings.getBoolean("chat_tools", true))
        private set

    fun updateChatTools(value: Boolean) {
        chatTools = value
        settings.edit().putBoolean("chat_tools", value).apply()
    }

    /** Texto recebido pelo "Compartilhar" do Android, colocado no campo de mensagem. */
    var incomingText by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    fun receiveShared(text: String) { incomingText = text.take(20_000) }

    /** Texto ditado pelo reconhecimento de voz do Android: entra no fim do campo de mensagem. */
    var dictated by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    fun receiveDictation(text: String) { dictated = text.trim().take(4_000) }
    fun consumeDictation() { dictated = null }

    private var answerJob: Job? = null
    private var currentTools: AgentTools? = null
    private val phoneActions by lazy { PhoneActions(getApplication()) }
    private var answerGeneration = 0

    /**
     * Botão Parar: o que já chegou fica na conversa e ações pedidas depois disso são recusadas.
     * (A chamada de rede termina sozinha em segundo plano; a resposta dela é ignorada.)
     */
    fun stopAnswer() {
        if (!busy) return
        currentTools?.halted = true
        answerGeneration++
        answerJob?.cancel()
        answerJob = null
        val partial = streamingText.trim()
        if (partial.isNotEmpty()) messages = messages + ChatMessage("model", "$partial\n\n(resposta interrompida)")
        streamingText = ""
        searching = false
        activeModel = null; activeTextModel = null; busy = false
        viewModelScope.launch(Dispatchers.IO) { history.save(messages) }
    }

    /** Tentar de novo: reenvia a pergunta do usuário; se for a última troca, a resposta antiga é substituída. */
    fun retry(index: Int, onAnswer: (String) -> Unit) {
        if (busy || index !in messages.indices) return
        val userIndex = (index downTo 0).firstOrNull { messages[it].role == "user" } ?: return
        val text = messages[userIndex].text.substringBefore("\n📎 ").removePrefix("Por voz: ")
        if (text.isBlank()) return
        if (userIndex >= messages.lastIndex - 1) messages = messages.take(userIndex)
        send(text, onAnswer)
    }
    fun consumeIncoming() { incomingText = null }

    fun send(input: String, onAnswer: (String) -> Unit): Boolean {
        val text = input.trim()
        val selectedFile = attachment
        if ((text.isEmpty() && selectedFile == null) || busy) return false
        val selectedProvider = if (selectedFile != null) ChatProvider.GEMINI else provider
        val key = storeFor(selectedProvider).read()
        if (key == null) {
            error = "Salve sua chave ${selectedProvider.label} em Ajustes."
            diagnostics.record("Chat", "Chave ${selectedProvider.label} não configurada.")
            return false
        }
        val prompt = text.ifBlank { "Analise este arquivo e explique os pontos mais relevantes." }
        messages = messages + ChatMessage("user", prompt + (selectedFile?.let { "\n📎 ${it.name}" } ?: ""))
        busy = true
        error = null
        activeModel = null
        activeTextModel = null
        streamingText = ""
        val snapshot = messages
        // Ferramentas desta resposta: Parar bloqueia só elas, não as da próxima pergunta.
        val sendTools = AgentTools(getApplication(), background = false, phone = phoneActions)
        currentTools = sendTools
        val generation = ++answerGeneration
        answerJob = viewModelScope.launch {
            var uploaded: String? = null
            try {
                var response: String
                if (selectedProvider == ChatProvider.GEMINI) {
                    val part = if (selectedFile != null) withContext(Dispatchers.IO) {
                        AttachmentClient(getApplication()).prepare(selectedFile, key)
                    }.also { uploaded = it.remoteName }.part else null
                    val choices = if (selectedFile != null) listOf(ChatModel.GEMINI_25)
                        else ChatModel.candidates(selectedModel, fallback)
                    var answer: String? = null
                    // Anexos seguem só para análise; o chat com ferramentas age como o Live.
                    val tools = if (selectedFile == null && chatTools) sendTools else null
                    for ((index, choice) in choices.withIndex()) {
                        activeModel = choice
                        activeTextModel = "Gemini · ${choice.label}"
                        try {
                            answer = withContext(Dispatchers.IO) {
                                val stream: (String) -> Unit = { partial ->
                                    main.post { if (busy && activeModel == choice && answerGeneration == generation) streamingText = partial }
                                }
                                TextModel.gemini(key, choice, snapshot, thinkingMode, part,
                                    chatSystem(GeminiClient.DEFAULT_SYSTEM + if (tools != null) TOOLS_GUIDE else "", tools != null, prompt,
                                        canSearch = googleSearch),
                                    45_000, googleSearch, tools, searchApi = WebSearch.preferApi(getApplication()),
                                    onDowngrade = { diagnostics.record("Chat Gemini", it) },
                                    onPartial = stream)
                            }
                            lastAnswerModel = choice
                            break
                        } catch (failure: GeminiHttpException) {
                            streamingText = ""
                            if (!failure.allowsFallback || index == choices.lastIndex) throw failure
                        }
                    }
                    response = answer ?: throw IllegalStateException("Nenhum modelo respondeu.")
                } else {
                    var modelId = if (selectedProvider == ChatProvider.GROQ) groqModelId else openRouterModel
                    var retried = false
                    val tools = if (chatTools) sendTools else null
                    // Sem Pesquisa Google embutida: a ferramenta web_search pesquisa pela API de busca ou pela chave Gemini.
                    val canSearch = googleSearch && WebSearch.available(getApplication())
                    val previous = snapshot.dropLast(1).lastOrNull { it.role == "user" }?.text?.removePrefix("Por voz: ")
                    // Jev ligado em Ajustes: reforça as regras (pesquisa que a regra não pegou, conversa sem ferramentas).
                    val route = JevDecisions.chatRoute(jevChatAnswers(prompt, previous), FreshInfo.needsSearch(prompt))
                    val functions = if (route.tools) tools?.declarations(webSearch = canSearch) else null
                    // Modelos pequenos (ex.: GPT OSS 20B) respondem de memória antigos fatos "atuais": pesquisa antes.
                    var request = snapshot
                    var browserSearch = false
                    if (route.search) {
                        // "pesquisa" sozinho pesquisa a pergunta anterior do usuário.
                        val query = FreshInfo.searchQuery(prompt, previous)
                        val reason: String? = if (!canSearch) {
                            if (!googleSearch) "a pesquisa está desligada em Ajustes > Pesquisa Google"
                            else "não há chave Gemini nem API de busca do Google salvas em Ajustes"
                        } else {
                            searching = true
                            val found = try {
                                withContext(Dispatchers.IO) {
                                    sendTools.run(WebSearch.NAME, org.json.JSONObject().put("consulta", query))
                                }
                            } finally { searching = false }
                            if (!found.has("erro")) {
                                request = snapshot.dropLast(1) + ChatMessage("user",
                                    FreshInfo.withResults(snapshot.last().text + if (query != prompt) "\n(Pesquisado: $query)" else "", found.toString()))
                                null
                            } else found.optString("motivo").ifBlank { found.optString("erro") }
                        }
                        if (reason != null) {
                            diagnostics.record("Chat ${selectedProvider.label}", "Pesquisa antes da resposta: $reason.")
                            // GPT OSS no Groq tem pesquisa própria (browser_search): tenta antes de desistir.
                            browserSearch = selectedProvider == ChatProvider.GROQ && modelId.contains("gpt-oss")
                            if (!browserSearch) {
                                request = snapshot.dropLast(1) + ChatMessage("user", FreshInfo.searchFailed(snapshot.last().text, reason))
                                error = "Não consegui pesquisar: $reason."
                            }
                        }
                    }
                    while (true) {
                        val currentLabel = "${selectedProvider.label} · $modelId"
                        activeTextModel = currentLabel
                        try {
                            response = withContext(Dispatchers.IO) {
                                ChatCompletionClient().streamAnswer(selectedProvider, key, modelId, request,
                                    chatSystem(ChatCompletionClient.DEFAULT_SYSTEM + if (tools != null) TOOLS_GUIDE else "",
                                        tools != null, prompt, canSearch || browserSearch),
                                    functions = functions, runTool = tools?.let { it::run },
                                    browserSearch = browserSearch,
                                    onDowngrade = { diagnostics.record("Chat ${selectedProvider.label}", it) }) { partial ->
                                    main.post { if (busy && activeTextModel == currentLabel && answerGeneration == generation) streamingText = partial }
                                }
                            }
                            break
                        } catch (failure: ChatProviderHttpException) {
                            if (selectedProvider != ChatProvider.GROQ || failure.status != 404 || retried) throw failure
                            diagnostics.record("Chat Groq", "HTTP 404 no modelo $modelId. Consultando modelos disponíveis.")
                            val models = withContext(Dispatchers.IO) { GroqCatalog().available(key) }
                            availableGroqModels = models
                            val alternate = models.firstOrNull { it != modelId }
                                ?: throw IllegalStateException("Groq HTTP 404: nenhum modelo alternativo disponível nesta chave.")
                            retried = true
                            modelId = alternate
                            streamingText = ""
                            groqModelStatus = "Modelo anterior indisponível; usando ${GroqModel.label(alternate)}."
                            selectGroqModel(alternate)
                        }
                    }
                    lastAnswerModel = null
                }
                // Imagens criadas nesta resposta viram linhas "[imagem] uri", que o balão mostra como figura.
                val created = synchronized(sendTools.images) { sendTools.images.toList() }
                messages = messages + ChatMessage("model", response + created.joinToString("") { "\n\n$IMAGE_MARK$it" })
                streamingText = ""
                withContext(Dispatchers.IO) { history.save(messages) }
                if (attachment == selectedFile) attachment = null
                onAnswer(response)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                streamingText = ""
                diagnostics.record(if (selectedFile != null) "Arquivo Gemini" else "Chat ${selectedProvider.label}", when {
                    exception is ChatProviderHttpException -> "HTTP ${exception.status} durante resposta. Modelo: ${if (selectedProvider == ChatProvider.GROQ) groqModelId else openRouterModel}."
                    exception is GeminiHttpException -> "HTTP ${exception.status} durante resposta Gemini."
                    exception is IllegalArgumentException && selectedFile != null ->
                        "${exception.javaClass.simpleName}: ${exception.message?.take(130) ?: "arquivo inválido"}"
                    else -> "${exception.javaClass.simpleName}: falha ao obter resposta."
                })
                error = when {
                    exception is GeminiHttpException && exception.status in listOf(401, 403) ->
                        "Chave Gemini sem acesso à API. Confira em Ajustes."
                    exception is ChatProviderHttpException && exception.status in listOf(401, 403) ->
                        "Chave ${exception.provider.label} recusada. Confira em Ajustes."
                    exception is ChatProviderHttpException && exception.status == 404 ->
                        "Groq HTTP 404: modelo ou recurso indisponível. Consulte os modelos em Ajustes."
                    selectedFile != null && exception is GeminiHttpException && exception.status == 400 ->
                        "Gemini não aceitou este formato de arquivo ou o conteúdo. Tente PDF, imagem ou texto."
                    else -> exception.message ?: "Não consegui responder agora."
                }
                // Mantém o texto do usuário para reenvio ou cópia após falha.
                withContext(Dispatchers.IO) { history.save(messages) }
            } finally {
                uploaded?.let { remote ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { AttachmentClient(getApplication()).delete(remote, key) }
                        catch (_: Exception) { diagnostics.record("Arquivo", "Não foi possível remover o arquivo remoto após a análise.") }
                    }
                }
                // Depois de Parar, uma pergunta nova pode já estar em andamento: não mexe no estado dela.
                if (answerGeneration == generation) { activeModel = null; activeTextModel = null; busy = false }
            }
        }
        return true
    }

    companion object {
        const val IMAGE_MARK = "[imagem] "
        /** O balão do chat mostra texto puro: Markdown apareceria com ** e | soltos. */
        const val PLAIN_TEXT = " O chat mostra Markdown simples: pode usar **negrito**, *itálico*, títulos com #, listas com - ou 1. " +
            "e blocos de código entre ```. Evite tabelas (no celular viram lista)."
        const val TOOLS_GUIDE = " Você tem as ferramentas do app no celular do usuário: alarmes, timers, agenda, contatos, " +
            "mensagens e ligações prontas, rotas, notificações, rotinas agendadas, apps e a sua memória. Use-as quando o " +
            "usuário pedir para agir ou quando precisar dos dados delas, e diga em uma frase o que fez. Mensagens e ligações " +
            "só abrem a tela pronta: o usuário é quem envia ou liga. Anote na memória (memory_note) fatos duradouros que o " +
            "usuário contar. Para criar imagens, use generate_image. Nunca diga que fez algo se a ferramenta devolveu erro."
    }
}
