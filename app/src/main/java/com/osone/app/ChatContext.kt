package com.osone.app

/** Quais mensagens anteriores vão ao modelo: as mais recentes que cabem num orçamento de texto. Lógica pura. */
object ChatContext {
    /** Gemini tem contexto grande e cota folgada. */
    const val GEMINI_CHARS = 60_000
    const val GEMINI_MESSAGES = 40
    /** Groq e OpenRouter gratuitos limitam tokens por minuto (e as ferramentas também contam). */
    const val COMPAT_CHARS = 12_000
    const val COMPAT_MESSAGES = 20

    /** A última mensagem (o pedido atual) vai sempre inteira; as anteriores entram enquanto couberem. */
    fun window(history: List<ChatMessage>, maxChars: Int, maxMessages: Int): List<ChatMessage> {
        if (history.isEmpty()) return history
        var start = history.lastIndex
        var used = history.last().text.length
        while (start > 0 && history.size - start < maxMessages) {
            val size = history[start - 1].text.length
            if (used + size > maxChars) break
            used += size
            start--
        }
        // Começa por uma fala do usuário: alguns provedores recusam histórico que abre com a resposta do modelo.
        while (start < history.lastIndex && history[start].role != "user") start++
        return history.subList(start, history.size)
    }
}
