package ru.chitalka.app.data

import org.json.JSONArray
import org.json.JSONObject

data class BackupData(val books: List<ReadingState>, val words: List<Word>, val bookmarks: List<Bookmark>, val reviews: List<ReviewEvent>)

object BackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val FORMAT = "ru.chitalka.app.backup"

    fun encode(data: BackupData): String = JSONObject().apply {
        put("format", FORMAT); put("version", 1); put("createdAt", System.currentTimeMillis())
        put("books", JSONArray().apply { data.books.forEach { b -> put(JSONObject().apply {
            put("hash", b.hash); put("title", b.title); put("author", b.author); put("locator", b.locator ?: JSONObject.NULL)
            put("progress", b.progress); put("lastRead", b.lastRead)
        }) } })
        put("words", JSONArray().apply { data.words.forEach { w -> put(JSONObject().apply {
            put("id", w.id); put("original", w.original); put("normalized", w.normalized); put("translation", w.translation)
            put("context", w.context); put("bookHash", w.bookHash); put("bookTitle", w.bookTitle); put("locator", w.locator)
            put("source", w.source); put("createdAt", w.createdAt)
        }) } })
        put("bookmarks", JSONArray().apply { data.bookmarks.forEach { b -> put(JSONObject().apply {
            put("id", b.id); put("bookHash", b.bookHash); put("bookTitle", b.bookTitle); put("author", b.author)
            put("locator", b.locator); put("quote", b.quote); put("note", b.note); put("createdAt", b.createdAt); put("updatedAt", b.updatedAt)
        }) } })
        put("reviews", JSONArray().apply { data.reviews.forEach { r -> put(JSONObject().apply {
            put("wordId", r.wordId); put("rating", r.rating); put("createdAt", r.createdAt)
        }) } })
    }.toString(2)

    fun decode(text: String): BackupData {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Копия превышает 16 МБ" }
        try {
            val root = JSONObject(text.removePrefix("\uFEFF"))
            require(root.optString("format") == FORMAT) { "Это не резервная копия Читалки" }
            require(root.long("version") == 1L) { "Эта версия резервной копии не поддерживается" }
            val books = root.getJSONArray("books")
            val words = root.getJSONArray("words")
            val marks = root.getJSONArray("bookmarks")
            val reviews = root.getJSONArray("reviews")
            require(listOf(books, words, marks, reviews).sumOf { it.length().toLong() } <= 100000) { "В копии слишком много записей" }
            fun <T> JSONArray.read(block: (JSONObject) -> T) = (0 until length()).map { block(getJSONObject(it)) }
            val bookList = books.read { b -> ReadingState(b.hash("hash"), b.text("title", 2000), b.text("author", 2000),
                b.locator("locator", nullable = true), b.progress(), b.long("lastRead")) }
            val wordList = words.read { w -> Word(w.text("id", 128, false), w.text("original", 2000, false), w.text("normalized", 2000, false),
                w.text("translation", 20000, false), w.text("context", 20000), w.hash("bookHash"), w.text("bookTitle", 2000),
                w.locator("locator")!!, w.text("source", 2000), w.long("createdAt")) }
            val markList = marks.read { b -> Bookmark(b.text("id", 128, false), b.hash("bookHash"), b.text("bookTitle", 2000), b.text("author", 2000),
                b.locator("locator")!!, b.text("quote", 100000, false), b.text("note", 20000), b.long("createdAt"), b.long("updatedAt")) }
            val reviewList = reviews.read { r ->
                val rating = r.long("rating")
                require(rating in 0..2) { "Некорректная оценка повторения" }
                ReviewEvent(wordId = r.text("wordId", 128, false), rating = rating.toInt(), createdAt = r.long("createdAt"))
            }
            require(wordList.map { it.id }.distinct().size == wordList.size) { "Повторяющиеся идентификаторы слов" }
            require(markList.map { it.id }.distinct().size == markList.size) { "Повторяющиеся идентификаторы закладок" }
            require(bookList.map { it.hash }.distinct().size == bookList.size) { "Повторяющиеся книги" }
            val ids = wordList.mapTo(hashSetOf()) { it.id }
            require(reviewList.all { it.wordId in ids }) { "История повторений ссылается на отсутствующее слово" }
            return BackupData(bookList, wordList, markList, reviewList)
        } catch (e: IllegalArgumentException) { throw e }
        catch (_: Exception) { throw IllegalArgumentException("Копия повреждена или имеет неверную структуру") }
    }

    private fun JSONObject.text(key: String, max: Int, blank: Boolean = true): String {
        val value = get(key)
        // Existing notes and publication metadata predate UI limits. The whole file is bounded;
        // do not create backups that silently reject a user's long existing text on restore.
        val limit = if (key == "id" || key == "wordId" || key == "hash" || key == "bookHash") max else MAX_BYTES
        require(value is String && value.length <= limit && (blank || value.isNotBlank())) { "Некорректное поле $key" }
        return value
    }
    private fun JSONObject.long(key: String): Long {
        val raw = get(key)
        require(raw is Number) { "Некорректное число $key" }
        val value = raw.toString().toLongOrNull()
        require(value != null && value >= 0) { "Некорректное число $key" }
        return value
    }
    private fun JSONObject.hash(key: String): String = text(key, 64).also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Некорректный хеш книги" }
    }
    private fun JSONObject.progress(): Double {
        val raw = get("progress")
        require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() in 0.0..1.0) { "Некорректный прогресс книги" }
        return raw.toDouble()
    }
    private fun JSONObject.locator(key: String, nullable: Boolean = false): String? {
        if (nullable && isNull(key)) return null
        val value = text(key, 200000, false)
        require(JSONObject(value).optString("href").isNotBlank()) { "Некорректное место в книге" }
        return value
    }

    fun csv(words: List<Word>): String {
        fun cell(value: String): String {
            val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        val rows = listOf(listOf("word", "translation", "context", "book", "source", "addedAt")) + words.map {
            listOf(it.original, it.translation, it.context, it.bookTitle, it.source, it.createdAt.toString())
        }
        return "\uFEFF" + rows.joinToString("\r\n", postfix = "\r\n") { it.joinToString(",", transform = ::cell) }
    }
}
