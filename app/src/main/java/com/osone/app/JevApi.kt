package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class JevException(val status: Int, message: String) : Exception(message)

/**
 * Jev (TypeSafe AI): modelo que não conversa, só decide. Recebe um estado (texto) e perguntas tipadas e devolve
 * escolhas, notas ou "sim/não" com probabilidade, em 70 a 500 ms. Usado para decisões rápidas do app
 * ([JevDecisions]); nada depende dele: sem chave ou com falha, o OSTIE usa as regras de antes. Testável na JVM.
 */
object JevApi {
    const val KEY_SLOT = "key_typesafe"
    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    const val MODEL = "jev-latest"

    /** Pergunta tipada: sim/não (noul), uma opção entre várias (choice) ou uma escala de 2 a 10 níveis (score). */
    sealed class Question(val instructions: String) {
        class Noul(instructions: String) : Question(instructions)
        class Choice(instructions: String, val options: Map<String, String>) : Question(instructions)
        class Score(instructions: String, val levels: List<String>) : Question(instructions)
    }

    /** Resposta: [noul] é a probabilidade do "sim"; [score] vai de 1 ao número de níveis. */
    data class Answer(val choice: String? = null, val noul: Double? = null, val score: Double? = null,
        val confidence: Double? = null, val probabilities: Map<String, Double> = emptyMap())

    fun body(state: String, questions: Map<String, Question>): JSONObject {
        require(questions.isNotEmpty()) { "Nenhuma pergunta." }
        val list = JSONObject()
        questions.forEach { (name, question) ->
            val item = JSONObject().put("instructions", question.instructions.take(1_000))
            when (question) {
                is Question.Noul -> item.put("type", "noul")
                is Question.Choice -> {
                    require(question.options.size in 2..255) { "Escolha precisa de 2 a 255 opções." }
                    item.put("type", "choice").put("criteria", JSONObject(question.options))
                }
                is Question.Score -> {
                    require(question.levels.size in 2..10) { "Escala precisa de 2 a 10 níveis." }
                    item.put("type", "score").put("criteria", JSONArray(question.levels))
                }
            }
            list.put(name, item)
        }
        return JSONObject().put("model", MODEL).put("state", state.take(8_000)).put("questions", list)
    }

    fun parse(body: String): Map<String, Answer> {
        val answers = JSONObject(body).optJSONObject("answers") ?: throw JevException(0, "O Jev respondeu sem decisões.")
        return answers.keys().asSequence().mapNotNull { name ->
            val item = answers.optJSONObject(name) ?: return@mapNotNull null
            val probabilities = item.optJSONObject("probabilities")?.let { p ->
                p.keys().asSequence().associateWith { p.optDouble(it) }.filterValues { !it.isNaN() }
            }.orEmpty()
            name to Answer(choice = item.optString("choice").takeIf { it.isNotBlank() },
                noul = number(item, "noul")?.coerceIn(0.0, 1.0), score = number(item, "score"),
                confidence = number(item, "confidence"), probabilities = probabilities)
        }.toMap()
    }

    private fun number(item: JSONObject, name: String): Double? = item.optDouble(name).takeIf { !it.isNaN() }

    /** Bloqueante (fora da thread principal). Tempo curto: é uma decisão, não pode atrasar a resposta. */
    fun ask(key: String, state: String, questions: Map<String, Question>, timeoutMs: Int = 4_000): Map<String, Answer> {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(body(state, questions).toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw JevException(status, explain(status))
            parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun explain(status: Int): String = when (status) {
        401, 403 -> "Chave do Jev (TypeSafe) recusada. Confira a chave em Ajustes."
        402 -> "Sem créditos no console da TypeSafe."
        429 -> "Muitas decisões seguidas no Jev; tente de novo em instantes."
        in 500..599 -> "O Jev está fora do ar agora (HTTP $status)."
        else -> "O Jev recusou o pedido (HTTP $status)."
    }
}
