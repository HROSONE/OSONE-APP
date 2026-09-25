package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Busca na web pela Tavily (grátis até 1.000 buscas por mês, sem cartão). Testável na JVM. */
object TavilyApi {
    const val KEY_SLOT = "key_tavily"

    /** Bloqueante (rodar fora da thread principal). A chave vai no cabeçalho. */
    fun search(key: String, query: String, count: Int = 5): JSONObject {
        val connection = URL("https://api.tavily.com/search").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use {
                it.write(JSONObject().put("query", query.trim().take(400)).put("max_results", count.coerceIn(1, 10))
                    .put("search_depth", "basic").toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) throw GoogleSearchException(status, explain(status))
            parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun parse(body: String): JSONObject {
        val items = JSONObject(body).optJSONArray("results") ?: JSONArray()
        val results = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val link = item.optString("url")
            if (link.isBlank()) continue
            results.put(JSONObject().put("titulo", item.optString("title").take(200)).put("link", link)
                .put("trecho", item.optString("content").replace("\n", " ").take(500))
                .apply { item.optString("published_date").takeIf { it.isNotBlank() }?.let { put("data", it.take(10)) } })
        }
        return if (results.length() == 0) JSONObject().put("resultado", "Nada encontrado para esta busca.")
            else JSONObject().put("resultados", results)
                .put("instrucao", "Responda com base nestes resultados e cite as fontes (título e link) que usar.")
    }

    fun explain(status: Int): String = when (status) {
        401, 403 -> "Chave da Tavily recusada. Confira a chave em Ajustes."
        429, 432, 433 -> "Cota da Tavily esgotada (1.000 buscas grátis por mês)."
        else -> "A busca Tavily falhou (HTTP $status)."
    }
}
