package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePlanTest {
    private val w = 1080
    private val h = 2340

    @Test fun doubleTapIsTwoQuickTapsOnTheSamePoint() {
        val plan = GesturePlan.build("toque_duplo", 500, 900, null, null, null, w, h)
        assertEquals(2, plan.strokes.size)
        assertTrue(plan.strokes.all { it.still && it.fromX == 500 && it.fromY == 900 })
        assertTrue(plan.strokes[1].start >= plan.strokes[0].start + plan.strokes[0].duration)
    }

    @Test fun pinchWithoutCoordinatesUsesTheCenterAndOpensOrCloses() {
        val zoomIn = GesturePlan.build("ampliar", null, null, null, null, null, w, h)
        val (startIn, endIn) = GesturePlan.spread(zoomIn)
        assertTrue(endIn > startIn)
        assertEquals(2, zoomIn.strokes.size)
        assertTrue(zoomIn.strokes.all { it.start == 0L })
        val (startOut, endOut) = GesturePlan.spread(GesturePlan.build("reduzir", null, null, null, null, 60, w, h))
        assertTrue(endOut < startOut)
    }

    @Test fun pinchNearTheEdgeStaysOnScreen() {
        val plan = GesturePlan.build("ampliar", 10, 10, null, null, 90, w, h)
        assertTrue(plan.strokes.all { s -> listOf(s.fromX, s.toX).all { it in 0 until w } && listOf(s.fromY, s.toY).all { it in 0 until h } })
    }

    @Test fun dragHoldsBeforeMovingAndNeedsBothEnds() {
        val plan = GesturePlan.build("arrastar", 200, 1500, 800, 400, null, w, h)
        assertTrue(plan.hold > 0)
        assertEquals(StrokeSpec(200, 1500, 800, 400, 0, 700), plan.strokes.single())
        val missing = runCatching { GesturePlan.build("arrastar", 200, 1500, null, null, null, w, h) }
        assertTrue(missing.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun rejectsOffScreenAndUnknownGestures() {
        assertTrue(runCatching { GesturePlan.build("segurar", 5000, 10, null, null, null, w, h) }.isFailure)
        assertTrue(runCatching { GesturePlan.build("girar", 10, 10, null, null, null, w, h) }.isFailure)
        assertEquals(800L, GesturePlan.build("segurar", 10, 10, null, null, null, w, h).strokes.single().duration)
    }
}
