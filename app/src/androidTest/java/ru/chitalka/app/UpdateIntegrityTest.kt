package ru.chitalka.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.BackupCodec
import java.io.File

/** Read-only comparison across an actual in-place APK update; never clear personal data. */
@RunWith(AndroidJUnit4::class)
class UpdateIntegrityTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ChitalkaApp
    private val file get() = File(app.cacheDir, "mvp-update-checkpoint.json")
    private suspend fun snapshot(): String {
        val data = app.repository.backups.snapshot()
        val canonical = data.copy(books = data.books.sortedBy { it.hash }, words = data.words.sortedBy { it.id },
            bookmarks = data.bookmarks.sortedBy { it.id }, reviews = data.reviews.sortedWith(compareBy({ it.wordId }, { it.createdAt }, { it.rating })))
        val json = JSONObject(BackupCodec.encode(canonical)).apply { remove("createdAt") }
        val practice = app.repository.dao.currentPractice()
        json.put("practice", practice?.let { JSONObject().put("ids", it.wordIds).put("ratings", it.ratings).put("revealed", it.revealedFor ?: JSONObject.NULL) } ?: JSONObject.NULL)
        json.put("bookFiles", app.repository.dao.allBooks().sortedBy { it.id }.joinToString { "${it.id}:${File(it.path).exists()}" })
        return json.toString()
    }
    @Test fun beforeUpdate() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("updatePhase") == "before")
        assertEquals("ru.chitalka.app", app.packageName)
        check(!file.exists()) { "Complete the existing update comparison first" }
        file.writeText(snapshot())
    }
    @Test fun afterUpdate() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("updatePhase") == "after")
        @Suppress("DEPRECATION") val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        assertEquals("1.3.0", version)
        assertEquals(0, app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals("An in-place update changed learning data, positions, moments, session or book files", file.readText(), snapshot())
        file.delete()
    }
}
