package ru.chitalka.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class BookImportResult(val book: Book, val duplicate: Boolean, val notice: String? = null)

class LibraryRepository(private val context: Context,
    val database: LibraryDatabase = Room.databaseBuilder(context, LibraryDatabase::class.java, "chitalka.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()) {
    val dao = database.dao()
    private val importLock = Mutex()
    val backups by lazy { BackupRepository(context, database) }
    val practice by lazy { PracticeRepository(database) }
    val moments by lazy { MomentRepository(database) }
    // EPUBs are local. Their remote resources must not make requests on behalf of the user.
    private val http = object : HttpClient {
        override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
            Try.failure(HttpError.IO(java.io.IOException("Внешние ресурсы EPUB отключены")))
    }
    private val retriever = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null))

    suspend fun open(file: File): Publication = withContext(Dispatchers.IO) {
        EpubSafety.validate(file)
        val asset = retriever.retrieve(file.toUrl()).getOrElse { error("Не удалось прочитать файл: $it") }
        val publication = opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            // Pass this per-open: Readium 3.1.2 does not reliably apply the constructor callback.
            container = TransformingContainer(container) { url, resource ->
                val path = url.toString().substringBefore('?').substringBefore('#').lowercase()
                if (listOf(".xhtml", ".html", ".htm", ".svg").any(path::endsWith)) resource.map { Try.success(safeEpubMarkup(it)) }
                else resource
            }
        }).getOrElse { error("Не удалось открыть EPUB: $it") }
        if (!publication.conformsTo(Publication.Profile.EPUB) || publication.isRestricted) {
            publication.close()
            error("Поддерживаются только EPUB без DRM")
        }
        publication
    }

    suspend fun import(uri: Uri): Book = importWithResult(uri).book

    suspend fun importWithResult(uri: Uri): BookImportResult = importLock.withLock {
        withContext(Dispatchers.IO) {
            val folder = File(context.filesDir, "books").apply { mkdirs() }
            val temp = File.createTempFile("import-", ".epub", folder)
            var destination: File? = null
            var coverFile: File? = null
            var converted: File? = null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Нет доступа к выбранному файлу")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                dao.bookByHash(hash)?.let { return@withContext BookImportResult(it, duplicate = true) }
                val displayName = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } }.getOrNull()
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Книга.epub"
                var readable = temp
                var notice: String? = null
                if (!displayName.endsWith(".epub", true)) {
                    val text = DocumentImporter.extract(temp, displayName) { source -> extractPdfText(context, source, displayName.substringBeforeLast('.')) }
                    notice = text.notice
                    readable = File.createTempFile("converted-", ".epub", folder).also { converted = it; DocumentImporter.writeEpub(text, hash, it) }
                }
                val publication = open(readable)
                try {
                    val id = UUID.randomUUID().toString()
                    destination = File(folder, "$id.epub")
                    val cover = publication.cover()?.let { bitmap ->
                        File(folder, "$id.jpg").also { file ->
                            coverFile = file
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        }.absolutePath
                    }
                    check(readable.renameTo(destination!!)) { "Не удалось сохранить книгу" }
                    val restored = dao.readingState(hash)
                    val book = Book(id, hash, publication.metadata.title ?: "Без названия",
                        publication.metadata.authors.joinToString { it.name.toString() }, destination!!.absolutePath, cover)
                        .copy(locator = restored?.locator, progress = restored?.progress ?: 0.0, lastRead = restored?.lastRead ?: 0)
                    check(dao.insert(book) != -1L) { "Книга уже добавлена" }
                    destination = null
                    coverFile = null
                    BookImportResult(book, duplicate = false, notice = notice)
                } finally { publication.close() }
            } finally {
                temp.delete()
                converted?.delete()
                destination?.delete()
                coverFile?.delete()
            }
        }
    }

    suspend fun remove(book: Book) = withContext(Dispatchers.IO) {
        dao.put(ReadingState(book.hash, book.title, book.author, book.locator, book.progress, book.lastRead))
        dao.delete(book)
        File(book.path).delete()
        book.cover?.let { File(it).delete() }
    }
}
