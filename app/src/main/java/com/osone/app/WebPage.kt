package com.osone.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * read_url: abre uma página da web e devolve o texto principal para o modelo ler (notícias, artigos).
 * Só HttpURLConnection e texto puro: testável na JVM.
 */
object WebPage {
    const val NAME = "read_url"
    private const val MAX_BYTES = 1_500_000
    const val MAX_TEXT = 8_000

    fun declaration(): JSONObject = JSONObject()
        .put("name", NAME)
        .put("description", "Abra um link (de uma pesquisa ou dito pelo usuário) e leia o texto da página: notícia, artigo, " +
            "receita, documentação. Use depois de web_search quando o resumo não bastar.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("url", JSONObject().put("type", "STRING").put("description", "Endereço http(s) da página")))
            .put("required", org.json.JSONArray().put("url")))

    /** Bloqueante (rodar fora da thread principal). */
    fun read(address: String): JSONObject {
        val url = address.trim()
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true))
            return JSONObject().put("erro", "Informe um link que comece com https://.")
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) OSTIE")
                connection.setRequestProperty("Accept", "text/html,text/plain;q=0.9,*/*;q=0.5")
                connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7")
                val status = connection.responseCode
                if (status !in 200..299) return JSONObject().put("erro", "A página respondeu HTTP $status.")
                val type = connection.contentType.orEmpty().lowercase()
                if (type.isNotBlank() && !type.contains("html") && !type.contains("text"))
                    return JSONObject().put("erro", "O link não é uma página de texto ($type).")
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16_384)
                    while (out.size() < MAX_BYTES) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)
                val html = String(bytes, runCatching { charset(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
                page(html, url)
            } finally { connection.disconnect() }
        } catch (failure: Exception) {
            JSONObject().put("erro", "Não consegui abrir a página (${failure.javaClass.simpleName}).")
        }
    }

    /** Título e texto legível de um HTML, sem menus, scripts nem estilos. */
    fun page(html: String, url: String): JSONObject {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty()
        val text = text(html)
        if (text.length < 80) return JSONObject().put("erro", "A página não tem texto legível (pode exigir login ou JavaScript).")
            .put("titulo", title).put("link", url)
        return JSONObject().put("titulo", title.take(200)).put("link", url)
            .put("texto", text.take(MAX_TEXT)).put("cortado", text.length > MAX_TEXT)
    }

    fun text(html: String): String {
        val flags = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        // O conteúdo principal, quando a página marca (article/main); senão, o corpo inteiro.
        val body = Regex("<article[^>]*>(.*?)</article>", flags).findAll(html).joinToString("\n") { it.groupValues[1] }
            .ifBlank { Regex("<main[^>]*>(.*?)</main>", flags).find(html)?.groupValues?.get(1).orEmpty() }
            .ifBlank { html }
        val cleaned = body
            .replace(Regex("<!--.*?-->", flags), " ")
            .replace(Regex("<(script|style|noscript|svg|nav|header|footer|aside|form|iframe|template)[^>]*>.*?</\\1>", flags), " ")
            .replace(Regex("<(br|/p|/div|/h[1-6]|/li|/tr|/section|/article|/blockquote)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
            .replace(Regex("<[^>]+>"), " ")
        return decode(cleaned).lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.isNotEmpty() && it != "•" }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ccedil" to "ç", "atilde" to "ã", "otilde" to "õ", "aacute" to "á", "eacute" to "é", "iacute" to "í",
        "oacute" to "ó", "uacute" to "ú", "acirc" to "â", "ecirc" to "ê", "ocirc" to "ô", "agrave" to "à",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“")

    private fun decode(text: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(text) { match ->
        val code = match.groupValues[1]
        when {
            code.startsWith("#x", true) -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> entities[code.lowercase()]
        } ?: match.value
    }

    /** Links (http/https) citados num texto, na ordem, sem repetir: para as fontes da tela do Live. */
    fun links(text: String): List<String> = Regex("https?://[^\\s)\\]\"'<>]+").findAll(text)
        .map { it.value.trimEnd('.', ',', ';') }.distinct().toList()
}
