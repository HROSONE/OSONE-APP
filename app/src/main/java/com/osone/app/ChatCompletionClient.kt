package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ChatProviderHttpException(val provider: ChatProvider, val status: Int) :
    Exception("${provider.label} respondeu HTTP $status. Confira a chave, o modelo e a cota.")

/**
 * Chat Completions compatível com OpenAI, com streaming SSE para OpenRouter e Groq. Com [functions], o modelo
 * usa as ferramentas do app: [runTool] executa cada pedido (bloqueante, fora da thread principal) e a resposta
 * volta ao modelo, até [MAX_TOOL_ROUNDS] rodadas. Se o modelo não aceitar ferramentas, responde sem elas.
 */
class ChatCompletionClient {
    fun streamAnswer(provider: ChatProvider, key: String, model: String, history: List<ChatMessage>,
        systemPrompt: String = DEFAULT_SYSTEM, readTimeoutMs: Int = 45_000,
        functions: JSONArray? = null, runTool: ((String, JSONObject) -> JSONObject)? = null,
        browserSearch: Boolean = false, onDowngrade: (String) -> Unit = {}, onPartial: (String) -> Unit): String {
        require(provider != ChatProvider.GEMINI)
        val endpoint = when (provider) {
            ChatProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
            ChatProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
            ChatProvider.GEMINI -> error("Use GeminiClient para Gemini")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        ChatContext.window(history, ChatContext.COMPAT_CHARS, ChatContext.COMPAT_MESSAGES).forEach { message ->
            messages.put(JSONObject().put("role", if (message.role == "model") "assistant" else "user")
                .put("content", message.text))
        }
        val tools = if (functions != null && functions.length() > 0 && runTool != null) OpenAiTools.fromGemini(functions) else null
        var useTools = tools != null
        // Pesquisa própria do GPT OSS no Groq; vai sozinha (sem as ferramentas do app) e cai fora se o Groq recusar.
        var useBrowser = browserSearch
        if (useBrowser) useTools = false
        val answer = StringBuilder()
        var round = 0
        while (true) {
            val prefix = answer.toString()
            val payload = JSONObject().put("model", model).put("stream", true).put("messages", messages)
            if (useTools) payload.put("tools", tools).put("tool_choice", "auto")
            if (useBrowser) payload.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
            val turn = try {
                stream(provider, key, endpoint, payload, readTimeoutMs) { text ->
                    onPartial(if (prefix.isEmpty()) text else "$prefix\n\n$text")
                }
            } catch (failure: Exception) {
                // Modelos sem suporte a ferramentas costumam responder 400/404/422 (ou 413 no limite gratuito do Groq).
                val refused = failure is ChatStreamInterrupted ||
                    failure is ChatProviderHttpException && failure.status in TOOL_REFUSALS
                if (round == 0 && useBrowser && (failure is ChatProviderHttpException || failure is ChatStreamInterrupted)) {
                    useBrowser = false
                    useTools = tools != null
                    onDowngrade("$model recusou a pesquisa própria do Groq (" +
                        (if (failure is ChatProviderHttpException) "HTTP ${failure.status}" else "erro no streaming") + "); respondendo sem ela.")
                    messages.getJSONObject(0).put("content", systemPrompt + NO_SEARCH)
                    onPartial("")
                    continue
                }
                if (round == 0 && useTools && refused) {
                    useTools = false
                    messages.getJSONObject(0).put("content", systemPrompt + NO_TOOLS)
                    onDowngrade("$model recusou as ferramentas do app (" +
                        (if (failure is ChatProviderHttpException) "HTTP ${failure.status}" else "erro no streaming") +
                        "); respondendo sem elas.")
                    onPartial("")
                    continue
                }
                // Depois de executar ferramentas, não repete a conversa (a ação seria refeita).
                if (round == 0) throw failure
                throw IllegalStateException("O modelo parou depois de usar uma ferramenta (" +
                    (if (failure is ChatProviderHttpException) "HTTP ${failure.status}" else failure.javaClass.simpleName) + ").")
            }
            if (turn.text.isNotBlank()) {
                if (answer.isNotEmpty()) answer.append("\n\n")
                answer.append(turn.text.trim())
            }
            val calls = turn.toolCalls()
            if (calls.isEmpty() || !useTools || runTool == null || round >= MAX_TOOL_ROUNDS) break
            round++
            messages.put(turn.assistantMessage())
            for (call in calls) {
                val result = try { runTool(call.name, call.arguments) }
                    catch (failure: Exception) { JSONObject().put("erro", failure.message?.take(160) ?: "Falha em ${call.name}.") }
                messages.put(OpenAiTools.toolMessage(call.id, result))
            }
        }
        return answer.toString().trim().ifEmpty { throw IllegalStateException("${provider.label} não enviou texto. Confira o modelo escolhido.") }
    }

    private fun stream(provider: ChatProvider, key: String, endpoint: String, payload: JSONObject, readTimeoutMs: Int,
        onText: (String) -> Unit): OpenAiTools.Turn {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw ChatProviderHttpException(provider, status)
            val turn = OpenAiTools.Turn(provider.label)
            fun process(data: String) { if (turn.accept(data)) onText(turn.text.toString()) }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val event = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        process(event.toString()); event.setLength(0)
                    } else if (line.startsWith("data:")) event.append(line.substring(5).trimStart())
                }
                process(event.toString())
            }
            turn
        } finally { connection.disconnect() }
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 5
        private val TOOL_REFUSALS = setOf(400, 404, 413, 422)
        private const val NO_SEARCH = " A pesquisa na web falhou agora: diga isso ao usuário em uma frase e não invente informação atual."
        private const val NO_TOOLS = " Nesta resposta as ferramentas do app estão indisponíveis: não diga que fez ações; " +
            "explique o que o usuário pode fazer ou peça para tentar com o Gemini."
        const val DEFAULT_SYSTEM = "Você é OSTIE, assistente pessoal do usuário. Responda no idioma do usuário com clareza, precisão e passos práticos quando relevantes. Considere o contexto anterior. Não invente ações externas."
    }
}
