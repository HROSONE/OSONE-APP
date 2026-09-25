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
import kotlinx.coroutines.Dispatchers
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
        }).takeLast(100)
        viewModelScope.launch(Dispatchers.IO) { history.save(messages) }
    }

    private fun chatSystem(base: String, canSave: Boolean = false, query: String = "", canSearch: Boolean = true) =
        base + PLAIN_TEXT + FreshInfo.instructions(canSearch = canSearch) + UserProfile.get(getApplication()).identity(canSave) + memory.promptBlock() +
            KnowledgeBase.get(getApplication()).let { it.promptBlock() + it.relevant(query) }

    private val agentTools by lazy { AgentTools(getApplication(), background = false) }

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
        viewModelScope.launch {
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
                    val tools = if (selectedFile == null && chatTools) agentTools else null
                    for ((index, choice) in choices.withIndex()) {
                        activeModel = choice
                        activeTextModel = "Gemini · ${choice.label}"
                        try {
                            answer = withContext(Dispatchers.IO) {
                                val stream: (String) -> Unit = { partial ->
                                    main.post { if (busy && activeModel == choice) streamingText = partial }
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
                    val tools = if (chatTools) agentTools else null
                    // Sem Pesquisa Google embutida: a ferramenta web_search pesquisa pela API de busca ou pela chave Gemini.
                    val canSearch = googleSearch && WebSearch.available(getApplication())
                    val functions = tools?.declarations(webSearch = canSearch)
                    // Modelos pequenos (ex.: GPT OSS 20B) respondem de memória antigos fatos "atuais": pesquisa antes.
                    var request = snapshot
                    if (canSearch && FreshInfo.needsSearch(prompt)) {
                        searching = true
                        val found = try {
                            withContext(Dispatchers.IO) {
                                agentTools.run(WebSearch.NAME, org.json.JSONObject().put("consulta", prompt.take(400)))
                            }
                        } finally { searching = false }
                        if (!found.has("erro")) request = snapshot.dropLast(1) +
                            ChatMessage("user", FreshInfo.withResults(snapshot.last().text, found.toString()))
                        else diagnostics.record("Chat ${selectedProvider.label}", "Pesquisa antes da resposta falhou; respondendo sem ela.")
                    }
                    while (true) {
                        val currentLabel = "${selectedProvider.label} · $modelId"
                        activeTextModel = currentLabel
                        try {
                            response = withContext(Dispatchers.IO) {
                                ChatCompletionClient().streamAnswer(selectedProvider, key, modelId, request,
                                    chatSystem(ChatCompletionClient.DEFAULT_SYSTEM + if (tools != null) TOOLS_GUIDE else "",
                                        tools != null, prompt, canSearch),
                                    functions = functions, runTool = tools?.let { it::run },
                                    onDowngrade = { diagnostics.record("Chat ${selectedProvider.label}", it) }) { partial ->
                                    main.post { if (busy && activeTextModel == currentLabel) streamingText = partial }
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
                messages = messages + ChatMessage("model", response)
                streamingText = ""
                withContext(Dispatchers.IO) { history.save(messages) }
                if (attachment == selectedFile) attachment = null
                onAnswer(response)
            } catch (exception: Exception) {
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
                activeModel = null; activeTextModel = null; busy = false
            }
        }
        return true
    }

    private companion object {
        /** O balão do chat mostra texto puro: Markdown apareceria com ** e | soltos. */
        const val PLAIN_TEXT = " O chat mostra texto simples: não use Markdown (nada de **, #, tabelas ou linhas com |), " +
            "exceto blocos de código entre ``` quando mostrar código; para listas, use linhas começando com \"• \" e deixe uma linha em branco entre os itens."
        const val TOOLS_GUIDE = " Você tem as ferramentas do app no celular do usuário: alarmes, timers, agenda, contatos, " +
            "mensagens e ligações prontas, rotas, notificações, rotinas agendadas, apps e a sua memória. Use-as quando o " +
            "usuário pedir para agir ou quando precisar dos dados delas, e diga em uma frase o que fez. Mensagens e ligações " +
            "só abrem a tela pronta: o usuário é quem envia ou liga. Anote na memória (memory_note) fatos duradouros que o " +
            "usuário contar. Nunca diga que fez algo se a ferramenta devolveu erro."
    }
}
