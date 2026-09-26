package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorGuidesTest {
    @Test fun findsByPackageNameAndAlias() {
        assertEquals("CapCut", EditorGuides.find("com.lemon.lvoverseas")?.name)
        assertEquals("CapCut", EditorGuides.find("CAPCUT")?.name)
        assertEquals("KineMaster", EditorGuides.find("abre o kinemaster")?.name)
        assertEquals("LumaFusion", EditorGuides.find("Luma Fusion")?.name)
        assertNull(EditorGuides.find("com.whatsapp"))
        assertNull(EditorGuides.find(""))
    }

    @Test fun answerAlwaysHelps() {
        assertTrue(EditorGuides.answer("InShot").contains("Aparar"))
        assertTrue(EditorGuides.answer("InShot").contains("Exportar pode demorar"))
        assertTrue(EditorGuides.answer("Editor Qualquer").startsWith("Sem guia específico"))
    }

    @Test fun guideIsAttachedOncePerWindow() {
        var now = 0L
        val tracker = EditorGuides.Tracker { now }
        assertNotNull(tracker.guideFor("com.lemon.lvoverseas"))
        assertNull(tracker.guideFor("com.lemon.lvoverseas"))
        // Só pelo pacote exato: um app qualquer com "vn" no nome não recebe guia.
        assertNull(tracker.guideFor("com.example.vnpay"))
        now += EditorGuides.REPEAT_MS
        assertNotNull(tracker.guideFor("com.lemon.lvoverseas"))
    }
}
