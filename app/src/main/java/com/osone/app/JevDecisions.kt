package com.osone.app

/**
 * Onde o Jev decide no OSTIE, sempre como reforço das regras que já existem (sem chave ou com falha, nada muda):
 * 1. Chat (Groq e OpenRouter): se o pedido precisa de pesquisa na web e se precisa de ações no celular.
 *    Sem ações, o pedido vai sem a lista de ferramentas: resposta mais rápida e sem ferramenta inventada.
 * 2. Rotinas por notificação: um filtro em frase ("mensagem de trabalho urgente") é conferido pelo sentido.
 * Lógica pura, testável na JVM.
 */
object JevDecisions {
    const val SEARCH = "pesquisa"
    const val ACTION = "acao"
    const val MATCH = "combina"

    /** Acima disso o Jev "pede" a pesquisa; ações só saem da lista quando ele tem quase certeza de que não servem. */
    const val SEARCH_AT = 0.6
    const val ACTION_BELOW = 0.15
    const val MATCH_AT = 0.7

    fun chatQuestions(): Map<String, JevApi.Question> = mapOf(
        SEARCH to JevApi.Question.Noul("A mensagem do usuário precisa de informação atual da internet para ser respondida " +
            "(notícias, preços, cotações, clima, placares, lançamentos, fatos recentes ou um pedido para pesquisar)?"),
        ACTION to JevApi.Question.Noul("A mensagem pede para agir no celular ou usar dados dele (alarme, lembrete, rotina, agenda, " +
            "mensagem, ligação, notificação, abrir app, ajuste, memória do assistente, criar imagem ou ler um link)?"))

    /** Mensagem atual e, se houver, a anterior do usuário (para "pesquisa isso" ou "faz aquilo"). */
    fun chatState(message: String, previous: String?): String =
        (if (!previous.isNullOrBlank()) "Mensagem anterior do usuário: ${previous.take(600)}\n" else "") +
            "Mensagem do usuário: ${message.take(2_000)}"

    data class ChatRoute(val search: Boolean, val tools: Boolean)

    /** Regras fixas somadas ao Jev: a regra "pesquisa" nunca é desligada por ele. */
    fun chatRoute(answers: Map<String, JevApi.Answer>?, ruleSaysSearch: Boolean): ChatRoute {
        val search = answers?.get(SEARCH)?.noul
        val action = answers?.get(ACTION)?.noul
        val wantsSearch = ruleSaysSearch || (search != null && search >= SEARCH_AT)
        // Pesquisa também é ferramenta (web_search): se vai pesquisar, a lista fica.
        val tools = wantsSearch || action == null || action >= ACTION_BELOW
        return ChatRoute(wantsSearch, tools)
    }

    /**
     * Filtro em frase (3 palavras ou mais, sem vírgula), como "mensagem de trabalho urgente": vale pelo sentido.
     * Listas curtas ("WhatsApp, Ana") continuam por palavra, sem gastar rede.
     */
    fun isMeaningFilter(filter: String): Boolean =
        !filter.contains(',') && filter.trim().split(Regex("\\s+")).count { it.length > 1 } >= 3

    fun notificationQuestion(filter: String): Map<String, JevApi.Question> = mapOf(
        MATCH to JevApi.Question.Noul("Esta notificação corresponde ao que o usuário pediu para observar: \"${filter.trim().take(300)}\"?"))

    fun matches(answers: Map<String, JevApi.Answer>): Boolean = (answers[MATCH]?.noul ?: 0.0) >= MATCH_AT
}
