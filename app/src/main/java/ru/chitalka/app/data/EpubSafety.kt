package ru.chitalka.app.data

import java.io.File
import java.util.zip.ZipFile

object EpubSafety {
    const val MAX_UNPACKED_BYTES = 300L * 1024 * 1024
    const val MAX_CHAPTER_BYTES = 8L * 1024 * 1024

    fun validate(file: File) {
        require(file.length() > 0) { "Книга пустая" }
        ZipFile(file).use { zip ->
            require(zip.size() <= 10000) { "В книге слишком много файлов" }
            require(zip.getEntry("META-INF/container.xml") != null) { "Это не EPUB: отсутствует описание книги" }
            val mime = zip.getEntry("mimetype") ?: error("Это не EPUB: отсутствует mimetype")
            require(mime.size in 1..64) { "Некорректный mimetype" }
            require(zip.getInputStream(mime).use { input ->
                val bytes = ByteArray(64)
                var size = 0
                while (size < bytes.size) {
                    val read = input.read(bytes, size, bytes.size - size)
                    if (read < 0) break
                    size += read
                }
                String(bytes, 0, size, Charsets.US_ASCII).trim()
            } == "application/epub+zip") { "Неподдерживаемый формат" }
            var total = 0L
            val buffer = ByteArray(32768)
            for (entry in zip.entries()) {
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && !name.contains(':') && name.split('/').none { it == ".." }) { "Небезопасный путь внутри книги" }
                if (entry.isDirectory) continue
                val chapter = listOf(".xhtml", ".html", ".htm", ".svg").any { name.lowercase().endsWith(it) }
                if (chapter) require(entry.size <= MAX_CHAPTER_BYTES) { "Глава превышает 8 МБ: разделите слишком длинный EPUB" }
                require(entry.size <= MAX_UNPACKED_BYTES) { "Слишком большой файл внутри книги" }
                zip.getInputStream(entry).use { input ->
                    var entryBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        entryBytes += count
                        if (chapter) require(entryBytes <= MAX_CHAPTER_BYTES) { "Глава превышает 8 МБ" }
                        require(total <= MAX_UNPACKED_BYTES) { "Распакованная книга превышает 300 МБ" }
                    }
                }
            }
        }
    }
}

fun normalizeWord(text: String): String = text.trim()
    .trim { !it.isLetterOrDigit() }
    .replace('’', '\'')
    .lowercase(java.util.Locale.ROOT)
