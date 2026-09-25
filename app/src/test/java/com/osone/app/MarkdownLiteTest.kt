package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    @Test fun inlineBoldItalicAndCode() {
        val segments = MarkdownLite.inline("O **Gemini 4** saiu *hoje* e usa `thinking`.")
        assertEquals(listOf("O ", "Gemini 4", " saiu ", "hoje", " e usa ", "thinking", "."), segments.map { it.text })
        assertTrue(segments[1].bold); assertTrue(segments[3].italic); assertTrue(segments[5].code)
        // Asterisco solto (multiplicação, 2 * 3) não vira itálico.
        assertEquals(1, MarkdownLite.inline("2 * 3 = 6").size)
        assertEquals(1, MarkdownLite.inline("arquivo_de_teste.txt").size)
    }

    @Test fun headingsListsRulesAndCode() {
        val lines = MarkdownLite.parse("## Novidades\n- um\n  - dois\n1. primeiro\n---\n```kotlin\nval x = 1\n```\ntexto")
        assertEquals(MarkdownLite.Kind.HEADING, lines[0].kind); assertEquals(2, lines[0].level)
        assertEquals(MarkdownLite.Kind.BULLET, lines[1].kind); assertEquals(1, lines[2].level)
        assertEquals("1.", lines[3].marker)
        assertEquals(MarkdownLite.Kind.RULE, lines[4].kind)
        assertEquals("val x = 1", lines[5].segments.single().text)
        assertEquals(MarkdownLite.Kind.TEXT, lines[6].kind)
    }

    @Test fun tablesBecomeLists() {
        val lines = MarkdownLite.parse("| Data | Título |\n|---|---|\n| 24/09 | **Gemini 4** |")
        assertEquals(2, lines.size)
        assertEquals("Data — Título", lines[0].segments.joinToString("") { it.text })
        assertEquals(MarkdownLite.Kind.BULLET, lines[1].kind)
        assertTrue(lines[1].segments.any { it.bold && it.text == "Gemini 4" })
    }

    @Test fun plainTextForCopy() {
        val plain = MarkdownLite.plain("**Oi**\n\n- a\n- *b*")
        assertEquals("Oi\n\n• a\n• b", plain)
        assertFalse(plain.contains("*"))
    }
}
