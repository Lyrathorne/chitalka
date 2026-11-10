package ru.chitalka.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val hash = "a".repeat(64)
    private val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml","text":{"highlight":"curious"}}"""
    private fun fixture() = BackupData(
        listOf(ReadingState(hash, "Книга, с цитатой", "Автор", locator, .42, 100)),
        listOf(Word("w", "curious", "curious", "любопытный", "A \"curious\" word.\nNext line.", hash, "Книга", locator, createdAt = 10)),
        listOf(Bookmark("b", hash, "Книга", "Автор", locator, "Цитата", "Личная заметка", 20, 30)),
        listOf(ReviewEvent(wordId = "w", rating = 2, createdAt = 40)))

    @Test fun roundTripPreservesTextsNotesProgressAndReviews() {
        val input = fixture()
        assertEquals(input, BackupCodec.decode(BackupCodec.encode(input)))
    }
    @Test fun pageBookmarksRoundTripAndOldCopiesRemainCompatible() {
        val data = fixture().let { it.copy(bookmarks = listOf(it.bookmarks.single().copy(kind = "BOOKMARK"))) }
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
        val old = JSONObject(BackupCodec.encode(fixture()))
        old.getJSONArray("bookmarks").getJSONObject(0).remove("kind")
        assertEquals("QUOTE", BackupCodec.decode(old.toString()).bookmarks.single().kind)
        old.getJSONArray("bookmarks").getJSONObject(0).put("kind", "INVALID")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(old.toString()) }
    }
    @Test fun rejectsUnsupportedVersion() {
        val root = JSONObject(BackupCodec.encode(fixture())).put("version", 999)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }
    @Test fun longExistingNoteCanBeExportedAndRestored() {
        val data = fixture().let { it.copy(bookmarks = listOf(it.bookmarks.single().copy(note = "я".repeat(25000)))) }
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
    }
    @Test fun rejectsDanglingReviewReference() {
        val root = JSONObject(BackupCodec.encode(fixture()))
        root.getJSONArray("reviews").getJSONObject(0).put("wordId", "missing")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }
    @Test fun rejectsInvalidProgressAndCoercedNumbers() {
        val root = JSONObject(BackupCodec.encode(fixture()))
        root.getJSONArray("books").getJSONObject(0).put("progress", 1.5)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
        val other = JSONObject(BackupCodec.encode(fixture())).put("version", "1")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(other.toString()) }
    }
    @Test fun rejectsCorruptAndOversizedFiles() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("x".repeat(BackupCodec.MAX_BYTES + 1)) }
    }
    @Test fun csvEscapesQuotesAndProtectsSpreadsheetFormulas() {
        val word = fixture().words.single().copy(translation = "=HYPERLINK(\"bad\")")
        val csv = BackupCodec.csv(listOf(word))
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\""))
        assertTrue(csv.contains("A \"\"curious\"\" word.\nNext line."))
        assertTrue(csv.endsWith("\r\n"))
    }
}
