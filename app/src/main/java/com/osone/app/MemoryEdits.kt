package com.osone.app

import java.text.Normalizer

/** Regras de edição da memória, sem Android: esquecer só palavras inteiras e não passar do limite. Testável na JVM. */
object MemoryEdits {
    /** Mais que isso num "esquecer" pede um trecho mais específico (evita apagar a memória inteira por engano). */
    const val MAX_FORGET = 3

    private fun normalized(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    /**
     * Índices das anotações ("- ...") que contêm [needle] como palavra ou expressão inteira, sem diferenciar acento:
     * "Ana" acha "a Ana é minha irmã", mas não "semana" nem "Mariana".
     */
    fun forgetMatches(lines: List<String>, needle: String): List<Int> {
        val clean = normalized(needle.trim())
        if (clean.length < 3) return emptyList()
        val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(clean) + "(?![\\p{L}\\p{N}])")
        return lines.indices.filter { lines[it].startsWith("- ") && pattern.containsMatchIn(normalized(lines[it])) }
    }

    /** Aviso quando a memória nova passaria do limite; null se couber. */
    fun fullMessage(length: Int, limit: Int): String? = if (length <= limit) null else
        "Memória cheia (${"%,d".format(length)} de ${"%,d".format(limit)} caracteres). Reorganize uma seção com memory_rewrite, " +
            "resumindo anotações antigas, e tente de novo."
}
