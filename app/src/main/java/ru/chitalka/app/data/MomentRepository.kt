package ru.chitalka.app.data

import androidx.room.withTransaction

data class SavedMoment(val bookmark: Bookmark, val created: Boolean)

enum class MomentSection(val title: String) { BOOKMARK("Закладки"), QUOTE("Цитаты"), NOTE("Заметки") }
val Bookmark.section: MomentSection get() = when {
    kind == "BOOKMARK" -> MomentSection.BOOKMARK
    note.isNotBlank() -> MomentSection.NOTE
    else -> MomentSection.QUOTE
}

/** Quotes and notes share one source range; adding a note never duplicates the quote. */
class MomentRepository(private val db: LibraryDatabase) {
    private val dao = db.dao()
    suspend fun quote(draft: Bookmark): SavedMoment = db.withTransaction {
        val existing = dao.bookmarkByLocation(draft.bookHash, draft.locator)
        if (existing != null) return@withTransaction SavedMoment(existing, false)
        check(draft.quote.isNotBlank())
        check(dao.insert(draft) != -1L)
        SavedMoment(draft, true)
    }
    suspend fun note(draft: Bookmark, text: String): SavedMoment = db.withTransaction {
        val existing = dao.bookmarkByLocation(draft.bookHash, draft.locator)
        val now = System.currentTimeMillis()
        if (existing != null) {
            val changed = existing.copy(note = text, updatedAt = now)
            dao.update(changed)
            SavedMoment(changed, false)
        } else {
            require(text.isNotBlank())
            val created = draft.copy(note = text, createdAt = now, updatedAt = now)
            check(dao.insert(created) != -1L)
            SavedMoment(created, true)
        }
    }
    suspend fun undoQuote(saved: Bookmark): Boolean = db.withTransaction {
        if (dao.bookmarkById(saved.id) != saved) return@withTransaction false
        dao.delete(saved)
        true
    }
    suspend fun edit(id: String, quote: String, note: String) = db.withTransaction {
        require(quote.isNotBlank())
        val current = requireNotNull(dao.bookmarkById(id)) { "Момент уже удалён" }
        dao.update(current.copy(quote = quote.trim(), note = note.trim(), updatedAt = System.currentTimeMillis()))
    }
}
