package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*
import java.io.File
import java.util.UUID

/** Run seed, force-stop the target from adb, then run resume. Fixtures are cleaned by resume. */
@RunWith(AndroidJUnit4::class)
class PracticeProcessTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val checkpoint get() = File(app.cacheDir, "practice-process-checkpoint.json")

    @Test fun seedBeforeProcessDeath() = runBlocking<Unit> {
        check(!checkpoint.exists()) { "Finish the previous resume check before seeding again" }
        val old = app.repository.dao.currentPractice()
        val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
        val saved = JSONObject().put("first", first).put("second", second)
        if (old != null) saved.put("old", JSONObject().put("wordIds", old.wordIds).put("ratings", old.ratings).put("revealedFor", old.revealedFor ?: JSONObject.NULL))
        checkpoint.writeText(saved.toString())
        fun word(id: String, original: String, translation: String) = Word(id, original, id, translation, "Process recovery fixture", "d".repeat(64), "Test",
            """{"href":"chapter.xhtml","type":"application/xhtml+xml"}""")
        app.repository.dao.insert(word(first, "shore", "берег")); app.repository.dao.insert(word(second, "breeze", "ветерок"))
        app.repository.dao.put(PracticeSession(wordIds = JSONArray(listOf(first, second)).toString()))
        app.repository.practice.reveal(first); assertTrue(app.repository.practice.rate(first, 2))
        app.repository.practice.reveal(second)
    }

    @Test fun resumeAfterProcessDeath() = runBlocking<Unit> {
        val saved = JSONObject(checkpoint.readText())
        val ids = listOf(saved.getString("first"), saved.getString("second"))
        try {
            val device = UiDevice.getInstance(instrument)
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                device.wait(Until.findObject(By.text("Практика")), 10000).click()
                assertTrue(device.wait(Until.hasObject(By.text("breeze")), 5000))
                assertTrue(device.wait(Until.hasObject(By.text("ветерок")), 5000))
                assertFalse(device.hasObject(By.text("shore")))
                device.findObject(By.text("Помню")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Повторение завершено")), 5000))
                assertTrue(device.hasObject(By.text("Помню: 2")))
                assertEquals(2, app.repository.dao.allReviews().count { it.wordId in ids })
            }
        } finally {
            ids.forEach { id ->
                app.repository.dao.wordById(id)?.let { app.repository.dao.delete(it) }
                app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM ReviewEvent WHERE wordId = ?", arrayOf(id))
            }
            if (saved.has("old")) {
                val old = saved.getJSONObject("old")
                app.repository.dao.put(PracticeSession(wordIds = old.getString("wordIds"), ratings = old.getString("ratings"), revealedFor = if (old.isNull("revealedFor")) null else old.getString("revealedFor")))
            } else app.repository.dao.clearPractice()
            checkpoint.delete()
        }
    }
}
