package ru.chitalka.app.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

data class TextChapter(val title: String, val paragraphs: List<String>)
class ScannedDocxException(val bookTitle: String,val bookAuthor: String): IllegalArgumentException("DOCX содержит сканы без текстового слоя. Требуется распознавание текста (OCR).")
data class TextBook(val title: String, val author: String, val format: String, val chapters: List<TextChapter>) {
    val notice: String get() = if (format.contains("OCR")) "Текст распознан со сканов. Возможны ошибки OCR; проверьте важные фрагменты." else if (format == "PDF") "PDF открыт как текст: колонки и таблицы могут отличаться. OCR не выполняется."
        else "$format открыт как текст: сложное оформление и изображения не переносятся."
}

/** Local text-import-v1. Stable paths and the original-file hash are shared with Windows. */
object DocumentImporter {
    const val MAX_XML = 32 * 1024 * 1024
    const val MAX_TEXT = 8_000_000
    private fun clean(s: String) = s.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "").trim()
    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        // Android's Harmony DOM does not implement Xerces' disallow-doctype feature.
        // Reject declarations before parsing, including UTF-16/32 XML, and refuse all resolutions.
        val declarations = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
        require(!declarations.contains("<!DOCTYPE", true) && !declarations.contains("<!ENTITY", true)) { "DTD и внешние сущности в документе не поддерживаются" }
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        return f.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw org.xml.sax.SAXException("Внешние сущности запрещены") } }.parse(bytes.inputStream())
    }
    private fun Element.elements(name: String): List<Element> = getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }
    private fun children(e: Element) = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()
    private fun entry(zip: ZipFile, name: String): ByteArray {
        val e = zip.getEntry(name) ?: error("В документе отсутствует $name")
        require(e.size <= MAX_XML) { "XML документа превышает 32 МБ" }
        return zip.getInputStream(e).use { input -> input.readBounded(MAX_XML) }
    }
    private fun java.io.InputStream.readBounded(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(32768)
        while (true) { val n = read(buffer); if (n < 0) break; require(out.size().toLong() + n <= max) { "Документ превышает предел чтения" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    private fun checkZip(zip: ZipFile) { require(zip.size() <= 10000 && zip.entries().asSequence().sumOf { it.size.coerceAtLeast(0) } <= EpubSafety.MAX_UNPACKED_BYTES) { "Документ превышает предел распаковки (300 МБ / 10 000 файлов)" } }
    private fun chapters(paragraphs: List<String>, title: String): List<TextChapter> {
        val out = mutableListOf<TextChapter>(); var block = mutableListOf<String>(); var size = 0; var total = 0
        for (raw in paragraphs) for (text in clean(raw).chunked(100000)) {
            if (text.isBlank()) continue
            total += text.length; require(total <= MAX_TEXT) { "В документе больше 8 миллионов символов" }
            if (size + text.length > 100000 && block.isNotEmpty()) { out += TextChapter("Часть ${out.size + 1}", block); block = mutableListOf(); size = 0 }
            block += text; size += text.length
        }
        if (block.isNotEmpty()) out += TextChapter(if (out.isEmpty()) title else "Часть ${out.size + 1}", block)
        return out
    }
    fun extract(source: File, displayName: String, pdf: (File) -> TextBook): TextBook {
        require(source.length() > 0) { "Документ пуст" }
        val ext = displayName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        var title = displayName.substringBeforeLast('.'); var author = ""; val paragraphs: List<String>
        if (ext == "pdf") return pdf(source)
        when (ext) {
            "docx" -> ZipFile(source).use { zip ->
                checkZip(zip)
                val doc = xml(entry(zip, "word/document.xml"))
                if (zip.getEntry("docProps/core.xml") != null) {
                    val meta = xml(entry(zip, "docProps/core.xml")).documentElement
                    title = meta.elements("title").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: title
                    author = meta.elements("creator").firstOrNull()?.textContent ?: ""
                }
                paragraphs = doc.documentElement.elements("p").map { p ->
                    val text = StringBuilder()
                    fun visit(node: Node, deleted: Boolean = false) {
                        val name = node.localName; val skip = deleted || name == "del"
                        if (!skip) when (name) { "t" -> { text.append(node.textContent); return }; "tab" -> text.append(' '); "br", "cr" -> text.append('\n') }
                        if (!skip) for (i in 0 until node.childNodes.length) visit(node.childNodes.item(i), skip)
                    }
                    visit(p); text.toString()
                }
                if(paragraphs.all{it.isBlank()} && doc.documentElement.elements("blip").isNotEmpty())throw ScannedDocxException(title,author)
            }
            "fb2", "zip" -> {
                val bytes = if (ext == "zip") ZipFile(source).use { zip -> checkZip(zip); val files = zip.entries().asSequence().filter { it.name.endsWith(".fb2", true) }.toList(); require(files.size == 1) { "ZIP должен содержать ровно одну книгу FB2" }; entry(zip, files.single().name) }
                    else source.inputStream().use { it.readBounded(MAX_XML) }
                val root = xml(bytes).documentElement; require(root.localName == "FictionBook") { "Это не FB2" }
                val info = root.elements("title-info").firstOrNull()
                title = info?.elements("book-title")?.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: title
                author = info?.elements("author")?.joinToString(", ") { a -> children(a).filter { it.localName in listOf("first-name", "middle-name", "last-name") }.joinToString(" ") { it.textContent.trim() } } ?: ""
                paragraphs = children(root).filter { it.localName == "body" }.flatMap { body ->
                    body.getElementsByTagNameNS("*", "*").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.localName in listOf("p", "v", "subtitle") }.map { it.textContent } }
                }
            }
            "txt" -> {
                val bytes = source.inputStream().use { it.readBounded(MAX_XML) }
                val text = if (bytes.size >= 2 && ((bytes[0] == (-1).toByte() && bytes[1] == (-2).toByte()) || (bytes[0] == (-2).toByte() && bytes[1] == (-1).toByte()))) bytes.toString(Charsets.UTF_16)
                    else try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
                    catch (_: java.nio.charset.CharacterCodingException) { bytes.toString(charset("windows-1251")) }
                require(!text.contains('\u0000')) { "Файл не похож на TXT" }
                paragraphs = text.split(Regex("\\r?\\n\\s*\\r?\\n"))
            }
            else -> error("Поддерживаются EPUB, PDF, DOCX, FB2, FB2.ZIP и TXT. Старый DOC сохраните как DOCX.")
        }
        val chapters = chapters(paragraphs, title); require(chapters.isNotEmpty()) { "В документе нет текста для чтения" }
        return TextBook(title, author, if (ext == "zip") "FB2" else ext.uppercase(java.util.Locale.ROOT), chapters)
    }
    fun writeEpub(book: TextBook, hash: String, destination: File) {
        fun esc(s: String) = clean(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        fun path(i: Int) = "Text/chapter-%04d.xhtml".format(java.util.Locale.ROOT, i + 1)
        fun html(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${esc(title)}</title></head><body>$body</body></html>"""
        ZipOutputStream(destination.outputStream()).use { zip ->
            fun put(name: String, text: String, stored: Boolean = false) { val bytes = text.toByteArray(Charsets.UTF_8); val e = ZipEntry(name); if (stored) { e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = e.size; e.crc = CRC32().apply { update(bytes) }.value }; zip.putNextEntry(e); zip.write(bytes); zip.closeEntry() }
            put("mimetype", "application/epub+zip", true)
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("nav.xhtml", html("Содержание", "<nav epub:type=\"toc\"><h1>Содержание</h1><ol>" + book.chapters.mapIndexed { i, c -> "<li><a href=\"${path(i)}\">${esc(c.title)}</a></li>" }.joinToString("") + "</ol></nav>"))
            put("content.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:sha256:$hash</dc:identifier><dc:title>${esc(book.title)}</dc:title><dc:creator>${esc(book.author)}</dc:creator><dc:language>und</dc:language><dc:description>text-import-v1 · ${esc(book.notice)}</dc:description><meta property="dcterms:modified">2026-11-11T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" + book.chapters.mapIndexed { i, _ -> "<item id=\"c$i\" href=\"${path(i)}\" media-type=\"application/xhtml+xml\"/>" }.joinToString("") + "</manifest><spine>" + book.chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" } + "</spine></package>")
            book.chapters.forEachIndexed { i, c -> put(path(i), html(c.title, "<h1>${esc(c.title)}</h1>" + c.paragraphs.joinToString("") { "<p>${esc(it).replace("\n", "<br/>")}</p>" })) }
        }
    }
}
