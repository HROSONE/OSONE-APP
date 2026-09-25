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

    /** Resumo das últimas mensagens do chat para o Live continuar a conversa (vazio se não houver). */
    fun recap(history: List<ChatMessage>, maxChars: Int = 3_000, maxMessages: Int = 8): String {
        val recent = window(history, maxChars, maxMessages)
        if (recent.isEmpty()) return ""
        val lines = recent.joinToString("\n") { message ->
            (if (message.role == "user") "Usuário: " else "OSTIE: ") + message.text.removePrefix("Por voz: ")
                .replace(Regex("\\s+"), " ").trim().let { if (it.length > 400) it.take(400) + "…" else it }
        }
        return "\n\nConversa recente no chat escrito (continue dela se o usuário se referir a algo dito lá; não repita):\n$lines"
    }
}
