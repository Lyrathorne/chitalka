package ru.chitalka.app.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class EpubContentTest {
    @Test fun missingHeadAndNamespacePrefixStillReceivePolicy() {
        for (html in listOf("<html><body><p>Readable</p></body></html>", "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\"><h:body>Readable</h:body></h:html>")) {
            val result = safeEpubMarkup(html.toByteArray()).toString(Charsets.UTF_8)
            assertTrue(result.contains("Content-Security-Policy")); assertTrue(result.contains("Readable"))
        }
    }
    @Test fun stripsPublicationCodeButPreservesTextStructureAndAddsPolicy() {
        val html = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Book</title><script>leak()</script><base href="https://bad.example/"/><meta http-equiv="refresh" content="0;url=https://bad.example"/></head><body onload="leak()"><h1 id="chapter">Глава</h1><p>Mira <b>was curious</b>.</p><a href="#footnote">1</a><aside id="footnote">Note</aside><a href="java&#x73;cript:leak()">bad</a><img src="images/book.png" onerror="leak()"/><iframe src="https://bad.example"/></body></html>"""
        val safe = Jsoup.parse(safeEpubMarkup(html.toByteArray()).toString(Charsets.UTF_8), "", Parser.xmlParser())
        assertTrue(safe.select("script,iframe,base").isEmpty())
        assertFalse(safe.selectFirst("body")!!.hasAttr("onload"))
        assertFalse(safe.selectFirst("img")!!.hasAttr("onerror"))
        assertEquals("images/book.png", safe.selectFirst("img")!!.attr("src"))
        assertEquals("#footnote", safe.selectFirst("a")!!.attr("href"))
        assertEquals("Глава", safe.getElementById("chapter")!!.text())
        assertEquals("Note", safe.getElementById("footnote")!!.text())
        assertEquals("Mira was curious.", safe.selectFirst("p")!!.text())
        assertFalse(safe.select("a").last()!!.hasAttr("href"))
        val policy = safe.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(policy.contains("connect-src 'none'")); assertTrue(policy.contains("frame-src 'none'"))
    }
}
