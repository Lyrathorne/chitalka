package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*
import java.io.File
import java.util.UUID

/** Explicit phases protect ordinary test runs from deleting/downloading SDK models. */
@RunWith(AndroidJUnit4::class)
class ModelLifecycleTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private val checkpoint get() = File(app.cacheDir, "model-lifecycle-checkpoint.json")
    private val model get() = TranslateRemoteModel.Builder(TranslateLanguage.RUSSIAN).build()
    private fun capture(name: String) {
        device.waitForIdle(); instrument.waitForIdleSync(); Thread.sleep(300)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, testPictureName(name))
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChitalkaTests")
        }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri -> app.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            bitmap.recycle()
        }
    }

    @Test fun removeModelKeepsDataAndInvalidatesTranslationCache() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelPhase") == "remove")
        check(!checkpoint.exists())
        withTimeout(60000) { app.translator.prepare() }
        assertTrue(app.translator.translate("curious").isNotBlank())
        val id = UUID.randomUUID().toString()
        val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml"}"""
        val word = Word(id, "keepsake", id, "памятная вещь", "A keepsake from a book", "e".repeat(64), "Model test", locator)
        val mark = Bookmark("mark-$id", word.bookHash, word.bookTitle, "Test author", locator, "A saved quotation", "Keep my note")
        app.repository.dao.insert(word); app.repository.dao.insert(mark); app.repository.dao.insert(ReviewEvent(wordId = id, rating = 2))
        checkpoint.writeText(JSONObject().put("id", id).put("mark", mark.id).toString())
        val books = app.repository.dao.allBooks().sortedBy { it.id }
        val words = app.repository.dao.allWords().sortedBy { it.id }
        val marks = app.repository.dao.allBookmarks().sortedBy { it.id }
        val reviews = app.repository.dao.allReviews().sortedBy { it.id }
        val downloads = app.modelDownloads
        withContext(Dispatchers.Main) { downloads.refresh() }
        withTimeout(10000) { downloads.state.first { it.phase == ModelPhase.READY } }
        ActivityScenario.launch<ModelDownloadActivity>(Intent(app, ModelDownloadActivity::class.java).putExtra("start", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { screen ->
            device.wait(Until.findObject(By.text("Удалить модель")), 5000).click()
            device.wait(Until.findObject(By.text("Отмена")), 5000).click()
            assertTrue(RemoteModelManager.getInstance().isModelDownloaded(model).await())
            device.findObject(By.text("Удалить модель")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Удалить языковую модель?")), 5000))
            capture("model-removal-confirm.png")
            device.findObjects(By.text("Удалить модель")).last().click()
            withTimeout(30000) { downloads.state.first { it.phase == ModelPhase.MISSING } }
            assertFalse(RemoteModelManager.getInstance().isModelDownloaded(model).await())
            screen.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Переводчик не загружен")), 10000))
            delay(600)
            assertEquals(ModelPhase.MISSING, downloads.state.value.phase)
            assertTrue(withTimeout(10000) { runCatching { app.translator.translate("curious") }.isFailure })
            capture("model-removed.png")
        }
        assertEquals(books, app.repository.dao.allBooks().sortedBy { it.id })
        assertEquals(words, app.repository.dao.allWords().sortedBy { it.id })
        assertEquals(marks, app.repository.dao.allBookmarks().sortedBy { it.id })
        assertEquals(reviews, app.repository.dao.allReviews().sortedBy { it.id })
    }

    @Test fun downloadAgainAndVerifySavedData() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelPhase") == "restore")
        val saved = JSONObject(checkpoint.readText())
        val id = saved.getString("id")
        val downloads = app.modelDownloads
        ActivityScenario.launch<ModelDownloadActivity>(Intent(app, ModelDownloadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            val state = withTimeout(10000) { downloads.state.first { s -> s.phase != ModelPhase.CHECKING } }
            if (state.phase != ModelPhase.READY && state.phase != ModelPhase.DOWNLOADING) device.wait(Until.findObject(By.text("Загрузить модель")), 5000).click()
            val progressing = withTimeout(180000) { downloads.state.first { s -> s.phase == ModelPhase.READY || (s.phase == ModelPhase.DOWNLOADING && s.bytes > 0) } }
            if (progressing.phase == ModelPhase.DOWNLOADING) capture("model-download-again.png")
            withTimeout(180000) { downloads.state.first { s -> s.phase == ModelPhase.READY } }
            assertTrue(RemoteModelManager.getInstance().isModelDownloaded(model).await())
            capture("model-reinstalled.png")
        }
        assertTrue(withTimeout(30000) { app.translator.translate("curious") }.any { it in 'А'..'я' })
        assertEquals("памятная вещь", app.repository.dao.wordById(id)?.translation)
        assertEquals("Keep my note", app.repository.dao.bookmarkById(saved.getString("mark"))?.note)
        assertEquals(1, app.repository.dao.allReviews().count { it.wordId == id })
        app.repository.dao.wordById(id)?.let { app.repository.dao.delete(it) }
        app.repository.dao.bookmarkById(saved.getString("mark"))?.let { app.repository.dao.delete(it) }
        app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM ReviewEvent WHERE wordId = ?", arrayOf(id))
        checkpoint.delete()
    }
}
