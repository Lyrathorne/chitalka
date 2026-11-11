package ru.chitalka.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class DictionaryMeaning(val headword: String, val partOfSpeech: String, val translation: String, val definition: String)

/** Separate, immutable package. No access to the user's Room database. */
class LocalDictionary(private val context: Context) {
    private val lock = Mutex()
    private var database: SQLiteDatabase? = null
    val source = "FreeDict / WikDict • CC BY-SA 3.0"

    suspend fun lookup(text: String): List<DictionaryMeaning> = withContext(Dispatchers.IO) {
        val key = dictionaryKey(text)
        if (key.isBlank() || key.length > 100 || key.any { it.isWhitespace() }) return@withContext emptyList()
        val db = database()
        val meanings = mutableListOf<DictionaryMeaning>()
        for (candidate in dictionaryCandidates(key)) {
            db.rawQuery("SELECT headword,pos,translation,definition FROM entry WHERE normalized = ? ORDER BY id LIMIT 80", arrayOf(candidate)).use { rows ->
                while (rows.moveToNext()) {
                    val pos = rows.getString(1)
                    // Derived regular forms must match a suitable part of speech.
                    if (candidate != key && candidate !in irregularBases(key) && pos !in listOf("v", "n", "adj")) continue
                    meanings += DictionaryMeaning(rows.getString(0), pos, rows.getString(2), rows.getString(3))
                }
            }
            // Exact dictionary entries take precedence. Inflection candidates are not a claim of contextual disambiguation.
            if (meanings.isNotEmpty()) break
        }
        meanings.distinctBy { Triple(it.headword, it.partOfSpeech, it.translation) }
    }

    private suspend fun database(): SQLiteDatabase = lock.withLock {
        database?.let { return@withLock it }
        val manifest = context.assets.open("dictionary/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val folder = File(context.filesDir, "dictionaries").apply { check(mkdirs() || isDirectory) }
        val file = File(folder, "${manifest.getString("version")}.sqlite")
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        val expected = manifest.getString("sha256")
        if (!file.isFile || file.length() != manifest.getLong("bytes") || digest(file) != expected) {
            val temp = File.createTempFile("dictionary-", ".part", folder)
            try {
                // .bin prevents AAPT from transparently decompressing and renaming a .gz asset.
                GZIPInputStream(context.assets.open("dictionary/eng-rus.sqlite.gz.bin")).use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(32768); var total = 0L
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                        check(total <= manifest.getLong("bytes")) { "Повреждён словарный пакет" }; output.write(buffer, 0, count) }
                } }
                check(temp.length() == manifest.getLong("bytes") && digest(temp) == expected) { "Повреждён словарный пакет" }
                check(temp.renameTo(file)) { "Не удалось установить словарь. Освободите место." }
            } finally { temp.delete() }
        }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also { db ->
            check(db.version == 1) { "Неподдерживаемый словарный пакет" }
            database = db
        }
    }
}

fun dictionaryKey(text: String): String = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFC)
    .trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }
    .replace('’', '\'').replace('‐', '-').replace('‑', '-').lowercase(java.util.Locale.ROOT)

// Authored lookup hints, not dictionary definitions; exact entries always win.
fun irregularBases(word: String): List<String> = mapOf(
    "am" to "be", "is" to "be", "are" to "be", "was" to "be", "were" to "be", "been" to "be",
    "has" to "have", "had" to "have", "did" to "do", "done" to "do", "went" to "go", "gone" to "go",
    "said" to "say", "told" to "tell", "made" to "make", "took" to "take", "taken" to "take",
    "came" to "come", "seen" to "see", "knew" to "know", "known" to "know", "got" to "get", "gotten" to "get",
    "gave" to "give", "given" to "give", "found" to "find", "thought" to "think", "bought" to "buy",
    "brought" to "bring", "felt" to "feel", "left" to "leave", "heard" to "hear", "held" to "hold",
    "kept" to "keep", "let" to "let", "lost" to "lose", "met" to "meet", "paid" to "pay", "ran" to "run",
    "sat" to "sit", "stood" to "stand", "spoke" to "speak", "spoken" to "speak", "wrote" to "write", "written" to "write",
    "ate" to "eat", "eaten" to "eat", "drank" to "drink", "drunk" to "drink", "slept" to "sleep",
    "began" to "begin", "begun" to "begin", "broke" to "break", "broken" to "break", "chose" to "choose", "chosen" to "choose",
    "drove" to "drive", "driven" to "drive", "fell" to "fall", "fallen" to "fall", "flew" to "fly", "flown" to "fly",
    "forgot" to "forget", "forgotten" to "forget", "grew" to "grow", "grown" to "grow", "sent" to "send", "spent" to "spend",
    "children" to "child", "men" to "man", "women" to "woman", "mice" to "mouse", "feet" to "foot", "teeth" to "tooth",
    "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad"
)[word]?.let(::listOf) ?: emptyList()

fun dictionaryCandidates(text: String): List<String> {
    val word = dictionaryKey(text)
    val candidates = linkedSetOf(word)
    candidates.addAll(irregularBases(word))
    if (word.endsWith("'s")) candidates.add(word.dropLast(2))
    if (word.length > 4 && word.endsWith("ies")) candidates.add(word.dropLast(3) + "y")
    if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) { candidates.add(word.dropLast(1)); if (word.endsWith("es")) candidates.add(word.dropLast(2)) }
    for (suffix in listOf("ing", "ed")) if (word.endsWith(suffix) && word.length > suffix.length + 2) {
        val stem = word.dropLast(suffix.length)
        candidates.add(stem); candidates.add(stem + "e")
        if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) candidates.add(stem.dropLast(1))
        if (suffix == "ed" && stem.endsWith("i")) candidates.add(stem.dropLast(1) + "y")
    }
    return candidates.toList()
}
