package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGateTest {
    private fun frame(amplitude: Int): ByteArray {
        val bytes = ByteArray(3200)
        for (i in 0 until 1600) {
            val value = if (i % 2 == 0) amplitude else -amplitude
            bytes[2 * i] = (value and 0xFF).toByte()
            bytes[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test fun levelIsRms() {
        assertEquals(1000.0, VoiceGate.level(frame(1000), 3200), 0.5)
        assertEquals(0.0, VoiceGate.level(ByteArray(0), 0), 0.0)
    }

    @Test fun silenceIsSkippedAndVoiceBringsPreRoll() {
        val gate = VoiceGate()
        repeat(10) { assertTrue(gate.offer(frame(20), 3200).isEmpty()) }
        assertFalse(gate.open)
        val out = gate.offer(frame(4000), 3200)
        assertEquals(VoiceGate.PRE_ROLL + 1, out.size)
        assertTrue(gate.open)
        // Depois da voz, segue entregando o silêncio por um tempo e então fecha.
        repeat(VoiceGate.HANGOVER) { assertEquals(1, gate.offer(frame(20), 3200).size) }
        assertTrue(gate.offer(frame(20), 3200).isEmpty())
        assertFalse(gate.open)
    }

    @Test fun noisyRoomRaisesTheBar() {
        val gate = VoiceGate()
        repeat(200) { gate.offer(frame(600), 3200) }
        // Ruído constante de ventilador não abre a porta; voz bem mais alta abre.
        assertTrue(gate.offer(frame(700), 3200).isEmpty())
        assertTrue(gate.offer(frame(5000), 3200).isNotEmpty())
    }

    @Test fun yieldsToCallsAndOtherRecorders() {
        assertTrue(WakeYield.shouldYield(1, 0))
        assertTrue(WakeYield.shouldYield(0, 2))
        assertTrue(WakeYield.shouldYield(0, 3))
        assertFalse(WakeYield.shouldYield(0, 0))
    }
}
