package com.osone.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Informação atual: os modelos não sabem a data de hoje e o treino deles é antigo. Estas regras
 * entram nas instruções do chat e do Live, e [needsSearch] decide quando pesquisar antes de responder
 * (Groq e OpenRouter nem sempre chamam web_search sozinhos). Lógica pura, testável na JVM.
 */
object FreshInfo {
    private val format = SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    /** Data e hora do aparelho e a regra de não inventar fatos recentes. */
    fun instructions(now: Date = Date(), canSearch: Boolean): String =
        " Agora é ${synchronized(format) { format.format(now) }} (horário do aparelho). Seu conhecimento de treino é antigo " +
            "e não inclui os últimos meses: nunca apresente como atual algo que você só lembra do treino." +
            if (canSearch) " Para notícias, lançamentos, preços, cotações, placares, clima, eventos e qualquer fato recente, " +
                "pesquise na web antes de responder e cite as fontes."
            else " Para notícias e fatos recentes, diga que não tem informação atualizada em vez de inventar."

    // Só termos que quase sempre pedem dado atual: cada busca gasta cota (a API do Google dá 100 por dia).
    private val fresh = Regex(
        "\\b(not[ií]cias?|novidades|manchetes|lan[cç](ou|aram|amentos?)|pre[cç]o d[oa]s?|cota[cç][aã]o|d[oó]lar|" +
            "bitcoin|placar|quem ganhou|previs[aã]o do tempo|vai chover|temperatura em|elei[cç](ão|ões|oes)|" +
            "esta semana|essa semana|neste m[eê]s|nesse m[eê]s|20[2-9][0-9])\\b",
        RegexOption.IGNORE_CASE)

    /** Pedido explícito: "pesquisa", "busca na internet", "procura no Google"… */
    private val command = Regex("\\b(pesquis[aeo]r?|pesquisa[r]?|busque|busca(r)? n[ao] (internet|web|google)|" +
        "procur[ae](r)? n[ao] (internet|web|google)|no google|na internet|na web)\\b", RegexOption.IGNORE_CASE)

    /** O pedido depende de informação atual (notícias, preços, clima, datas recentes) ou pede pesquisa. */
    fun needsSearch(text: String): Boolean = fresh.containsMatchIn(text) || command.containsMatchIn(text)

    /**
     * O que pesquisar: o próprio pedido ou, quando ele é só a ordem ("pesquisa", "busca na internet"),
     * a pergunta anterior do usuário.
     */
    fun searchQuery(request: String, previous: String?): String {
        val rest = command.replace(request, " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val words = rest.split(" ").filter { it.length > 2 && it.lowercase() !in FILLER }
        return (if (words.isEmpty() && !previous.isNullOrBlank()) previous else request).trim().take(400)
    }

    private val FILLER = setOf("isso", "sobre", "pra", "para", "mim", "por", "favor", "agora", "ai", "aí", "entao", "então", "vai")

    /** A pesquisa falhou: o modelo explica o motivo em vez de responder de memória. */
    fun searchFailed(request: String, reason: String): String =
        "$request\n\n[O app tentou pesquisar na web agora e falhou: $reason. Diga isso ao usuário em uma frase e não invente " +
            "informação atual.]"

    /** Resultados da pesquisa anexados ao pedido do usuário, só na chamada ao modelo (não vão para o histórico). */
    fun withResults(request: String, results: String): String =
        "$request\n\n[Resultados de uma pesquisa na web feita agora pelo app. Responda com base neles, diga a data " +
            "das notícias quando houver e cite as fontes (título e link). Se não bastarem, diga isso em vez de inventar.]\n" +
            results.take(6_000)
}
