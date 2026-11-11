package ru.chitalka.app

import org.junit.Assert.*
import org.junit.Test
import ru.chitalka.app.data.DocumentImporter
import java.io.File
import java.util.zip.ZipFile

class DocumentImporterTest {
    private fun fixture(name: String) = File.createTempFile("format-", ".tmp").apply {
        DocumentImporterTest::class.java.getResourceAsStream("/$name")!!.use { input -> outputStream().use { input.copyTo(it) } }
    }
    @Test fun importsTextMetadataAndStableEpubPaths() {
        for (name in listOf("sample.txt", "unicode.txt", "sample.docx", "sample.fb2", "sample.fb2.zip")) {
            val source = fixture(name)
            val target = File.createTempFile("converted-", ".epub")
            try {
                val book = DocumentImporter.extract(source, name) { error("Unexpected PDF") }
                assertTrue(book.chapters.flatMap { it.paragraphs }.any { it.contains("curious") || it.contains("Любопытный") })
                if (name.endsWith("docx")) { assertEquals("Format sample", book.title); assertEquals("QA Writer", book.author); assertTrue(book.chapters.flatMap { it.paragraphs }.any { it.contains("Table cell") }) }
                DocumentImporter.writeEpub(book, "a".repeat(64), target)
                ZipFile(target).use { zip ->
                    assertNotNull(zip.getEntry("Text/chapter-0001.xhtml"))
                    assertEquals("application/epub+zip", zip.getInputStream(zip.getEntry("mimetype")).reader().readText())
                }
            } finally { source.delete(); target.delete() }
        }
    }
    @Test fun rejectsUnsafeAndBrokenDocuments() {
        for (name in listOf("xxe.fb2", "broken.docx")) {
            val file = fixture(name)
            try { assertTrue(runCatching { DocumentImporter.extract(file, name) { error("Unexpected PDF") } }.isFailure) }
            finally { file.delete() }
        }
    }
}
