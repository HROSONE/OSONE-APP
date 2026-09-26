package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEditsTest {
    private val lines = listOf("## Pessoas", "- A Ana é minha irmã (01/09/2026)", "- Reunião toda semana às 9h",
        "- Mariana trabalha comigo", "- Gosto de banana", "Ana sem marcador não é anotação")

    @Test fun forgetMatchesWholeWordsOnly() {
        assertEquals(listOf(1), MemoryEdits.forgetMatches(lines, "Ana"))
        assertEquals(listOf(1), MemoryEdits.forgetMatches(lines, "ana é minha irmã"))
        assertEquals(listOf(2), MemoryEdits.forgetMatches(lines, "reuniao"))
        assertEquals(emptyList<Int>(), MemoryEdits.forgetMatches(lines, "an"))
        assertEquals(emptyList<Int>(), MemoryEdits.forgetMatches(lines, "Pessoas"))
    }

    @Test fun fullMemoryIsRefusedNotCut() {
        assertNull(MemoryEdits.fullMessage(60_000, 60_000))
        assertTrue(MemoryEdits.fullMessage(60_001, 60_000)!!.contains("memory_rewrite"))
    }
}
