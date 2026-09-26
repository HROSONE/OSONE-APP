package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRulesTest {
    @Test fun eachPlanUnlocksItsFeatures() {
        assertFalse(PlanRules.allows(Plan.GRATIS, PlanFeature.GAMES_EDITORS))
        assertTrue(PlanRules.allows(Plan.PRO, PlanFeature.GAMES_EDITORS))
        assertFalse(PlanRules.allows(Plan.PRO, PlanFeature.KNOWLEDGE))
        assertTrue(PlanRules.allows(Plan.EMPRESA, PlanFeature.KNOWLEDGE))
        assertEquals(Plan.GRATIS, Plan.fromId("desconhecido"))
        assertEquals(Plan.PRO, Plan.fromId("PRO"))
    }

    @Test fun freeAgentHasADailyLimit() {
        assertNull(PlanRules.agentAction(Plan.GRATIS, PlanRules.FREE_AGENT_ACTIONS - 1))
        assertNotNull(PlanRules.agentAction(Plan.GRATIS, PlanRules.FREE_AGENT_ACTIONS))
        assertNull(PlanRules.agentAction(Plan.PRO, 500))
        // Novo dia zera o contador.
        assertEquals(0, PlanRules.countFor("2026-09-25", 10, "2026-09-26"))
        assertEquals(7, PlanRules.countFor("2026-09-26", 7, "2026-09-26"))
    }

    @Test fun freeRoutinesAreLimited() {
        assertNull(PlanRules.newRoutine(Plan.GRATIS, 1, byNotification = false))
        assertNotNull(PlanRules.newRoutine(Plan.GRATIS, 2, byNotification = false))
        assertNotNull(PlanRules.newRoutine(Plan.GRATIS, 0, byNotification = true))
        assertNull(PlanRules.newRoutine(Plan.PRO, 25, byNotification = true))
        assertTrue(PlanRules.blocked(PlanFeature.KNOWLEDGE).contains("Empresa"))
    }
}
