package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMarksTest {
    private val w = 1080
    private val h = 2340

    @Test fun marksAreNumberedTopToBottomLeftToRight() {
        val marks = ScreenMarks.pick(listOf(
            ScreenMarks.Box(600, 1000, 800, 1100, "Direita"),
            ScreenMarks.Box(100, 1000, 300, 1100, "Esquerda"),
            ScreenMarks.Box(100, 200, 300, 300, "Topo")), w, h)
        assertEquals(listOf("Topo", "Esquerda", "Direita"), marks.map { it.label })
        assertEquals(listOf(1, 2, 3), marks.map { it.number })
        assertEquals(200 to 250, marks[0].x to marks[0].y)
    }

    @Test fun skipsBackgroundsTinyAndDuplicatedControls() {
        val marks = ScreenMarks.pick(listOf(
            ScreenMarks.Box(0, 0, w, h, "Fundo"),
            ScreenMarks.Box(10, 10, 14, 14, "Pontinho"),
            ScreenMarks.Box(100, 500, 500, 700, "Cartão"),
            ScreenMarks.Box(110, 510, 490, 690, "Botão dentro"),
            ScreenMarks.Box(100, 900, 300, 1000, "Texto", clickable = false),
            ScreenMarks.Box(100, 1200, 900, 1300, "Campo", clickable = false, editable = true)), w, h)
        // No mesmo lugar fica o menor (mais preciso); texto sem toque fica de fora; campo editável entra.
        assertEquals(listOf("Botão dentro", "Campo"), marks.map { it.label })
    }

    @Test fun limitAndLookup() {
        val many = (0 until 100).map { ScreenMarks.Box(10 + (it % 10) * 100, 100 + (it / 10) * 150, 90 + (it % 10) * 100, 180 + (it / 10) * 150, "b$it") }
        val marks = ScreenMarks.pick(many, w, h)
        assertEquals(ScreenMarks.LIMIT, marks.size)
        assertEquals("b0", ScreenMarks.find(marks, 1)?.label)
        assertNull(ScreenMarks.find(marks, 99))
    }

    @Test fun gridUsesRoundRealPixels() {
        assertEquals(150, ScreenMarks.gridStep(w, h))
        val lines = ScreenMarks.gridLines(w, 150)
        assertEquals(150, lines.first())
        assertTrue(lines.last() < w)
        assertEquals(512, ScreenMarks.toImage(1080, 1024f / 2160f))
    }

    @Test fun framesRepeatOnlyAsHeartbeat() {
        val gray = IntArray(FrameChange.SIDE * FrameChange.SIDE) { 0xFF808080.toInt() }
        val same = FrameChange.signature(gray)
        assertTrue(FrameChange.shouldSend(null, same, 0, 0))
        assertFalse(FrameChange.shouldSend(same, same.copyOf(), lastSentAt = 1_000, now = 2_000))
        assertTrue(FrameChange.shouldSend(same, same.copyOf(), lastSentAt = 1_000, now = 1_000 + FrameChange.HEARTBEAT_MS))
        val moved = gray.copyOf().also { it[57] = 0xFFFFFFFF.toInt() }
        assertTrue(FrameChange.changed(same, FrameChange.signature(moved)))
    }

    @Test fun holdAndDragDurations() {
        assertEquals(3000L, GesturePlan.build("segurar", 10, 10, null, null, null, w, h, 3000).strokes.single().duration)
        assertEquals(100L, GesturePlan.build("segurar", 10, 10, null, null, null, w, h, 5).strokes.single().duration)
        assertEquals(2000L, GesturePlan.build("arrastar", 100, 100, 500, 100, null, w, h, 2000).strokes.single().duration)
    }
}
