package ru.chitalka.app

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupDataTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val locator = """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.2},"text":{"highlight":"curious"}}"""

    @Test fun mergeIsIdempotentPreservesNewerNotesAndRestoresReimportedProgress() = runBlocking {
        val folder = File(context.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = folder }
        val source = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val file = File(folder, "fixture.epub")
            context.assets.open("sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            source.dao().insert(Book("book", hash, "The Quiet Garden", "Автор", "/private/not-exported.epub", locator = locator, progress = .7, lastRead = 500))
            source.dao().insert(Word("source-word", "curious", "curious", "любопытный", "A curious word", hash, "The Quiet Garden", locator, createdAt = 100))
            source.dao().insert(Bookmark("source-mark", hash, "The Quiet Garden", "Автор", locator, "Цитата", "Старая заметка", 100, 200))
            source.dao().insert(ReviewEvent(wordId = "source-word", rating = 2, createdAt = 300))
            val exported = File(folder, "backup.json")
            BackupRepository(isolated, source).writeJson(Uri.fromFile(exported))
            assertFalse(exported.readText().contains("/private/not-exported"))
            val repo = LibraryRepository(isolated, target)
            val data = repo.backups.read(Uri.fromFile(exported))
            target.dao().insert(data.words.single().copy(id = "existing-word"))
            target.dao().insert(data.bookmarks.single().copy(id = "existing-mark", note = "Новая заметка", updatedAt = 400))
            val result = repo.backups.restore(data)
            assertEquals(0, result.wordsAdded)
            assertEquals(1, result.reviewsAdded)
            assertEquals(1, result.missingBooks)
            assertEquals("Новая заметка", target.dao().allBookmarks().single().note)
            assertEquals("existing-word", target.dao().allReviews().single().wordId)
            val repeated = repo.backups.restore(data)
            assertEquals(0, repeated.reviewsAdded)
            assertEquals(1, target.dao().allWords().size)
            assertEquals(1, target.dao().allReviews().size)
            val safety = File(folder, "safety.json")
            assertTrue(repo.backups.writeSafetyCopy(Uri.fromFile(safety)))
            assertEquals(1, BackupCodec.decode(safety.readText()).words.size)
            val imported = repo.import(Uri.fromFile(file))
            assertEquals(.7, imported.progress, .0001)
            assertEquals(locator, imported.locator)
            val again = repo.importWithResult(Uri.fromFile(file))
            assertTrue(again.duplicate)
            assertEquals(imported.id, again.book.id)
        } finally { source.close(); target.close(); folder.deleteRecursively() }
    }

    @Test fun rejectsInvalidInputWithoutChangingDatabase() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val hash = "b".repeat(64)
            val word = Word("keep", "keep", "keep", "сохранять", "Keep it", hash, "Книга", locator)
            db.dao().insert(word)
            val invalid = BackupData(emptyList(), emptyList(), emptyList(), listOf(ReviewEvent(wordId = "missing", rating = 2)))
            try { BackupRepository(context, db).restore(invalid); fail("Invalid backup accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(word), db.dao().allWords())
        } finally { db.close() }
    }

    @Test fun restoresIntoEmptyDatabaseWithoutCreatingGhostBooks() = runBlocking {
        val folder = File(context.cacheDir, "fresh-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = folder }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val hash = "e".repeat(64)
            val data = BackupData(listOf(ReadingState(hash, "Книга", "Автор", locator, .4, 500)),
                listOf(Word("fresh", "light", "light", "свет", "A light", hash, "Книга", locator, createdAt = 100)),
                listOf(Bookmark("mark", hash, "Книга", "Автор", locator, "Цитата", "Заметка", 100, 200)),
                listOf(ReviewEvent(wordId = "fresh", rating = 1, createdAt = 300)))
            val backup = BackupRepository(isolated, db)
            assertEquals(ImportSummary(1, 1, 1, 1), backup.restore(data))
            assertTrue(db.dao().allBooks().isEmpty())
            assertEquals(data, BackupCodec.decode(BackupCodec.encode(backup.snapshot())))
        } finally { db.close(); folder.deleteRecursively() }
    }

    @Test fun databaseFailureRollsBackAllImportedRecords() = runBlocking {
        val folder = File(context.cacheDir, "rollback-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = folder }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val hash = "f".repeat(64)
            val keep = Word("keep", "keep", "keep", "хранить", "Keep", hash, "Книга", locator)
            db.dao().insert(keep)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER test_reject BEFORE INSERT ON Bookmark BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
            val data = BackupData(emptyList(), listOf(keep.copy(id = "new", original = "new", normalized = "new", translation = "новый")),
                listOf(Bookmark("newmark", hash, "Книга", "Автор", locator, "Цитата")), emptyList())
            try { BackupRepository(isolated, db).restore(data); fail("Expected database rejection") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(listOf(keep), db.dao().allWords())
            assertTrue(db.dao().allBookmarks().isEmpty())
        } finally { db.close(); folder.deleteRecursively() }
    }

    @Test fun migratesVersionsOneAndThreeWithoutLosingVocabularyBookmarksOrHistory() = runBlocking {
        for (version in listOf(1, 3)) {
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = JSONObject(instrument.context.assets.open("schema-v$version.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            val hash = "c".repeat(64)
            sqlite.execSQL("INSERT INTO Book VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>("legacy-book", hash, "Book", "Author", "legacy.epub", null, locator, .6, 500))
            sqlite.execSQL("INSERT INTO Word VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>("legacy", "old", "old", "старый", "old text", hash, "Book", locator, "ML Kit", 100))
            sqlite.execSQL("INSERT INTO Bookmark(id,bookHash,bookTitle,author,locator,quote,note,createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>("mark", hash, "Book", "Author", locator, "Quote", "Note", 200))
            sqlite.execSQL("INSERT INTO ReviewEvent(wordId,rating,createdAt) VALUES('legacy',2,300)")
            if (version == 3) sqlite.execSQL("INSERT INTO PracticeSession(id,wordIds,ratings,revealedFor) VALUES(1,'[\"legacy\"]','{}','legacy')")
            sqlite.version = version
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            assertEquals("старый", db.dao().allWords().single().translation)
            assertEquals("Note", db.dao().allBookmarks().single().note)
            assertEquals(0L, db.dao().allBookmarks().single().updatedAt)
            assertEquals("QUOTE", db.dao().allBookmarks().single().kind)
            if (version == 3) assertEquals("legacy", db.dao().currentPractice()?.revealedFor)
            assertEquals("legacy", db.dao().allReviews().single().wordId)
            assertEquals(.6, db.dao().allBooks().single().progress, .0001)
            assertEquals(locator, db.dao().allBooks().single().locator)
            assertTrue(db.dao().allReadingStates().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
