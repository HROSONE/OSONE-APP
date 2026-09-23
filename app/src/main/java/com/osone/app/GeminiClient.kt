package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiHttpException(val status: Int) : Exception("O serviço respondeu HTTP $status. Confira o acesso ao modelo e a cota.") {
    val allowsFallback get() = status == 404 || status == 429 || status in 500..599
}

/** Resposta por SSE: o primeiro trecho aparece no chat sem aguardar o texto inteiro. */
class GeminiClient {
    fun streamAnswer(key: String, model: ChatModel, history: List<ChatMessage>, mode: ThinkingMode,
        attachment: JSONObject? = null,
        onPartial: (String) -> Unit): String {
        val contents = JSONArray()
        history.takeLast(12).forEachIndexed { index, message ->
            val parts = JSONArray().put(JSONObject().put("text", message.text))
            if (attachment != null && index == history.takeLast(12).lastIndex && message.role == "user")
                parts.put(attachment)
            contents.put(JSONObject().put("role", message.role).put("parts", parts))
        }
        val request = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "Você é OSONE APP, assistente pessoal de Henrique no Android. Responda naturalmente no idioma do usuário. Dê respostas claras, específicas e úteis; use o contexto da conversa, e apresente passos práticos quando necessários. Evite texto genérico e repetição. Seja honesto sobre incertezas. Não diga que abriu aplicativos, acessou arquivos ou usou ferramentas se não fez isso."))))
            .put("contents", contents)
        val thinking = if (model == ChatModel.GEMINI_25) JSONObject().put("thinkingBudget", when (mode) {
            ThinkingMode.FAST -> 0
            ThinkingMode.BALANCED -> 1024
            ThinkingMode.DEEP -> 4096
        }) else JSONObject().put("thinkingLevel", mode.value)
        request.put("generationConfig", JSONObject().put("thinkingConfig", thinking))
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/${model.id}:streamGenerateContent?alt=sse")
            .openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw GeminiHttpException(status)

            val answer = StringBuilder()
            fun processEvent(data: String) {
                if (data.isBlank()) return
                val candidate = JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: return
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought")) continue
                    val piece = part.optString("text")
                    if (piece.isNotEmpty()) { answer.append(piece); onPartial(answer.toString()) }
                }
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val event = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        processEvent(event.toString())
                        event.setLength(0)
                    } else if (line.startsWith("data:")) event.append(line.substring(5).trimStart())
                }
                processEvent(event.toString())
            }
            answer.toString().trim().ifEmpty { throw IllegalStateException("O modelo não enviou resposta em texto.") }
        } finally { connection.disconnect() }
    }
}
