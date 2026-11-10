package ru.chitalka.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Bookmark ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS ReadingState (hash TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, locator TEXT, progress REAL NOT NULL, lastRead INTEGER NOT NULL, PRIMARY KEY(hash))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ReviewEvent_wordId_createdAt_rating ON ReviewEvent(wordId, createdAt, rating)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS PracticeSession (id INTEGER NOT NULL, wordIds TEXT NOT NULL, ratings TEXT NOT NULL, revealedFor TEXT, PRIMARY KEY(id))")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Bookmark ADD COLUMN kind TEXT NOT NULL DEFAULT 'QUOTE'")
    }
}
