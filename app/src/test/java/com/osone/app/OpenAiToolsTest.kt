package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiToolsTest {
    private fun delta(delta: JSONObject) =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta))).toString()

    private fun callPiece(index: Int, id: Any? = null, name: String? = null, arguments: String? = null) =
        delta(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index).apply {
            if (id != null) put("id", id)
            put("function", JSONObject().apply {
                if (name != null) put("name", name)
                if (arguments != null) put("arguments", arguments)
            })
        })))

    @Test fun convertsGeminiDeclarationsToJsonSchema() {
        val declarations = JSONArray()
            .put(JSONObject().put("name", "set_alarm").put("description", "Cria alarme")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("hora", JSONObject().put("type", "INTEGER").put("description", "Hora"))
                        .put("dias", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))))
                    .put("required", JSONArray().put("hora"))))
            .put(JSONObject().put("name", "device_status").put("description", "Bateria"))
        val tools = OpenAiTools.fromGemini(declarations)
        assertEquals(2, tools.length())
        val first = tools.getJSONObject(0)
        assertEquals("function", first.getString("type"))
        val parameters = first.getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        val properties = parameters.getJSONObject("properties")
        assertEquals("integer", properties.getJSONObject("hora").getString("type"))
        assertEquals("Hora", properties.getJSONObject("hora").getString("description"))
        assertEquals("string", properties.getJSONObject("dias").getJSONObject("items").getString("type"))
        assertEquals("hora", parameters.getJSONArray("required").getString(0))
        // Sem parâmetros: objeto vazio, exigido pelo formato OpenAI.
        val empty = tools.getJSONObject(1).getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", empty.getString("type"))
        assertEquals(0, empty.getJSONObject("properties").length())
    }

    @Test fun accumulatesTextAndFragmentedToolCalls() {
        val turn = OpenAiTools.Turn("Groq")
        assertTrue(turn.accept(delta(JSONObject().put("content", "Vou "))))
        assertTrue(turn.accept(delta(JSONObject().put("content", "criar."))))
        assertFalse(turn.accept(callPiece(0, id = "call_a", name = "set_alarm", arguments = "{\"ho")))
        turn.accept(callPiece(0, arguments = "ra\": 7}"))
        turn.accept(callPiece(1, id = JSONObject.NULL, name = "device_status", arguments = ""))
        assertFalse(turn.accept("[DONE]"))
        assertEquals("Vou criar.", turn.text.toString())
        val calls = turn.toolCalls()
        assertEquals(2, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("set_alarm", calls[0].name)
        assertEquals(7, calls[0].arguments.getInt("hora"))
        assertEquals("call_1", calls[1].id)
        assertEquals(0, calls[1].arguments.length())

        val assistant = turn.assistantMessage()
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("Vou criar.", assistant.getString("content"))
        val sent = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_a", sent.getString("id"))
        assertEquals(7, JSONObject(sent.getJSONObject("function").getString("arguments")).getInt("hora"))

        val reply = OpenAiTools.toolMessage("call_a", JSONObject().put("resultado", "ok"))
        assertEquals("tool", reply.getString("role"))
        assertEquals("call_a", reply.getString("tool_call_id"))
        assertEquals("ok", JSONObject(reply.getString("content")).getString("resultado"))
    }

    @Test fun invalidArgumentsBecomeEmptyObjectAndNullContentIsIgnored() {
        val turn = OpenAiTools.Turn("OpenRouter")
        assertFalse(turn.accept(delta(JSONObject().put("content", JSONObject.NULL))))
        turn.accept(callPiece(0, id = "x", name = "memory_read", arguments = "{quebrado"))
        assertEquals(0, turn.toolCalls().single().arguments.length())
        assertTrue(turn.assistantMessage().isNull("content"))
    }

    @Test(expected = ChatStreamInterrupted::class) fun errorEventInterrupts() {
        OpenAiTools.Turn("Groq").accept(JSONObject().put("error", JSONObject().put("message", "tool_use_failed")).toString())
    }
}
