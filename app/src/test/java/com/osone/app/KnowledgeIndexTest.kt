package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeIndexTest {
    private val menu = KnowledgeSource("1", "Cardápio da Padaria Sol", "texto",
        "Pão francês custa R$ 1,20. Bolo de cenoura com chocolate custa R$ 35. Abrimos às 6h e fechamos às 20h.", 0)
    private val delivery = KnowledgeSource("2", "Entregas", "texto",
        "Fazemos entregas no bairro Centro e no Jardim América. A taxa de entrega é R$ 5. Pedidos pelo WhatsApp.", 0)

    @Test fun searchFindsTheRightSourceIgnoringAccents() {
        val hits = KnowledgeIndex.search(listOf(menu, delivery), "Qual a taxa de entrega no jardim america?")
        assertEquals("Entregas", hits.first().title)
        assertTrue(KnowledgeIndex.search(listOf(menu, delivery), "quanto custa o bolo de cenoura").first().text.contains("35"))
        assertTrue(KnowledgeIndex.search(listOf(menu, delivery), "de que para").isEmpty())
    }

    @Test fun chunksCoverLongTextWithLimitedSize() {
        val text = (1..200).joinToString(" ") { "Frase número $it da política de trocas." }
        val parts = KnowledgeIndex.chunks(text)
        assertTrue(parts.size > 3)
        assertTrue(parts.all { it.length <= 1_200 })
        assertTrue(parts.last().contains("200"))
    }

    @Test fun smallBaseGoesInlineAndLargeBaseUsesSearch() {
        val small = KnowledgeIndex.promptBlock(listOf(menu), "Você é o atendente da Padaria Sol.", strict = true)
        assertTrue(small.contains("R$ 1,20"))
        assertTrue(small.contains("atendente da Padaria Sol"))
        assertTrue(small.contains("não invente"))
        val big = KnowledgeSource("3", "Manual", "pdf", "x".repeat(KnowledgeIndex.INLINE_LIMIT + 1), 0)
        val large = KnowledgeIndex.promptBlock(listOf(big), "", strict = false)
        assertTrue(large.contains("knowledge_search"))
        assertFalse(large.contains("xxxxxxxx"))
        assertEquals("", KnowledgeIndex.promptBlock(emptyList(), " ", strict = false))
    }
}
