package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTPS direto: sem proxy de PC e sem segredos dentro do APK. */
class GeminiClient {
    fun answer(key: String, model: String, history: List<ChatMessage>): String {
        require(Regex("[a-zA-Z0-9._-]{3,80}").matches(model)) { "Nome de modelo inválido." }
        val contents = JSONArray()
        history.takeLast(20).forEach { message ->
            contents.put(JSONObject().put("role", message.role).put("parts", JSONArray().put(JSONObject().put("text", message.text))))
        }
        val request = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Você é OSONE APP, assistente de Henrique no Android. Responda em português claro. Nunca afirme ter aberto apps, alterado arquivos ou usado ferramentas se isso não aconteceu."))))
            .put("contents", contents)
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Nunca devolvemos o corpo do erro: pode conter detalhes sensíveis do provedor.
                throw IllegalStateException("O serviço respondeu HTTP $status. Confira a chave, o modelo e a cota.")
            }
            val candidate = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("candidates")?.optJSONObject(0)
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val answer = buildString {
                if (parts != null) for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (part.isNotBlank()) append(part)
                }
            }.trim()
            if (answer.isBlank()) "O modelo não enviou uma resposta em texto. Tente novamente."
            else answer
        } finally { connection.disconnect() }
    }
}
