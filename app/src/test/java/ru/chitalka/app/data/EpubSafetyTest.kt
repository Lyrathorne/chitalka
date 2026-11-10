package ru.chitalka.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubSafetyTest {
    private fun epub(extra: String? = null, mime: String = "application/epub+zip"): File {
        val file = File.createTempFile("chitalka-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            add("mimetype", mime)
            add("META-INF/container.xml", "<container/>")
            if (extra != null) add(extra, "test")
        }
        return file
    }
    @Test fun acceptsBoundedEpub() { val f = epub("chapter.xhtml"); try { EpubSafety.validate(f) } finally { f.delete() } }
    @Test fun rejectsTraversal() { val f = epub("../outside"); try { assertThrows(IllegalArgumentException::class.java) { EpubSafety.validate(f) } } finally { f.delete() } }
    @Test fun rejectsWindowsTraversal() { val f = epub("..\\outside"); try { assertThrows(IllegalArgumentException::class.java) { EpubSafety.validate(f) } } finally { f.delete() } }
    @Test fun rejectsOtherZip() { val f = epub(mime = "application/zip"); try { assertThrows(IllegalArgumentException::class.java) { EpubSafety.validate(f) } } finally { f.delete() } }
    @Test fun normalizesWithoutDestroyingWordForms() {
        assertEquals("don't", normalizeWord(" “Don’t!” "))
        assertEquals("well-known", normalizeWord("well-known."))
        assertEquals("running", normalizeWord("Running"))
    }
}
