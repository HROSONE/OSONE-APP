package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    @Test fun keepsSettingsAndRoutinesButNeverKeys() {
        val text = SettingsBackup.build(mapOf("chat_provider" to "groq", "google_search" to true, "live_level_x" to 2,
            "key_tavily" to "tvly-secreto", "api_token" to "abc", "echo" to 0.5f),
            JSONArray().put(JSONObject().put("id", "r1").put("titulo", "Remédio")), 1L)
        assertFalse(text.contains("tvly-secreto"))
        assertFalse(text.contains("abc"))
        val parsed = SettingsBackup.parse(text)
        assertEquals("groq", parsed.settings["chat_provider"])
        assertEquals(true, parsed.settings["google_search"])
        assertEquals(1, parsed.routines.length())
    }

    @Test fun numbersComeBackInThePreferenceType() {
        assertEquals(2, SettingsBackup.number(2, 5))
        assertEquals(2L, SettingsBackup.number(2, 5L))
        assertEquals(0.5f, SettingsBackup.number(0.5, 1f))
        assertEquals(7, SettingsBackup.number(7, null))
        assertTrue(runCatching { SettingsBackup.parse("{\"versao\":9}") }.isFailure)
    }
}
