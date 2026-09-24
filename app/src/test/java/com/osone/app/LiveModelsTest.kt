package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveModelsTest {
    private val catalog = """
        {"models": [
          {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash", "supportedGenerationMethods": ["generateContent"]},
          {"name": "models/gemini-2.5-flash-native-audio-preview-12-2025", "displayName": "Gemini 2.5 Flash Native Audio", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-3.8-live-preview", "displayName": "Gemini 3.8 Live", "supportedGenerationMethods": ["bidiGenerateContent", "countTokens"]},
          {"name": "models/gemini-3.5-live-translate", "displayName": "Gemini 3.5 Live Translate", "supportedGenerationMethods": ["bidiGenerateContent"]}
        ]}
    """.trimIndent()

    @Test fun onlyConversationalLiveModelsAreListedWithExactIds() {
        val models = LiveModel.parseCatalog(catalog)
        assertEquals(listOf("gemini-3.8-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025"), models.map { it.id })
        assertEquals("Gemini 3.8 Live", models.first().label)
        assertEquals(models, LiveModel.fromJson(LiveModel.toJson(models)))
    }

    @Test fun savedIdSurvivesEvenWhenNotListedAndFallbackIsBounded() {
        val known = LiveModel.parseCatalog(catalog)
        assertEquals("gemini-9-live", LiveModel.fromId("gemini-9-live", known).id)
        assertEquals(known.first(), LiveModel.fromId(null, known))
        val chosen = known.last()
        val candidates = LiveModel.candidates(chosen, true, known + LiveModel.DEFAULTS)
        assertEquals(chosen, candidates.first())
        assertEquals(3, candidates.size)
        assertEquals(listOf(chosen), LiveModel.candidates(chosen, false, known))
    }
}
