package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSearchApiTest {
    @Test fun urlCarriesQueryAndCxButNeverTheKey() {
        val url = GoogleSearchApi.url(" abc123:xyz ", "tempo em São Paulo", count = 30)
        assertTrue(url.startsWith("https://customsearch.googleapis.com/customsearch/v1?"))
        assertTrue(url.contains("cx=abc123%3Axyz"))
        assertTrue(url.contains("q=tempo+em+S%C3%A3o+Paulo"))
        assertTrue(url.contains("num=10"))
        assertFalse(url.contains("key="))
    }

    @Test fun parsesItemsIntoShortResults() {
        val body = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("title", "Previsão").put("link", "https://clima.example/sp").put("snippet", "Sol\ne 28 °C")
                .put("pagemap", JSONObject().put("metatags", JSONArray().put(JSONObject().put("article:published_time", "2026-09-25T08:00:00-03:00")))))
            .put(JSONObject().put("title", "Sem link"))).toString()
        val result = GoogleSearchApi.parse(body)
        val list = result.getJSONArray("resultados")
        assertEquals(1, list.length())
        assertEquals("https://clima.example/sp", list.getJSONObject(0).getString("link"))
        assertEquals("Sol e 28 °C", list.getJSONObject(0).getString("trecho"))
        assertEquals("2026-09-25", list.getJSONObject(0).getString("data"))
        assertTrue(result.has("instrucao"))
        assertTrue(GoogleSearchApi.parse("{}").has("resultado"))
    }

    private fun error(code: Int, reason: String, status: String, message: String) = JSONObject().put("error", JSONObject()
        .put("code", code).put("status", status).put("message", message)
        .put("errors", JSONArray().put(JSONObject().put("reason", reason)))).toString()

    @Test fun explainsCommonGoogleErrorsInPortuguese() {
        assertTrue(GoogleSearchApi.explain(429, error(429, "rateLimitExceeded", "RESOURCE_EXHAUSTED", "Quota exceeded"))
            .contains("Cota"))
        assertTrue(GoogleSearchApi.explain(403, error(403, "accessNotConfigured", "PERMISSION_DENIED", "API has not been used"))
            .contains("não está ativada"))
        assertTrue(GoogleSearchApi.explain(400, error(400, "badRequest", "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."))
            .contains("Chave da busca Google inválida"))
        assertTrue(GoogleSearchApi.explain(400, error(400, "invalid", "INVALID_ARGUMENT", "Request contains an invalid argument."))
            .contains("cx"))
        assertTrue(GoogleSearchApi.explain(500, null).contains("HTTP 500"))
    }
}
