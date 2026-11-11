package ru.chitalka.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WindowsBackupCompatibilityTest {
    @Test fun windowsMvpExportIsAcceptedWithoutLosingNotesContextsOrLocators() {
        val text = javaClass.getResourceAsStream("/windows-mvp-backup.json")!!.bufferedReader().use { it.readText() }
        val data = BackupCodec.decode(text)
        assertEquals(2, data.words.size)
        assertTrue(data.bookmarks.any { it.note.contains("Windows") })
        assertTrue(data.bookmarks.any { it.quote.length > 100 })
        assertEquals(1, data.reviews.size)
        val locator = JSONObject(data.words.first().locator)
        assertTrue(locator.getString("href").endsWith(".xhtml"))
        assertTrue(locator.getJSONObject("locations").getString("cfi").startsWith("epubcfi("))
        assertFalse(locator.getJSONObject("text").getString("highlight").isBlank())
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
    }
}
