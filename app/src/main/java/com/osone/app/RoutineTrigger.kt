package com.osone.app

import java.text.Normalizer

/**
 * Rotina que reage a notificações: o filtro é uma lista separada por vírgula (app, pessoa ou palavra),
 * como "WhatsApp, Ana" ou "banco". Todas as partes precisam aparecer no app, no título ou no texto.
 * Lógica pura, testável na JVM.
 */
object RoutineTrigger {
    /** Uma rotina não dispara de novo antes disso (conversas em grupo mandam várias notificações seguidas). */
    const val COOLDOWN_MS = 2 * 60_000L

    private fun normalized(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    fun matches(filter: String, app: String, title: String, text: String): Boolean {
        val terms = filter.split(",").map(::normalized).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return false
        val haystack = normalized("$app\n$title\n$text")
        return terms.all { haystack.contains(it) }
    }

    /** Resumo da notificação que vai para a rotina (sem passar de 600 caracteres). */
    fun describe(app: String, title: String, text: String): String =
        "Notificação de $app" + (if (title.isNotBlank()) " — $title" else "") + (if (text.isNotBlank()) ": ${text.take(500)}" else "")
}
