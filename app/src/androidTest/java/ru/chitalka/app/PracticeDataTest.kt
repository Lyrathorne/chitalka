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
class PracticeDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun word(id: String, time: Long = 100) = Word(id, id, id, "перевод $id", "Context $id", "a".repeat(64), "Book",
        """{"href":"chapter.xhtml","type":"application/xhtml+xml"}""", createdAt = time)

    @Test fun sessionAndRevealSurviveDatabaseReopenAndDuplicateAnswersAreIgnored() = runBlocking<Unit> {
        val name = "practice-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        var db = open()
        try {
            db.dao().insert(word("first", 200)); db.dao().insert(word("second"))
            var repo = PracticeRepository(db)
            repo.start()
            assertFalse(repo.rate("first", 2))
            repo.reveal("first")
            db.close(); db = open(); repo = PracticeRepository(db)
            assertTrue(practiceProgress(db.dao().currentPractice(), db.dao().allWords()).revealed)
            val outcomes = coroutineScope { (1..3).map { async(Dispatchers.IO) { repo.rate("first", 2) } }.awaitAll() }
            assertEquals(1, outcomes.count { it })
            assertEquals(1, db.dao().allReviews().size)
            db.close(); db = open()
            val progress = practiceProgress(db.dao().currentPractice(), db.dao().allWords())
            assertEquals("second", progress.current?.id); assertFalse(progress.revealed)
            assertEquals(listOf(0, 0, 1), progress.counts)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun failedAdvanceRollsBackAnswerHistory() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            db.dao().insert(word("first"))
            val repo = PracticeRepository(db); repo.start(); repo.reveal("first")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_practice BEFORE INSERT ON PracticeSession WHEN NEW.ratings != '{}' BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
            assertTrue(runCatching { repo.rate("first", 1) }.isFailure)
            assertEquals(0, db.dao().allReviews().size)
            assertTrue(practiceProgress(db.dao().currentPractice(), db.dao().allWords()).revealed)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_practice")
            assertTrue(repo.rate("first", 1))
        } finally { db.close() }
    }

    @Test fun deletionIsSkippedNewWordsWaitForNextSessionAndSummaryKeepsRatings() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val first = word("first", 200); val second = word("second")
            db.dao().insert(first); db.dao().insert(second)
            val repo = PracticeRepository(db); repo.start()
            db.dao().delete(first); db.dao().insert(word("new", 300))
            var state = practiceProgress(db.dao().currentPractice(), db.dao().allWords())
            assertEquals("second", state.current?.id); assertEquals(1, state.missing)
            repo.reveal("second"); assertTrue(repo.rate("second", 0))
            state = practiceProgress(db.dao().currentPractice(), db.dao().allWords())
            assertTrue(state.finished); assertEquals(1, state.answered); assertEquals(listOf(1, 0, 0), state.counts)
            repo.start()
            assertEquals("new", practiceProgress(db.dao().currentPractice(), db.dao().allWords()).current?.id)
        } finally { db.close() }
    }

    @Test fun emptyDictionaryDoesNotStartSession() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try { PracticeRepository(db).start(); assertNull(db.dao().currentPractice()) }
        finally { db.close() }
    }
}
