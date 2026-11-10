package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.reader.ReaderActivity

@RunWith(AndroidJUnit4::class)
class OfflineTranslationUiTest {
    @Test fun failedOnlineRequestKeepsPreviousOfflineTranslationAndItsSource() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlinePhase") == "offline")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        val device = UiDevice.getInstance(instrument)
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        assertNull("Run this phase with networking disabled", connectivity.activeNetwork)
        val (book, file) = feedbackBook(app)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                val p = scenario.readerPoint("curious"); delay(400)
                device.swipe(p.first, p.second, p.first, p.second, 150)
                device.wait(Until.findObject(By.desc("Перевести выделенное")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Сохранить слово")), 15000))
                val previous = device.findObject(By.desc("Изменить перевод")).text
                device.wait(Until.findObject(By.desc("Онлайн-перевод")), 5000).click()
                device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Отправить и перевести", java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE))), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.textContains("Показан прежний вариант")), 45000))
                assertEquals(previous, device.findObject(By.desc("Изменить перевод")).text)
                assertTrue(device.hasObject(By.textContains("Google Translate")))
                assertTrue(device.findObject(By.desc("Онлайн-перевод")).isEnabled)
                assertTrue(device.hasObject(By.desc("Сохранить слово")))
                assertTrue(app.repository.dao.allWords().none { it.bookHash == book.hash })
                feedbackCapture("online-unavailable-keeps-offline.png")
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
}
