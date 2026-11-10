package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PracticeUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)

    @Test fun hidesAnswerRestoresAfterRecreateAndShowsSummary() = runBlocking<Unit> {
        val id = UUID.randomUUID().toString()
        val first = Word(id, "harbour", id, "гавань", "The harbour was quiet.", "c".repeat(64), "Test",
            """{"href":"chapter.xhtml","type":"application/xhtml+xml"}""")
        val second = first.copy(id = UUID.randomUUID().toString(), original = "meadow", normalized = UUID.randomUUID().toString(), translation = "луг", context = "A green meadow.")
        val old = app.repository.dao.currentPractice()
        app.repository.dao.insert(first); app.repository.dao.insert(second)
        app.repository.dao.put(PracticeSession(wordIds = JSONArray(listOf(first.id, second.id)).toString()))
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                device.wait(Until.findObject(By.text("Практика")), 10000).click()
                assertTrue(device.wait(Until.hasObject(By.text("harbour")), 5000))
                assertFalse(device.hasObject(By.text("гавань")))
                device.findObject(By.text("Показать контекст")).click()
                assertTrue(device.wait(Until.hasObject(By.text(first.context)), 5000))
                device.findObject(By.text("Показать ответ")).click()
                assertTrue(device.wait(Until.hasObject(By.text("гавань")), 5000))
                capture("practice-answer.png")
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("гавань")), 10000))
                device.findObject(By.text("Помню")).click()
                assertTrue(device.wait(Until.hasObject(By.text("meadow")), 5000))
                assertFalse(device.hasObject(By.text("луг")))
                device.findObject(By.text("Книги")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Мои книги")), 5000))
                device.wait(Until.findObject(By.text("Практика")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("meadow")), 5000))
                device.findObject(By.text("Показать ответ")).click()
                device.wait(Until.findObject(By.text("Трудно")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("Повторение завершено")), 5000))
                assertTrue(device.hasObject(By.text("Ответов: 2 из 2.")))
                assertTrue(device.hasObject(By.text("Помню: 1"))); assertTrue(device.hasObject(By.text("Трудно: 1")))
                capture("practice-summary.png")
                val booksTab = device.wait(Until.findObject(By.text("Книги")), 5000)
                assertTrue("Library navigation must remain visible on summary", booksTab.visibleBounds.height() > 0)
                booksTab.click()
                assertTrue(device.wait(Until.hasObject(By.text("Мои книги")), 5000))
                device.findObject(By.text("Практика")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Повторение завершено")), 5000))
                val reviews = app.repository.dao.allReviews().filter { it.wordId == first.id || it.wordId == second.id }
                assertEquals(2, reviews.size)
                device.findObject(By.text("Повторить ещё раз")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Показать ответ")), 5000))
            }
        } finally {
            app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM ReviewEvent WHERE wordId IN (?, ?)", arrayOf(first.id, second.id))
            app.repository.dao.delete(first); app.repository.dao.delete(second)
            if (old == null) app.repository.dao.clearPractice() else app.repository.dao.put(old)
        }
    }

    private fun capture(name: String) {
        device.waitForIdle()
        instrument.waitForIdleSync()
        Thread.sleep(250)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChitalkaTests")
        }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                app.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }
}
