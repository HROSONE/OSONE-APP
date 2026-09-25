package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ImageGenException(message: String) : Exception(message)

/**
 * Cria imagens com o modelo de imagem do Gemini disponível na chave (ex.: "Flash Image").
 * O modelo é escolhido pela lista de modelos da própria chave, sem ID fixo. Rede e JSON puros: testável na JVM.
 */
object ImageGen {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    @Volatile private var cachedModel: String? = null

    /** Melhor modelo de imagem da lista (generateContent + "image" no nome, preferindo flash e versões novas). */
    fun pickModel(listJson: String): String? {
        val models = JSONObject(listJson).optJSONArray("models") ?: JSONArray()
        val candidates = (0 until models.length()).mapNotNull { models.optJSONObject(it) }.filter { model ->
            val name = model.optString("name").lowercase()
            val methods = model.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
            name.contains("image") && methods.contains("generateContent") && !name.contains("imagen") &&
                !name.contains("embedding")
        }.map { it.optString("name").removePrefix("models/") }
        return candidates.sortedWith(compareByDescending<String> { it.contains("flash") }
            .thenByDescending { !it.contains("preview") }
            .thenByDescending { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.value?.toDoubleOrNull() ?: 0.0 }).firstOrNull()
    }

    /** Imagens (base64 e tipo) e texto de uma resposta generateContent. */
    fun parse(body: String): Pair<List<Pair<String, String>>, String> {
        val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts") ?: JSONArray()
        val images = ArrayList<Pair<String, String>>()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            (part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data"))?.let { data ->
                val encoded = data.optString("data")
                if (encoded.isNotBlank()) images += encoded to data.optString("mimeType", data.optString("mime_type", "image/png"))
            }
            part.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it) }
        }
        return images to text.toString().trim()
    }

    /** Bloqueante (fora da thread principal). Devolve (base64, tipo) da primeira imagem. */
    fun generate(key: String, prompt: String): Pair<String, String> {
        val model = cachedModel ?: request("$BASE/models?pageSize=200", key, null).let { (status, body) ->
            if (status !in 200..299) throw ImageGenException(explain(status))
            pickModel(body) ?: throw ImageGenException("Esta chave Gemini não tem acesso a nenhum modelo de imagem.")
        }.also { cachedModel = it }
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt.take(2_000))))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val (status, body) = request("$BASE/models/$model:generateContent", key, payload.toString())
        if (status !in 200..299) {
            if (status == 404) cachedModel = null
            throw ImageGenException(explain(status))
        }
        val (images, text) = parse(body)
        return images.firstOrNull() ?: throw ImageGenException(
            if (text.isNotBlank()) "O modelo não gerou imagem: ${text.take(160)}" else "O modelo não gerou imagem para esse pedido.")
    }

    fun explain(status: Int): String = when (status) {
        429 -> "Cota de imagens da chave Gemini esgotada (o plano gratuito pode não incluir imagens)."
        401, 403 -> "A chave Gemini não tem acesso ao modelo de imagem."
        404 -> "Modelo de imagem indisponível para esta chave."
        400 -> "O pedido de imagem foi recusado (pode ter violado as regras de conteúdo)."
        else -> "A criação de imagem falhou (HTTP $status)."
    }

    private fun request(address: String, key: String, body: String?): Pair<Int, String> {
        val connection = URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 90_000
            connection.setRequestProperty("x-goog-api-key", key)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally { connection.disconnect() }
    }
}
