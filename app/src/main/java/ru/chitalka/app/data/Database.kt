package ru.chitalka.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["hash"], unique = true)])
data class Book(
    @PrimaryKey val id: String,
    val hash: String,
    val title: String,
    val author: String,
    val path: String,
    val cover: String? = null,
    val locator: String? = null,
    val progress: Double = 0.0,
    val lastRead: Long = 0,
)

@Entity(indices = [Index(value = ["bookHash", "locator"], unique = true)])
data class Bookmark(
    @PrimaryKey val id: String,
    val bookHash: String,
    val bookTitle: String,
    val author: String,
    val locator: String,
    val quote: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Entity(indices = [Index(value = ["normalized", "translation"], unique = true)])
data class Word(
    @PrimaryKey val id: String,
    val original: String,
    val normalized: String,
    val translation: String,
    val context: String,
    val bookHash: String,
    val bookTitle: String,
    val locator: String,
    val source: String = "ML Kit • машинный перевод",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(indices = [Index(value = ["wordId", "createdAt", "rating"])])
data class ReviewEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: String,
    val rating: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM Book ORDER BY lastRead DESC, title") fun books(): Flow<List<Book>>
    @Query("SELECT * FROM Bookmark ORDER BY createdAt DESC") fun bookmarks(): Flow<List<Bookmark>>
    @Query("SELECT * FROM Word ORDER BY createdAt DESC") fun words(): Flow<List<Word>>
    @Query("SELECT * FROM Book WHERE id = :id") suspend fun book(id: String): Book?
    @Query("SELECT * FROM Book WHERE hash = :hash") suspend fun bookByHash(hash: String): Book?
    @Query("SELECT * FROM Book") suspend fun allBooks(): List<Book>
    @Query("SELECT * FROM Word") suspend fun allWords(): List<Word>
    @Query("SELECT * FROM Bookmark") suspend fun allBookmarks(): List<Bookmark>
    @Query("SELECT * FROM ReviewEvent") suspend fun allReviews(): List<ReviewEvent>
    @Query("SELECT * FROM ReadingState") suspend fun allReadingStates(): List<ReadingState>
    @Query("SELECT * FROM ReadingState WHERE hash = :hash") suspend fun readingState(hash: String): ReadingState?
    @Query("SELECT * FROM Word WHERE normalized = :normalized AND translation = :translation") suspend fun wordByMeaning(normalized: String, translation: String): Word?
    @Query("SELECT * FROM Word WHERE id = :id") suspend fun wordById(id: String): Word?
    @Query("SELECT * FROM Bookmark WHERE bookHash = :hash AND locator = :locator") suspend fun bookmarkByLocation(hash: String, locator: String): Bookmark?
    @Query("SELECT * FROM Bookmark WHERE id = :id") suspend fun bookmarkById(id: String): Bookmark?
    @Query("SELECT COUNT(*) FROM ReviewEvent WHERE wordId = :wordId AND rating = :rating AND createdAt = :time") suspend fun reviewCount(wordId: String, rating: Int, time: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(state: ReadingState)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(book: Book): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(bookmark: Bookmark): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(word: Word): Long
    @Insert suspend fun insert(event: ReviewEvent)
    @Update suspend fun update(bookmark: Bookmark)
    @Update suspend fun update(word: Word)
    @Query("UPDATE Book SET locator = :locator, progress = :progress, lastRead = :time WHERE id = :id")
    suspend fun progress(id: String, locator: String, progress: Double, time: Long)
    @Delete suspend fun delete(book: Book)
    @Delete suspend fun delete(bookmark: Bookmark)
    @Delete suspend fun delete(word: Word)
}

@Entity
data class ReadingState(@PrimaryKey val hash: String, val title: String, val author: String,
    val locator: String?, val progress: Double, val lastRead: Long)

@Database(entities = [Book::class, Bookmark::class, Word::class, ReviewEvent::class, ReadingState::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun dao(): LibraryDao }
