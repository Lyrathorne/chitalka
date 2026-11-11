package ru.chitalka.app.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream

/** Remove publication-authored active content; Readium injects its own reader scripts afterwards. */
fun safeEpubMarkup(bytes: ByteArray): ByteArray {
    val document = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
    document.outputSettings().charset(Charsets.UTF_8).prettyPrint(false)
    for (element in document.allElements.toList()) {
        val tag = element.tagName().substringAfter(':').lowercase()
        if (tag in setOf("script", "iframe", "object", "embed", "base", "foreignobject") ||
            (tag == "meta" && element.attr("http-equiv").lowercase() in setOf("refresh", "content-security-policy"))) {
            element.remove(); continue
        }
        element.attributes().asList().forEach { attribute ->
            val key = attribute.key.lowercase()
            val value = attribute.value.filterNot { it.isWhitespace() || it.code < 32 }.lowercase()
            if (key.startsWith("on") || (key in setOf("href", "xlink:href", "src", "action", "formaction") &&
                    (value.startsWith("javascript:") || value.startsWith("vbscript:") || value.startsWith("data:text/html")))) element.removeAttr(attribute.key)
        }
    }
    fun qualified(tag: String, name: String) = if (':' in tag) tag.substringBefore(':') + ":" + name else name
    val html = document.allElements.firstOrNull { it.tagName().substringAfter(':').equals("html", ignoreCase = true) }
    val head = document.allElements.firstOrNull { it.tagName().substringAfter(':').equals("head", ignoreCase = true) }
        ?: html?.prependElement(qualified(html.tagName(), "head"))
    head?.prependElement(qualified(head.tagName(), "meta"))?.apply {
        attr("http-equiv", "Content-Security-Policy")
        attr("content", "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; media-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'")
    }
    return document.outerHtml().toByteArray(Charsets.UTF_8)
}
