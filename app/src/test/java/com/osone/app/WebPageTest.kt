package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageTest {
    private val article = """
        <html><head><title>Gemini 4 chega &amp; muda tudo</title><style>.x{color:red}</style></head>
        <body><nav>Menu Início Esportes</nav><header>Portal</header>
        <article><h1>Google anuncia o Gemini 4</h1><p>O Google apresentou nesta quinta&#8209;feira o novo modelo, com
        respostas mais r&aacute;pidas e melhor racioc&iacute;nio.</p><script>track()</script>
        <ul><li>Mais rápido</li><li>Mais barato</li></ul><p>Disponível hoje no app.</p></article>
        <footer>© Portal</footer></body></html>
    """.trimIndent()

    @Test fun keepsTheArticleAndDropsMenusScriptsAndStyles() {
        val page = WebPage.page(article, "https://exemplo.com/gemini-4")
        assertEquals("Gemini 4 chega & muda tudo", page.getString("titulo"))
        val text = page.getString("texto")
        assertTrue(text.contains("Google anuncia o Gemini 4"))
        assertTrue(text.contains("respostas mais rápidas e melhor raciocínio"))
        assertTrue(text.contains("• Mais rápido"))
        assertFalse(text.contains("Menu Início"))
        assertFalse(text.contains("track()"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("© Portal"))
        assertFalse(page.getBoolean("cortado"))
    }

    @Test fun pagesWithoutTextExplainWhy() {
        assertTrue(WebPage.page("<html><body><div id=app></div><script>render()</script></body></html>", "https://a.b").has("erro"))
        assertTrue(WebPage.read("ftp://exemplo.com").has("erro"))
    }

    @Test fun findsLinksInSummaries() {
        val summary = "Resposta.\n\nFontes:\n• G1 — https://g1.globo.com/tec/x.ghtml\n• Blog (https://blog.google/gemini-4/).\n• G1 — https://g1.globo.com/tec/x.ghtml"
        assertEquals(listOf("https://g1.globo.com/tec/x.ghtml", "https://blog.google/gemini-4/"), WebPage.links(summary))
    }
}
