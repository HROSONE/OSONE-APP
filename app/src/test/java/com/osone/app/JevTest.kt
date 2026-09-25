package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevTest {
    @Test fun bodyHasTypedQuestions() {
        val body = JevApi.body("Mensagem: oi", mapOf(
            "a" to JevApi.Question.Noul("Pede algo?"),
            "b" to JevApi.Question.Choice("Assunto?", mapOf("conta" to "Cobrança", "outro" to "Outro")),
            "c" to JevApi.Question.Score("Urgência?", listOf("pode esperar", "hoje", "agora"))))
        assertEquals(JevApi.MODEL, body.getString("model"))
        assertEquals("Mensagem: oi", body.getString("state"))
        val questions = body.getJSONObject("questions")
        assertEquals("noul", questions.getJSONObject("a").getString("type"))
        assertEquals("Cobrança", questions.getJSONObject("b").getJSONObject("criteria").getString("conta"))
        assertEquals(3, questions.getJSONObject("c").getJSONArray("criteria").length())
        assertTrue(runCatching { JevApi.body("x", emptyMap()) }.isFailure)
        assertTrue(runCatching { JevApi.body("x", mapOf("s" to JevApi.Question.Score("?", listOf("só um")))) }.isFailure)
    }

    @Test fun parsesChoiceNoulAndScore() {
        val answers = JevApi.parse("""{"model":"jev-1.13.0","answers":{
            "topic":{"type":"choice","choice":"billing","confidence":0.9,"probabilities":{"billing":0.9,"bug":0.1}},
            "urgent":{"type":"noul","noul":0.82},
            "level":{"type":"score","score":2.68,"confidence":0.5}}}""")
        assertEquals("billing", answers["topic"]?.choice)
        assertEquals(0.1, answers["topic"]!!.probabilities["bug"]!!, 1e-9)
        assertEquals(0.82, answers["urgent"]!!.noul!!, 1e-9)
        assertNull(answers["urgent"]?.choice)
        assertEquals(2.68, answers["level"]!!.score!!, 1e-9)
        assertTrue(runCatching { JevApi.parse("{}") }.exceptionOrNull() is JevException)
    }

    @Test fun chatRouteNeverDropsWhatTheRulesAsk() {
        fun answers(search: Double, action: Double) = mapOf(JevDecisions.SEARCH to JevApi.Answer(noul = search),
            JevDecisions.ACTION to JevApi.Answer(noul = action))
        // Sem Jev: tudo como antes.
        assertEquals(JevDecisions.ChatRoute(search = true, tools = true), JevDecisions.chatRoute(null, ruleSaysSearch = true))
        assertEquals(JevDecisions.ChatRoute(search = false, tools = true), JevDecisions.chatRoute(null, ruleSaysSearch = false))
        // Jev percebe notícia que a regra não pegou.
        assertTrue(JevDecisions.chatRoute(answers(0.9, 0.01), false).search)
        // Conversa simples: sem pesquisa e sem lista de ferramentas.
        assertEquals(JevDecisions.ChatRoute(false, false), JevDecisions.chatRoute(answers(0.1, 0.05), false))
        // A regra "pesquisa" vale mesmo se o Jev discordar, e pesquisar mantém as ferramentas.
        assertEquals(JevDecisions.ChatRoute(true, true), JevDecisions.chatRoute(answers(0.1, 0.05), true))
        // Na dúvida sobre ações, a lista fica.
        assertTrue(JevDecisions.chatRoute(answers(0.1, 0.3), false).tools)
    }

    @Test fun meaningFiltersAndMatch() {
        assertTrue(JevDecisions.isMeaningFilter("mensagem de trabalho urgente"))
        assertFalse(JevDecisions.isMeaningFilter("WhatsApp, Ana"))
        assertFalse(JevDecisions.isMeaningFilter("banco"))
        assertTrue(JevDecisions.notificationQuestion("x y z")[JevDecisions.MATCH] is JevApi.Question.Noul)
        assertTrue(JevDecisions.matches(mapOf(JevDecisions.MATCH to JevApi.Answer(noul = 0.8))))
        assertFalse(JevDecisions.matches(mapOf(JevDecisions.MATCH to JevApi.Answer(noul = 0.4))))
        assertFalse(JevDecisions.matches(emptyMap()))
    }
}
