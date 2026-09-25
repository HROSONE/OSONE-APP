package com.osone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPolicyTest {
    @Test fun pausesLowOrSavingAndResumesWithMargin() {
        assertTrue(BatteryPolicy.shouldPause(14, charging = false, powerSave = false, paused = false))
        assertFalse(BatteryPolicy.shouldPause(15, charging = false, powerSave = false, paused = false))
        // Pausada: só volta com 20% (evita liga/desliga em 15%).
        assertTrue(BatteryPolicy.shouldPause(17, charging = false, powerSave = false, paused = true))
        assertFalse(BatteryPolicy.shouldPause(20, charging = false, powerSave = false, paused = true))
        assertTrue(BatteryPolicy.shouldPause(80, charging = false, powerSave = true, paused = false))
        assertFalse(BatteryPolicy.shouldPause(5, charging = true, powerSave = true, paused = true))
    }
}
