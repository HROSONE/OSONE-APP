package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilyApiTest {
    @Test fun parsesResultsWithDates() {
        val body = JSONObject().put("results", JSONArray()
            .put(JSONObject().put("title", "Gemini 4").put("url", "https://blog.google/g4").put("content", "Lançado\nhoje")
                .put("published_date", "2026-09-24T10:00:00Z"))
            .put(JSONObject().put("title", "sem link"))).toString()
        val list = TavilyApi.parse(body).getJSONArray("resultados")
        assertEquals(1, list.length())
        assertEquals("2026-09-24", list.getJSONObject(0).getString("data"))
        assertEquals("Lançado hoje", list.getJSONObject(0).getString("trecho"))
        assertTrue(TavilyApi.parse("{}").has("resultado"))
        assertTrue(TavilyApi.explain(432).contains("Cota"))
    }
}
