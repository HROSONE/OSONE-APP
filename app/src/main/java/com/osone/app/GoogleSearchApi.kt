package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GoogleSearchException(val status: Int, message: String) : Exception(message)

/**
 * API de busca do Google (Custom Search JSON API, chave do Cloud Console + ID do mecanismo "cx").
 * Só HttpURLConnection e org.json: testável sem Android. O Google encerra esta API em 01/01/2027.
 */
object GoogleSearchApi {
    private const val ENDPOINT = "https://customsearch.googleapis.com/customsearch/v1"

    fun url(cx: String, query: String, count: Int = 5): String =
        "$ENDPOINT?cx=${encode(cx.trim())}&q=${encode(query.trim())}&num=${count.coerceIn(1, 10)}&hl=pt-BR&gl=br"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    /** Bloqueante (rodar fora da thread principal). A chave vai no cabeçalho, fora do endereço. */
    fun search(key: String, cx: String, query: String, count: Int = 5): JSONObject {
        val connection = URL(url(cx, query, count)).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("x-goog-api-key", key)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = try { connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } } catch (_: Exception) { null }
                throw GoogleSearchException(status, explain(status, body))
            }
            parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }

    /** Resultados enxutos para o modelo resumir: título, link e trecho. */
    fun parse(body: String): JSONObject {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        val results = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val link = item.optString("link")
            if (link.isBlank()) continue
            results.put(JSONObject().put("titulo", item.optString("title").take(200))
                .put("link", link).put("trecho", item.optString("snippet").replace("\n", " ").take(400)))
        }
        return if (results.length() == 0) JSONObject().put("resultado", "Nada encontrado para esta busca.")
            else JSONObject().put("resultados", results)
                .put("instrucao", "Responda com base nestes resultados e cite as fontes (título e link) que usar.")
    }

    /** Mensagem clara para Ajustes e diagnóstico, a partir do código e do motivo que o Google devolve. */
    fun explain(status: Int, body: String?): String {
        val reason = try {
            JSONObject(body.orEmpty()).optJSONObject("error")?.let { error ->
                listOf(error.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty(),
                    error.optString("status"), error.optString("message")).joinToString(" ")
            }.orEmpty()
        } catch (_: Exception) { "" }
        return when {
            status == 429 || reason.contains("limit", true) || reason.contains("quota", true) || reason.contains("RESOURCE_EXHAUSTED") ->
                "Cota da busca Google esgotada (100 buscas grátis por dia). Volta amanhã."
            reason.contains("accessNotConfigured", true) || reason.contains("SERVICE_DISABLED", true) ->
                "A Custom Search API não está ativada no projeto desta chave. Ative no Cloud Console."
            reason.contains("API key not valid", true) || reason.contains("API_KEY_INVALID", true) ->
                "Chave da busca Google inválida. Confira a chave criada no Cloud Console."
            status == 400 && reason.contains("invalid", true) ->
                "Chave ou ID do mecanismo (cx) inválido. Confira os dois."
            status == 400 || status == 403 -> "O Google recusou a busca (HTTP $status). Confira a chave, o cx e se a API está ativa."
            else -> "A busca Google falhou (HTTP $status)."
        }
    }
}
