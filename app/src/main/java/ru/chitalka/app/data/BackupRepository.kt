package ru.chitalka.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportSummary(val wordsAdded: Int, val bookmarksAdded: Int, val reviewsAdded: Int, val missingBooks: Int)

class BackupRepository(private val context: Context, private val database: LibraryDatabase) {
    private val dao = database.dao()

    suspend fun snapshot(): BackupData = database.withTransaction {
        val states = dao.allReadingStates() + dao.allBooks().map { ReadingState(it.hash, it.title, it.author, it.locator, it.progress, it.lastRead) }
        val words = dao.allWords()
        val ids = words.mapTo(hashSetOf()) { it.id }
        BackupData(states.groupBy { it.hash }.values.map { group -> group.maxBy { it.lastRead } },
            words, dao.allBookmarks(), dao.allReviews().filter { it.wordId in ids })
    }

    suspend fun writeJson(uri: Uri) = withContext(Dispatchers.IO) {
        val text = BackupCodec.encode(snapshot())
        require(text.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "Копия превышает допустимые 16 МБ" }
        BackupCodec.decode(text)
        write(uri, text)
    }
    suspend fun writeCsv(uri: Uri) = withContext(Dispatchers.IO) { write(uri, BackupCodec.csv(dao.allWords())) }
    private suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Не удалось записать выбранный файл")
    }

    suspend fun read(uri: Uri): BackupData = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= BackupCodec.MAX_BYTES) { "Копия превышает 16 МБ" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        } ?: error("Не удалось открыть выбранный файл")
        BackupCodec.decode(text)
    }

    suspend fun restore(data: BackupData): ImportSummary = withContext(Dispatchers.IO) {
        // Validate before touching any table; imported data never contains local file paths.
        val validated = BackupCodec.decode(BackupCodec.encode(data))
        val previous = BackupCodec.encode(snapshot())
        require(previous.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "Не удалось создать защитную копию: данных больше 16 МБ" }
        BackupCodec.decode(previous)
        val folder = File(context.filesDir, "backups").apply { mkdirs() }
        val safety = File(folder, "before-import-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        safety.outputStream().use { it.write(previous.toByteArray(Charsets.UTF_8)) }
        val result = database.withTransaction {
            var addedWords = 0; var addedMarks = 0; var addedReviews = 0; var missingBooks = 0
            validated.books.forEach { state ->
                val local = dao.bookByHash(state.hash)
                if (local == null) {
                    missingBooks++
                    val pending = dao.readingState(state.hash)
                    if (pending == null || state.lastRead > pending.lastRead) dao.put(state)
                } else if (state.lastRead > local.lastRead) {
                    dao.progress(local.id, state.locator ?: local.locator ?: "", state.progress, state.lastRead)
                }
            }
            val wordIds = mutableMapOf<String, String>()
            validated.words.forEach { incoming ->
                val existing = dao.wordByMeaning(incoming.normalized, incoming.translation)
                if (existing != null) wordIds[incoming.id] = existing.id
                else {
                    val id = if (dao.wordById(incoming.id) == null) incoming.id else UUID.randomUUID().toString()
                    check(dao.insert(incoming.copy(id = id)) != -1L) { "Не удалось объединить слова" }
                    wordIds[incoming.id] = id
                    addedWords++
                }
            }
            validated.bookmarks.forEach { incoming ->
                val existing = dao.bookmarkByLocation(incoming.bookHash, incoming.locator)
                if (existing == null) {
                    val id = if (dao.bookmarkById(incoming.id) == null) incoming.id else UUID.randomUUID().toString()
                    check(dao.insert(incoming.copy(id = id)) != -1L) { "Не удалось объединить закладки" }
                    addedMarks++
                } else if (maxOf(incoming.updatedAt, incoming.createdAt) > maxOf(existing.updatedAt, existing.createdAt)) {
                    dao.update(existing.copy(quote = incoming.quote, note = incoming.note, kind = incoming.kind, updatedAt = maxOf(incoming.updatedAt, incoming.createdAt)))
                }
            }
            validated.reviews.forEach { event ->
                val wordId = wordIds.getValue(event.wordId)
                if (dao.reviewCount(wordId, event.rating, event.createdAt) == 0) {
                    dao.insert(event.copy(id = 0, wordId = wordId)); addedReviews++
                }
            }
            ImportSummary(addedWords, addedMarks, addedReviews, missingBooks)
        }
        // Keep the last three safety copies; export them through the backup screen if needed.
        folder.listFiles()?.filter { it.name.startsWith("before-import-") }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        result
    }

    suspend fun writeSafetyCopy(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val latest = File(context.filesDir, "backups").listFiles()?.filter { it.extension == "json" }?.maxByOrNull { it.lastModified() }
            ?: return@withContext false
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> latest.inputStream().use { it.copyTo(out) } } ?: error("Не удалось записать файл")
        true
    }
}
