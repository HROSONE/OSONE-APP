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
    var model by androidx.compose.runtime.mutableStateOf(settings.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash")
        private set
    val configured get() = secrets.read() != null

    fun saveSettings(apiKey: String, newModel: String) {
        if (!Regex("[a-zA-Z0-9._-]{3,80}").matches(newModel.trim())) {
            error = "Nome de modelo inválido."
            return
        }
        if (apiKey.isNotBlank()) secrets.save(apiKey.trim())
        model = newModel.trim()
        settings.edit().putString("model", model).apply()
        error = null
    }

    fun removeKey() { secrets.clear(); error = null }
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
        val snapshot = messages
        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { GeminiClient().answer(key, model, snapshot) }
                messages = messages + ChatMessage("model", response)
                withContext(Dispatchers.IO) { history.save(messages) }
                onAnswer(response)
            } catch (exception: Exception) {
                error = exception.message ?: "Não consegui responder agora."
                // Mantém o texto do usuário para reenvio ou cópia após falha.
                withContext(Dispatchers.IO) { history.save(messages) }
            } finally { busy = false }
        }
        return true
    }
}
