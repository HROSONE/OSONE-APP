package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FreshInfoTest {
    @Test fun newsAndPricesNeedSearch() {
        assertTrue(FreshInfo.needsSearch("Me fale as notícias no mundo da IA."))
        assertTrue(FreshInfo.needsSearch("quanto está a cotação do dólar?"))
        assertTrue(FreshInfo.needsSearch("Quem ganhou o jogo do Flamengo?"))
        assertTrue(FreshInfo.needsSearch("o que a OpenAI lançou esta semana"))
        assertTrue(FreshInfo.needsSearch("previsão do tempo para amanhã em Curitiba"))
        assertTrue(FreshInfo.needsSearch("Quais modelos saíram em 2026?"))
    }

    @Test fun ordinaryRequestsDoNotSpendSearches() {
        assertFalse(FreshInfo.needsSearch("Escreva um poema sobre o mar"))
        assertFalse(FreshInfo.needsSearch("cria um alarme para as 7h"))
        assertFalse(FreshInfo.needsSearch("o que tenho na agenda hoje?"))
        assertFalse(FreshInfo.needsSearch("agora me explica como funciona uma função em Kotlin"))
    }

    @Test fun instructionsCarryTodayAndTheNoInventingRule() {
        val date = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 25, 1, 50) }.time
        val withSearch = FreshInfo.instructions(date, canSearch = true)
        assertTrue(withSearch.contains("25/09/2026 01:50"))
        assertTrue(withSearch.contains("pesquise na web"))
        assertTrue(FreshInfo.instructions(date, canSearch = false).contains("não tem informação atualizada"))
        assertTrue(FreshInfo.withResults("notícias de IA", "{\"resultados\":[]}").startsWith("notícias de IA\n\n[Resultados"))
    }

    @Test fun explicitSearchCommandsUseThePreviousQuestion() {
        assertTrue(FreshInfo.needsSearch("pesquisa"))
        assertTrue(FreshInfo.needsSearch("busca na internet por favor"))
        assertTrue(FreshInfo.needsSearch("sobre o lançamento do gemini 4"))
        assertEquals("sobre o lançamento do gemini 4", FreshInfo.searchQuery("pesquisa", "sobre o lançamento do gemini 4"))
        assertEquals("sobre o lançamento do gemini 4", FreshInfo.searchQuery("pesquisa isso aí", "sobre o lançamento do gemini 4"))
        assertEquals("pesquisa o preço do iPhone 17", FreshInfo.searchQuery("pesquisa o preço do iPhone 17", "outra coisa"))
        assertEquals("pesquisa", FreshInfo.searchQuery("pesquisa", null))
        assertTrue(FreshInfo.searchFailed("pedido", "cota esgotada").contains("cota esgotada"))
    }

    @Test fun queriesUseTodaysYearForCurrentThings() {
        org.junit.Assert.assertEquals("notícias de hoje sobre futebol 2026", FreshInfo.datedQuery("notícias de hoje sobre futebol 2024", 2026))
        org.junit.Assert.assertEquals("preço do dólar hoje 2026", FreshInfo.datedQuery("preço do dólar hoje", 2026))
        // Pergunta histórica fica como veio.
        org.junit.Assert.assertEquals("final da Copa de 2014", FreshInfo.datedQuery("final da Copa de 2014", 2026))
        org.junit.Assert.assertEquals("últimas notícias 2025 e 2026", FreshInfo.datedQuery("últimas notícias 2025 e 2026", 2026))
    }

    @Test fun modelQueryIsCleanedOrFallsBack() {
        org.junit.Assert.assertEquals("jogo do Flamengo resultado", FreshInfo.cleanModelQuery("\"jogo do Flamengo resultado\"\n", "x"))
        org.junit.Assert.assertEquals("preço gasolina São Paulo", FreshInfo.cleanModelQuery("Consulta: preço gasolina São Paulo", "x"))
        org.junit.Assert.assertEquals("reserva", FreshInfo.cleanModelQuery("", "reserva"))
        org.junit.Assert.assertEquals("reserva", FreshInfo.cleanModelQuery("palavra ".repeat(40), "reserva"))
        org.junit.Assert.assertTrue(FreshInfo.queryWriter().contains("APENAS"))
    }
}
