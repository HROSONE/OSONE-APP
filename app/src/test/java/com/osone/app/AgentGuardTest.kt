package com.osone.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGuardTest {
    @Test fun riskyLabelsAreRecognized() {
        listOf("Enviar", "ENVIAR MENSAGEM", "Pagar agora", "Comprar", "Finalizar compra", "Excluir conversa", "Confirmar Pix",
            "Transferência", "Desinstalar", "Publicar").forEach { assertTrue(it, AgentGuard.isRisky(it)) }
        listOf("Voltar", "Pesquisar", "Configurações", "Conversas", "", "Enviados recentemente")
            .forEach { assertFalse(it, AgentGuard.isRisky(it)) }
    }

    @Test fun safeTapsPassStraightAway() {
        assertNull(AgentGuard().check("Abrir", confirmed = false))
    }

    @Test fun riskyTapNeedsAnAskedThenConfirmedCall() {
        var now = 0L
        val guard = AgentGuard { now }
        // Confirmar de primeira não vale: o modelo precisa ter perguntado antes.
        assertNotNull(guard.check("Enviar", confirmed = true))
        now += 5_000
        assertNull(guard.check("enviar", confirmed = true))
        // Depois de usada, a confirmação não serve para o próximo envio.
        assertNotNull(guard.check("Enviar", confirmed = true))
    }

    @Test fun confirmationExpires() {
        var now = 0L
        val guard = AgentGuard { now }
        assertNotNull(guard.check("Pagar", confirmed = false))
        now += AgentGuard.WINDOW_MS + 1
        assertNotNull(guard.check("Pagar", confirmed = true))
    }
}
