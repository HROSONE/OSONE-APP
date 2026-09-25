package com.osone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineTriggerTest {
    @Test fun allTermsMustAppearIgnoringCaseAndAccents() {
        assertTrue(RoutineTrigger.matches("WhatsApp, Ana", "WhatsApp", "Ana Souza", "Oi, tudo bem?"))
        assertFalse(RoutineTrigger.matches("WhatsApp, Ana", "WhatsApp", "Bruno", "Oi"))
        assertTrue(RoutineTrigger.matches("banco", "Nubank", "Compra aprovada", "Banco: R$ 20"))
        assertTrue(RoutineTrigger.matches("promocao", "Loja", "Promoção relâmpago", ""))
        assertFalse(RoutineTrigger.matches(" , ", "WhatsApp", "Ana", "Oi"))
    }

    @Test fun describesTheNotification() {
        assertTrue(RoutineTrigger.describe("WhatsApp", "Ana", "Oi").startsWith("Notificação de WhatsApp — Ana: Oi"))
    }
}
