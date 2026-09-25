package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenTest {
    private fun model(name: String, vararg methods: String) = JSONObject().put("name", "models/$name")
        .put("supportedGenerationMethods", JSONArray(methods.toList()))

    @Test fun picksAFlashImageModelFromTheKeyList() {
        val list = JSONObject().put("models", JSONArray()
            .put(model("gemini-3.8-flash", "generateContent"))
            .put(model("imagen-4.0-generate", "predict"))
            .put(model("gemini-2.5-flash-image-preview", "generateContent"))
            .put(model("gemini-2.5-flash-image", "generateContent"))
            .put(model("gemini-3-pro-image-preview", "generateContent"))).toString()
        assertEquals("gemini-2.5-flash-image", ImageGen.pickModel(list))
        assertEquals(null, ImageGen.pickModel(JSONObject().put("models", JSONArray().put(model("gemini-3.8-flash", "generateContent"))).toString()))
    }

    @Test fun readsImagesAndText() {
        val body = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", "Aqui está"))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/png").put("data", "iVBORw0K"))))))).toString()
        val (images, text) = ImageGen.parse(body)
        assertEquals(listOf("iVBORw0K" to "image/png"), images)
        assertEquals("Aqui está", text)
        assertTrue(ImageGen.explain(429).contains("Cota"))
    }
}
