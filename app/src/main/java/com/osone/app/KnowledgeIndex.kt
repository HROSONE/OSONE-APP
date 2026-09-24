package com.osone.app

import java.text.Normalizer

/** Uma fonte da base de conhecimento já convertida em texto. */
data class KnowledgeSource(val id: String, val title: String, val kind: String, val text: String, val addedAt: Long)

/**
 * Busca e instruções da base de conhecimento, sem Android (testável na JVM). Bases pequenas vão inteiras
 * nas instruções do modelo; bases grandes vão resumidas e o modelo consulta os trechos com knowledge_search.
 */
object KnowledgeIndex {
    const val INLINE_LIMIT = 24_000
    private val stopWords = setOf("a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "é", "em", "um", "uma",
        "para", "por", "com", "que", "se", "no", "na", "nos", "nas", "ao", "à", "como", "qual", "quais", "quanto",
        "mais", "sobre", "me", "eu", "voce", "vocês", "tem", "ter", "the", "of", "and", "to", "is")

    data class Hit(val title: String, val text: String, val score: Double)

    /** Trechos de até [size] caracteres, com [overlap] de sobreposição, cortados em parágrafo ou frase. */
    fun chunks(text: String, size: Int = 1_200, overlap: Int = 150): List<String> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.length <= size) return listOfNotNull(clean.takeIf { it.isNotBlank() })
        val result = ArrayList<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + size, clean.length)
            if (end < clean.length) {
                val window = clean.substring(start, end)
                val cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf('\n'))
                if (cut > size / 2) end = start + cut + 1
            }
            result += clean.substring(start, end).trim()
            if (end >= clean.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return result.filter { it.isNotBlank() }
    }

    fun terms(text: String): List<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in stopWords }

    /** Trechos mais parecidos com a pergunta (termos em comum, com peso para termos raros e títulos). */
    fun search(sources: List<KnowledgeSource>, query: String, limit: Int = 5): List<Hit> {
        val wanted = terms(query).toSet()
        if (wanted.isEmpty()) return emptyList()
        val pieces = sources.flatMap { source -> chunks(source.text).map { source.title to it } }
        if (pieces.isEmpty()) return emptyList()
        val pieceTerms = pieces.map { (title, text) -> terms("$title $text") }
        // Termos que aparecem em poucos trechos valem mais.
        val frequency = HashMap<String, Int>()
        pieceTerms.forEach { list -> list.toSet().forEach { if (it in wanted) frequency[it] = (frequency[it] ?: 0) + 1 } }
        return pieces.mapIndexed { index, (title, text) ->
            val counts = pieceTerms[index].groupingBy { it }.eachCount()
            val titleTerms = terms(title).toSet()
            var score = 0.0
            for (term in wanted) {
                val count = counts[term] ?: continue
                val rarity = Math.log(1.0 + pieces.size.toDouble() / (frequency[term] ?: 1))
                score += (1.0 + Math.log(count.toDouble())) * rarity + if (term in titleTerms) 0.5 else 0.0
            }
            Hit(title, text, score)
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(limit)
    }

    /** Bloco para as instruções do modelo; vazio quando a base está desligada ou sem fontes. */
    fun promptBlock(sources: List<KnowledgeSource>, instructions: String, strict: Boolean): String {
        if (sources.isEmpty() && instructions.isBlank()) return ""
        val total = sources.sumOf { it.text.length }
        return buildString {
            append("\n\nBASE DE CONHECIMENTO: quem configurou este aparelho carregou informações para você atender pessoas com base nelas.")
            if (instructions.isNotBlank()) append(" Instruções de atendimento: ").append(instructions.trim().take(2_000))
            append(" Para perguntas sobre esses assuntos, use a base como fonte principal e cite o título da fonte quando ajudar.")
            append(if (strict) " Se a resposta não estiver na base, diga com educação que não tem essa informação e ofereça outra ajuda; não invente."
                else " Se a base não cobrir a pergunta, pode usar conhecimento geral, deixando claro que não veio da base.")
            if (sources.isEmpty()) return@buildString
            if (total <= INLINE_LIMIT) {
                sources.forEach { append("\n\n### ").append(it.title).append("\n").append(it.text.trim()) }
            } else {
                append(" A base é grande: antes de responder sobre ela, chame knowledge_search com a pergunta e use os trechos devolvidos.")
                append(" Fontes disponíveis: ").append(sources.joinToString("; ") { it.title }).append('.')
            }
        }
    }
}
