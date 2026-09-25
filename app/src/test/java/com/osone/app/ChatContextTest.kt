package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatContextTest {
    private fun conversation(vararg sizes: Int) = sizes.mapIndexed { index, size ->
        ChatMessage(if (index % 2 == 0) "user" else "model", "x".repeat(size))
    }

    @Test fun keepsRecentMessagesThatFitTheBudget() {
        val history = conversation(501, 502, 503, 504, 100)
        val window = ChatContext.window(history, maxChars = 1_200, maxMessages = 40)
        // 100 + 504 + 503 cabem; a próxima passaria de 1.200. A janela abre numa fala do usuário.
        assertEquals(listOf(2, 3, 4), window.map { history.indexOf(it) })
    }

    @Test fun currentRequestAlwaysGoesEvenWhenHuge() {
        val history = conversation(10, 10, 50_000)
        assertEquals(listOf(history.last()), ChatContext.window(history, maxChars = 1_000, maxMessages = 20))
    }

    @Test fun respectsMessageLimitAndStartsWithUser() {
        val history = conversation(*IntArray(31) { 10 })
        val window = ChatContext.window(history, maxChars = 100_000, maxMessages = 12)
        assertEquals("user", window.first().role)
        assertEquals(history.last(), window.last())
        assertEquals(11, window.size)
        assertEquals(emptyList<ChatMessage>(), ChatContext.window(emptyList(), 100, 10))
    }
}
