package com.osone.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    private fun result(vararg words: Pair<String, Double>) = """{"result":[""" +
        words.joinToString(",") { (word, conf) -> """{"word":"$word","conf":$conf}""" } + """],"text":"x"}"""

    @Test fun greetingFollowedByNameWakes() {
        assertTrue(WakePhrase.matches(result("ei" to 0.9, "ostie" to 0.7)))
        assertTrue(WakePhrase.matches(result("oi" to 0.8, "hóstia" to 0.65)))
    }

    @Test fun nameAloneNeedsHighConfidence() {
        assertFalse(WakePhrase.matches(result("ostie" to 0.7)))
        assertTrue(WakePhrase.matches(result("ostie" to 0.9)))
    }

    @Test fun lowConfidenceOrOtherWordsDoNotWake() {
        assertFalse(WakePhrase.matches(result("ei" to 0.9, "ostie" to 0.4)))
        assertFalse(WakePhrase.matches(result("[unk]" to 1.0)))
        assertFalse(WakePhrase.matches("""{"text":""}"""))
        assertFalse(WakePhrase.matches("não é json"))
    }

    @Test fun grammarKeepsUnknownBucket() {
        val phrases = JSONArray(WakePhrase.grammar)
        assertEquals("[unk]", phrases.getString(phrases.length() - 1))
    }

    @Test fun findsTheModelFolderInOldAndNewZips() {
        // Formato antigo (vosk-model-small-pt-0.3): final.mdl na pasta do modelo.
        assertEquals("vosk-model-small-pt-0.3/", WakePhrase.modelRoot(listOf("vosk-model-small-pt-0.3/",
            "vosk-model-small-pt-0.3/final.mdl", "vosk-model-small-pt-0.3/ivector/final.ie")))
        // Formato novo: am/final.mdl.
        assertEquals("modelo/", WakePhrase.modelRoot(listOf("modelo/conf/model.conf", "modelo/am/final.mdl", "modelo/ivector/final.ie")))
        // Zip sem pasta raiz.
        assertEquals("", WakePhrase.modelRoot(listOf("final.mdl", "mfcc.conf")))
        assertEquals(null, WakePhrase.modelRoot(listOf("leia-me.txt")))
    }
}
