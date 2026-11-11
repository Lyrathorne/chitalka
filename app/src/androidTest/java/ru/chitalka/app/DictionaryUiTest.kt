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
class DictionaryUiTest {
    @Test fun bundledDictionarySupportsFormsAndRequiresChoiceBeforeSaving() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        val dictionary = app.dictionary
        assertTrue(dictionary.lookup("went").any { it.headword == "go" })
        assertTrue(dictionary.lookup("children").any { it.headword == "child" })
        assertTrue(dictionary.lookup("Well‑Known").any { it.headword == "well-known" })
        assertTrue(dictionary.lookup("running").isNotEmpty())
        assertTrue(dictionary.lookup("xyzzyinvalidwordzz").isEmpty())
        assertTrue(dictionary.lookup("curious").any { it.translation.contains("любопыт") })
        assertTrue(dictionary.lookup("gate").all { !it.translation.contains("[[") })
        val device = UiDevice.getInstance(instrument)
        val (book, file) = feedbackBook(app)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                val point = scenario.readerPoint("curious"); delay(300)
                device.swipe(point.first, point.second, point.first, point.second, 150)
                device.wait(Until.findObject(By.desc("Перевести выделенное")), 5000).click()
                device.wait(Until.findObject(By.desc("Словарные значения")), 15000)
                assertFalse("Do not silently choose the first of several senses", device.hasObject(By.desc("Сохранить слово")))
                feedbackCapture("dictionary-choose.png")
                device.findObject(By.desc("Словарные значения")).click()
                if (!device.hasObject(By.textContains("любопыт"))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textContains("любопыт"))
                device.wait(Until.findObject(By.textContains("любопыт")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Изменить перевод").textContains("любопыт")), 5000))
                assertTrue(device.hasObject(By.textContains("FreeDict")))
                feedbackCapture("dictionary-result.png")
                device.findObject(By.desc("Сохранить слово")).click()
                withTimeout(5000) { while (app.repository.dao.allWords().none { it.bookHash == book.hash }) delay(100) }
                val word = app.repository.dao.allWords().single { it.bookHash == book.hash }
                assertTrue(word.translation.contains("любопыт")); assertTrue(word.source.contains("FreeDict"))
                assertTrue(word.context.contains("curious")); assertTrue(word.locator.contains("chapter2"))
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
}
