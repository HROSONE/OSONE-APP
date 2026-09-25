package com.osone.app

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
}
