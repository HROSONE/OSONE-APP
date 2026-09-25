package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {
    private fun chat(vararg texts: String) = texts.mapIndexed { i, t -> ChatMessage(if (i % 2 == 0) "user" else "model", t) }

    @Test fun titleComesFromTheFirstQuestion() {
        assertEquals("Qual a capital da Austrália?", ChatHistory.title(chat("Qual a capital da Austrália?", "Canberra.")))
        val long = ChatHistory.title(chat("Me explica com calma como funciona a fotossíntese nas plantas aquáticas de água doce"))
        assertTrue(long.endsWith("…") && long.length <= 49)
        assertEquals("Resuma", ChatHistory.title(chat("Resuma\n📎 relatorio.pdf")))
        assertEquals("Conversa", ChatHistory.title(emptyList()))
    }

    @Test fun searchFindsAllWordsIgnoringAccents() {
        val a = SavedConversation("a", "Viagem", 1, chat("Roteiro para Floripa", "Dia 1: praia da Joaquina e Lagoa da Conceição."))
        val b = SavedConversation("b", "Receita", 2, chat("Bolo de cenoura", "Use 3 cenouras."))
        val found = ChatHistory.search(listOf(a, b), "lagoa conceicao")
        assertEquals(listOf("a"), found.map { it.conversation.id })
        assertTrue(found.single().snippet.contains("Lagoa da Conceição"))
        assertEquals(2, ChatHistory.search(listOf(a, b), "  ").size)
    }

    @Test fun jsonRoundTrip() {
        val original = SavedConversation("x1", "Teste", 42L, chat("oi", "olá"))
        assertEquals(original, ChatHistory.fromJson(ChatHistory.toJson(original)))
        assertEquals(null, ChatHistory.fromJson("{quebrado"))
    }
}
