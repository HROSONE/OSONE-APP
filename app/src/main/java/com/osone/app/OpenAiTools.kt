package com.osone.app

import org.json.JSONArray
import org.json.JSONObject

/** Resposta interrompida no meio do streaming (o provedor mandou um evento de erro). */
class ChatStreamInterrupted(message: String) : IllegalStateException(message)

/**
 * Ferramentas do app no formato Chat Completions (OpenAI), usado por Groq e OpenRouter.
 * Lógica pura (só org.json), testável sem Android.
 */
object OpenAiTools {
    class ToolCall(val id: String, val name: String, val arguments: JSONObject)

    /** Declarações no formato Gemini (tipos em maiúsculas) viram "tools" com JSON Schema. */
    fun fromGemini(declarations: JSONArray): JSONArray {
        val tools = JSONArray()
        for (i in 0 until declarations.length()) {
            val declaration = declarations.optJSONObject(i) ?: continue
            val parameters = declaration.optJSONObject("parameters")?.let(::schema)
                ?: JSONObject().put("type", "object").put("properties", JSONObject())
            tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", declaration.optString("name"))
                .put("description", declaration.optString("description"))
                .put("parameters", parameters)))
        }
        return tools
    }

    private fun schema(value: JSONObject): JSONObject {
        val copy = JSONObject()
        for (key in value.keys()) {
            val item = value.get(key)
            copy.put(key, when {
                key == "type" && item is String -> item.lowercase()
                key == "properties" && item is JSONObject -> JSONObject().apply {
                    for (name in item.keys()) item.optJSONObject(name)?.let { put(name, schema(it)) }
                }
                key == "items" && item is JSONObject -> schema(item)
                else -> item
            })
        }
        return copy
    }

    fun toolMessage(id: String, result: JSONObject): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result.toString())

    /** Junta os pedaços de uma resposta em streaming: o texto e as chamadas de ferramenta, que chegam fatiadas por índice. */
    class Turn(private val label: String) {
        private class Call(var id: String = "", val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())

        val text = StringBuilder()
        private val calls = java.util.TreeMap<Int, Call>()

        /** Processa um evento SSE; devolve true quando o texto cresceu. */
        fun accept(data: String): Boolean {
            if (data.isBlank() || data == "[DONE]") return false
            val message = JSONObject(data)
            if (message.has("error")) throw ChatStreamInterrupted("$label interrompeu a resposta. Tente outro modelo.")
            val delta = message.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return false
            delta.optJSONArray("tool_calls")?.let { list ->
                for (i in 0 until list.length()) {
                    val piece = list.optJSONObject(i) ?: continue
                    val call = calls.getOrPut(piece.optInt("index", calls.size)) { Call() }
                    (piece.opt("id") as? String)?.takeIf { it.isNotBlank() }?.let { call.id = it }
                    piece.optJSONObject("function")?.let { function ->
                        (function.opt("name") as? String)?.let { call.name.append(it) }
                        (function.opt("arguments") as? String)?.let { call.arguments.append(it) }
                    }
                }
            }
            val chunk = delta.opt("content") as? String ?: ""
            if (chunk.isEmpty()) return false
            text.append(chunk)
            return true
        }

        fun toolCalls(): List<ToolCall> = calls.entries.filter { it.value.name.isNotBlank() }.map { (index, call) ->
            val arguments = try { JSONObject(call.arguments.toString().ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            ToolCall(call.id.ifBlank { "call_$index" }, call.name.toString(), arguments)
        }

        /** O pedido do modelo volta ao histórico antes das respostas das ferramentas. */
        fun assistantMessage(): JSONObject = JSONObject().put("role", "assistant")
            .put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
            .put("tool_calls", JSONArray().apply {
                toolCalls().forEach { call ->
                    put(JSONObject().put("id", call.id).put("type", "function").put("function",
                        JSONObject().put("name", call.name).put("arguments", call.arguments.toString())))
                }
            })
    }
}
