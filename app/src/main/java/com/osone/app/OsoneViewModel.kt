package com.osone.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OsoneViewModel(application: Application) : AndroidViewModel(application) {
    private val secrets = SecureKeyStore(application)
    private val history = ConversationStore(application)
    private val settings = application.getSharedPreferences("osone_config", 0)
    var messages by androidx.compose.runtime.mutableStateOf(history.read())
        private set
    var busy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var error by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var selectedModel by androidx.compose.runtime.mutableStateOf(ChatModel.fromId(settings.getString("model", null)))
        private set
    var fallback by androidx.compose.runtime.mutableStateOf(settings.getBoolean("chat_fallback", true))
        private set
    var activeModel by androidx.compose.runtime.mutableStateOf<ChatModel?>(null)
        private set
    var lastAnswerModel by androidx.compose.runtime.mutableStateOf<ChatModel?>(null)
        private set
    var keyStatus by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var keySaveError by androidx.compose.runtime.mutableStateOf(false)
        private set
    val configured get() = secrets.read() != null

    fun selectModel(value: ChatModel) {
        selectedModel = value
        settings.edit().putString("model", value.id).apply()
    }

    fun updateFallback(value: Boolean) {
        fallback = value
        settings.edit().putBoolean("chat_fallback", value).apply()
    }

    /** Só limpa o campo da tela quando a gravação e a leitura de volta funcionam. */
    fun saveKey(input: String): Boolean {
        val apiKey = input.trim()
        if (apiKey.isEmpty()) {
            keyStatus = "Digite uma chave para salvar; a chave atual não foi alterada."
            keySaveError = true
            return false
        }
        return try {
            if (!secrets.save(apiKey) || secrets.read() != apiKey) {
                keyStatus = "Não foi possível confirmar a gravação. Sua chave permanece no campo para tentar novamente."
                keySaveError = true
                false
            } else {
                keyStatus = "Chave salva e conferida neste aparelho. O campo fica vazio porque ela é mantida oculta."
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

    fun removeKey() { secrets.clear(); keyStatus = "Chave removida deste aparelho."; keySaveError = false; error = null }
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
        val key = secrets.read()
        if (key == null) { error = "Salve sua chave Gemini em Configurações."; return false }
        messages = messages + ChatMessage("user", text)
        busy = true
        error = null
        activeModel = null
        val snapshot = messages
        viewModelScope.launch {
            try {
                val choices = ChatModel.candidates(selectedModel, fallback)
                var answer: String? = null
                var used: ChatModel? = null
                for ((index, choice) in choices.withIndex()) {
                    activeModel = choice
                    try {
                        answer = withContext(Dispatchers.IO) { GeminiClient().answer(key, choice.id, snapshot) }
                        used = choice
                        break
                    } catch (failure: GeminiHttpException) {
                        if (!failure.allowsFallback || index == choices.lastIndex) throw failure
                    }
                }
                val response = answer ?: throw IllegalStateException("Nenhum modelo respondeu.")
                lastAnswerModel = used
                messages = messages + ChatMessage("model", response)
                withContext(Dispatchers.IO) { history.save(messages) }
                onAnswer(response)
            } catch (exception: Exception) {
                error = if (exception is GeminiHttpException && exception.status in listOf(401, 403))
                    "Chave sem acesso à API Gemini. Confira a chave e as permissões no Google AI Studio."
                else exception.message ?: "Não consegui responder agora."
                // Mantém o texto do usuário para reenvio ou cópia após falha.
                withContext(Dispatchers.IO) { history.save(messages) }
            } finally { activeModel = null; busy = false }
        }
        return true
    }
}
