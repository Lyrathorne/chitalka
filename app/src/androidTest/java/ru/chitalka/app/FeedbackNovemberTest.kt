package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.reader.ReaderActivity

@RunWith(AndroidJUnit4::class)
class FeedbackNovemberTest {
    @Test fun downwardSwipeSavesPageBookmark() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        assertTrue(app.packageName.endsWith(".mvpcheck"))
        val device = UiDevice.getInstance(instrument)
        val (book, file) = feedbackBook(app)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 15000))
                scenario.readerJs("document.body.textContent")
                device.swipe(device.displayWidth/2, device.displayHeight/3, device.displayWidth/2, device.displayHeight*2/3, 20)
                withTimeout(5000) { while(app.repository.dao.allBookmarks().none { it.bookHash == book.hash && it.kind == "BOOKMARK" }) delay(100) }
                feedbackCapture("swipe-bookmark.png")
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
    @Test fun createsShelfAndPreservesItAfterRecreation() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        assertTrue(app.packageName.endsWith(".mvpcheck"))
        val device = UiDevice.getInstance(instrument)
        val shelf = "QA " + System.currentTimeMillis()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
            device.wait(Until.findObject(By.text("Новая полка")), 15000).click()
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000).text = shelf
            device.findObject(By.text("Создать")).click()
            assertTrue(device.wait(Until.hasObject(By.text(shelf)), 5000))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text(shelf)), 10000))
            feedbackCapture("shelves-library.png")
        }
        val prefs = app.getSharedPreferences("bookshelves", 0)
        val data = org.json.JSONObject(prefs.getString("data", "{}")!!)
        assertTrue(data.has(shelf)); data.remove(shelf); prefs.edit().putString("data", data.toString()).commit()
    }
}
