package ru.chitalka.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MomentDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun draft() = Bookmark(UUID.randomUUID().toString(), "f".repeat(64), "A Book", "An Author",
        """{"href":"chapter.xhtml","text":{"highlight":"First paragraph. Second paragraph."}}""", "First paragraph.\n\nSecond paragraph.")

    @Test fun repeatedQuoteAndLaterNoteKeepOneRangeWithoutErasingNote() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repo = MomentRepository(db); val source = draft()
            val results = coroutineScope { (1..3).map { async(Dispatchers.IO) { repo.quote(source.copy(id = UUID.randomUUID().toString())) } }.awaitAll() }
            assertEquals(1, results.count { it.created })
            val first = db.dao().allBookmarks().single()
            val annotated = repo.note(source, "My thought")
            assertEquals(first.id, annotated.bookmark.id)
            assertFalse(repo.quote(source).created)
            val saved = db.dao().allBookmarks().single()
            assertEquals("My thought", saved.note); assertEquals(source.quote, saved.quote)
            assertEquals(first.createdAt, saved.createdAt); assertTrue(saved.updatedAt >= first.createdAt)
            assertTrue(db.dao().allWords().isEmpty())
            val decoded = BackupCodec.decode(BackupCodec.encode(BackupData(emptyList(), emptyList(), listOf(saved), emptyList())))
            assertEquals(saved.quote, decoded.bookmarks.single().quote)
            assertEquals(saved.note, decoded.bookmarks.single().note)
        } finally { db.close() }
    }

    @Test fun newNoteRequiresTextAndCreatesQuoteOnce() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repo = MomentRepository(db); val source = draft()
            assertTrue(runCatching { repo.note(source, "   ") }.isFailure)
            assertTrue(db.dao().allBookmarks().isEmpty())
            assertTrue(repo.note(source, "A note").created)
            assertFalse(repo.quote(source).created)
            repo.note(source, "")
            assertEquals("", db.dao().allBookmarks().single().note)
            assertEquals(source.quote, db.dao().allBookmarks().single().quote)
        } finally { db.close() }
    }

    @Test fun identicalTextAtDifferentLocationsRemainsTwoQuotes() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repo = MomentRepository(db); val source = draft()
            repo.quote(source)
            repo.quote(source.copy(id = UUID.randomUUID().toString(), locator = source.locator.replace("chapter.xhtml", "chapter-two.xhtml")))
            assertEquals(2, db.dao().allBookmarks().size)
        } finally { db.close() }
    }
    @Test fun undoDoesNotEraseNoteAddedAfterQuoteWasSaved() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repo = MomentRepository(db); val source = draft()
            val first = repo.quote(source)
            assertTrue(repo.undoQuote(first.bookmark)); assertTrue(db.dao().allBookmarks().isEmpty())
            val second = repo.quote(source)
            repo.note(source, "Keep this thought")
            assertFalse(repo.undoQuote(second.bookmark))
            assertEquals("Keep this thought", db.dao().allBookmarks().single().note)
        } finally { db.close() }
    }
}
