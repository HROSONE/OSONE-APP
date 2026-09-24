package com.osone.app

import android.content.Context

/** O "cérebro" de texto escolhido em Ajustes > Chat escrito, usado fora do chat (código, rotinas). */
object TextModel {
    private fun preferences(context: Context) = context.getSharedPreferences("osone_config", 0)
    private fun provider(context: Context) = ChatProvider.fromValue(preferences(context).getString("chat_provider", null))
    private fun groqModel(context: Context) = preferences(context).getString("groq_model", null) ?: GroqModel.GPT_OSS_20B.id
    private fun openRouterModel(context: Context) =
        preferences(context).getString("openrouter_model", "openrouter/free") ?: "openrouter/free"

    fun label(context: Context): String = when (provider(context)) {
        ChatProvider.GEMINI -> "Gemini · ${ChatModel.fromId(preferences(context).getString("model", null)).label}"
        ChatProvider.GROQ -> "Groq · ${GroqModel.label(groqModel(context))}"
        ChatProvider.OPENROUTER -> "OpenRouter · ${openRouterModel(context)}"
    }

    /** Chamada bloqueante (rodar fora da thread principal), com o fallback Gemini se ligado. */
    fun ask(context: Context, prompt: String, system: String, googleSearch: Boolean = false,
        readTimeoutMs: Int = 180_000, onPartial: (String) -> Unit = {}): String {
        val provider = provider(context)
        val preferences = preferences(context)
        val key = SecureKeyStore(context, when (provider) {
            ChatProvider.GEMINI -> "key"
            ChatProvider.OPENROUTER -> "key_openrouter"
            ChatProvider.GROQ -> "key_groq"
        }).read() ?: throw IllegalStateException("Salve a chave ${provider.label} em Ajustes para usar o modelo de texto.")
        val history = listOf(ChatMessage("user", prompt))
        if (provider != ChatProvider.GEMINI) {
            val model = if (provider == ChatProvider.GROQ) groqModel(context) else openRouterModel(context)
            return ChatCompletionClient().streamAnswer(provider, key, model, history, system, readTimeoutMs, onPartial)
        }
        val selected = ChatModel.fromId(preferences.getString("model", null))
        val mode = ThinkingMode.fromValue(preferences.getString("thinking_mode", null))
        val choices = ChatModel.candidates(selected, preferences.getBoolean("chat_fallback", true))
        var search = googleSearch
        for ((index, choice) in choices.withIndex()) {
            try {
                return try {
                    GeminiClient().streamAnswer(key, choice, history, mode, null, system, readTimeoutMs,
                        googleSearch = search, onPartial = onPartial)
                } catch (failure: GeminiHttpException) {
                    if (!search || failure.status != 400) throw failure
                    search = false // Modelo recusou a pesquisa: responde sem ela.
                    GeminiClient().streamAnswer(key, choice, history, mode, null, system, readTimeoutMs, onPartial = onPartial)
                }
            } catch (failure: GeminiHttpException) {
                if (!failure.allowsFallback || index == choices.lastIndex) throw failure
            }
        }
        throw IllegalStateException("Nenhum modelo Gemini respondeu.")
    }
}
