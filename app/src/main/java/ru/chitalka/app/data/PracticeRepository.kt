package ru.chitalka.app.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

data class PracticeProgress(val session: PracticeSession?, val current: Word?, val answered: Int,
    val total: Int, val missing: Int, val counts: List<Int>) {
    val finished get() = session != null && current == null
    val revealed get() = current != null && session?.revealedFor == current.id
}

fun practiceProgress(session: PracticeSession?, words: List<Word>): PracticeProgress {
    if (session == null) return PracticeProgress(null, null, 0, 0, 0, listOf(0, 0, 0))
    val ids = JSONArray(session.wordIds).let { a -> (0 until a.length()).map { a.getString(it) } }
    val ratings = JSONObject(session.ratings)
    val byId = words.associateBy { it.id }
    val counts = (0..2).map { rating -> ids.count { ratings.has(it) && ratings.getInt(it) == rating } }
    return PracticeProgress(session, ids.firstOrNull { !ratings.has(it) && byId.containsKey(it) }?.let(byId::get),
        counts.sum(), ids.size, ids.count { !ratings.has(it) && !byId.containsKey(it) }, counts)
}

class PracticeRepository(private val db: LibraryDatabase) {
    private val dao = db.dao()
    val sessions = dao.practiceSession()

    suspend fun start(dueOnly: Boolean = false) = db.withTransaction {
        val words = if (dueOnly) ReviewSchedule.dueWords(dao.allWords(), dao.allReviews(), System.currentTimeMillis()) else dao.allWords().sortedByDescending { it.createdAt }
        if (words.isNotEmpty()) dao.put(PracticeSession(wordIds = JSONArray(words.map { it.id }).toString()))
    }

    suspend fun reveal(expectedId: String) = db.withTransaction {
        val session = dao.currentPractice() ?: return@withTransaction
        if (practiceProgress(session, dao.allWords()).current?.id == expectedId) dao.put(session.copy(revealedFor = expectedId))
    }

    suspend fun rate(expectedId: String, rating: Int): Boolean = db.withTransaction {
        require(rating in 0..2)
        val session = dao.currentPractice() ?: return@withTransaction false
        val progress = practiceProgress(session, dao.allWords())
        if (progress.current?.id != expectedId || !progress.revealed) return@withTransaction false
        // The answer and advancement commit together. Repeated taps for this card are ignored.
        dao.insert(ReviewEvent(wordId = expectedId, rating = rating))
        dao.put(session.copy(ratings = JSONObject(session.ratings).put(expectedId, rating).toString(), revealedFor = null))
        true
    }
}
