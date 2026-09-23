package com.osone.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OsoneViewModel(application: Application) : AndroidViewModel(application) {
    private val secrets = SecureKeyStore(application)
    private val routerSecrets = SecureKeyStore(application, "key_openrouter")
    private val groqSecrets = SecureKeyStore(application, "key_groq")
    private val history = ConversationStore(application)
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
    var groqModel by androidx.compose.runtime.mutableStateOf(GroqModel.fromId(settings.getString("groq_model", null)))
        private set
    var openRouterModel by androidx.compose.runtime.mutableStateOf(settings.getString("openrouter_model", "openrouter/free") ?: "openrouter/free")
        private set
    var fallback by androidx.compose.runtime.mutableStateOf(settings.getBoolean("chat_fallback", true))
        private set
    var thinkingMode by androidx.compose.runtime.mutableStateOf(ThinkingMode.fromValue(settings.getString("thinking_mode", null)))
        private set
    var streamingText by androidx.compose.runtime.mutableStateOf("")
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
    val selectedChatLabel get() = when (provider) {
        ChatProvider.GEMINI -> "Gemini · ${selectedModel.label}"
        ChatProvider.GROQ -> "Groq · ${groqModel.label}"
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

    fun selectGroqModel(value: GroqModel) {
        groqModel = value
        settings.edit().putString("groq_model", value.id).apply()
    }

    fun setOpenRouterModel(value: String) {
        openRouterModel = value.trim().take(120).ifEmpty { "openrouter/free" }
        settings.edit().putString("openrouter_model", openRouterModel).apply()
    }

    fun selectModel(value: ChatModel) {
        selectedModel = value
        settings.edit().putString("model", value.id).apply()
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
                keyStatus = "Não foi possível confirmar a gravação. Sua chave permanece no campo para tentar novamente."
                keySaveError = true
                false
            } else {
                keyStatus = "Chave ${forProvider.label} salva e conferida neste aparelho. O campo fica vazio porque ela é mantida oculta."
                keySaveError = false
                error = null
                true
            }
        } catch (_: Exception) {
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

    fun send(input: String, onAnswer: (String) -> Unit): Boolean {
        val text = input.trim()
        if (text.isEmpty() || busy) return false
        val selectedProvider = provider
        val key = storeFor(selectedProvider).read()
        if (key == null) { error = "Salve sua chave ${selectedProvider.label} em Ajustes."; return false }
        messages = messages + ChatMessage("user", text)
        busy = true
        error = null
        activeModel = null
        activeTextModel = null
        streamingText = ""
        val snapshot = messages
        viewModelScope.launch {
            try {
                val response: String
                if (selectedProvider == ChatProvider.GEMINI) {
                    val choices = ChatModel.candidates(selectedModel, fallback)
                    var answer: String? = null
                    for ((index, choice) in choices.withIndex()) {
                        activeModel = choice
                        activeTextModel = "Gemini · ${choice.label}"
                        try {
                            answer = withContext(Dispatchers.IO) {
                                GeminiClient().streamAnswer(key, choice, snapshot, thinkingMode) { partial ->
                                    main.post { if (busy && activeModel == choice) streamingText = partial }
                                }
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
                    val modelId = if (selectedProvider == ChatProvider.GROQ) groqModel.id else openRouterModel
                    val currentLabel = "${selectedProvider.label} · $modelId"
                    activeTextModel = currentLabel
                    response = withContext(Dispatchers.IO) {
                        ChatCompletionClient().streamAnswer(selectedProvider, key, modelId, snapshot) { partial ->
                            main.post { if (busy && activeTextModel == currentLabel) streamingText = partial }
                        }
                    }
                    lastAnswerModel = null
                }
                messages = messages + ChatMessage("model", response)
                streamingText = ""
                withContext(Dispatchers.IO) { history.save(messages) }
                onAnswer(response)
            } catch (exception: Exception) {
                streamingText = ""
                error = when {
                    exception is GeminiHttpException && exception.status in listOf(401, 403) ->
                        "Chave Gemini sem acesso à API. Confira em Ajustes."
                    exception is ChatProviderHttpException && exception.status in listOf(401, 403) ->
                        "Chave ${exception.provider.label} recusada. Confira em Ajustes."
                    else -> exception.message ?: "Não consegui responder agora."
                }
                // Mantém o texto do usuário para reenvio ou cópia após falha.
                withContext(Dispatchers.IO) { history.save(messages) }
            } finally { activeModel = null; activeTextModel = null; busy = false }
        }
        return true
    }
}
