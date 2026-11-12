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
import org.json.JSONObject
import ru.chitalka.app.reader.ReaderActivity
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnlineTranslationUiTest {
    @Test fun explicitOnlineTranslationCanBeCancelledComparedCorrectedAndSaved() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlinePhase") == "live")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        val device = UiDevice.getInstance(instrument)
        val sentence = "The door to my right opened quickly."
        val (book, file) = feedbackBook(app, sentence)
        fun click(text: String) = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(text), java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE))), 5000).click()
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                val p = scenario.readerPoint("door"); delay(400)
                device.swipe(p.first, p.second, p.first, p.second, 150)
                assertTrue(device.wait(Until.hasObject(By.desc("Онлайн-перевод")), 5000))
                scenario.readerJs("""(function(){const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(w.nextNode()){const t=w.currentNode,i=t.textContent.indexOf(${JSONObject.quote(sentence)});if(i>=0){const r=document.createRange();r.setStart(t,i);r.setEnd(t,i+${sentence.length});const s=window.getSelection();s.removeAllRanges();s.addRange(r);return true;}}return false;})()""")
                assertTrue(device.wait(Until.hasObject(By.desc("Выделенный фрагмент").textContains("The door")), 5000))
                device.wait(Until.findObject(By.desc("Онлайн-перевод")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("Перевести онлайн?")), 5000))
                feedbackCapture("online-consent.png"); click("Отмена")
                assertFalse(device.hasObject(By.desc("Сохранить слово")))
                assertTrue(app.repository.dao.allWords().none { it.bookHash == book.hash })
                device.wait(Until.findObject(By.desc("Онлайн-перевод")), 5000).click(); click("Разрешить и перевести")
                assertTrue(device.wait(Until.hasObject(By.desc("Сохранить слово")), 45000))
                val output = device.findObject(By.desc("Изменить перевод"))
                val actual = output.text
                assertTrue("Spatial meaning must be present in this verified example", actual.contains("справа", true))
                val offline = app.translator.translate(sentence)
                File(app.cacheDir, "translation-comparison.json").writeText(JSONObject().put("source", sentence).put("offline", offline).put("online", actual).toString())
                feedbackCapture("online-result.png")
                output.click()
                assertTrue(device.wait(Until.hasObject(By.text("Исправить перевод")), 5000))
                device.findObject(By.clazz("android.widget.EditText")).text = "Дверь справа от меня быстро открылась."
                click("Применить")
                assertTrue(device.wait(Until.hasObject(By.desc("Изменить перевод").text("Дверь справа от меня быстро открылась.")), 5000))
                device.findObject(By.desc("Сохранить слово")).click()
                withTimeout(10000) {
                    while (app.repository.dao.allWords().none { it.bookHash == book.hash }) delay(100)
                }
                val saved = app.repository.dao.allWords().single { it.bookHash == book.hash }
                assertEquals(sentence, saved.original)
                assertEquals("Дверь справа от меня быстро открылась.", saved.translation)
                assertTrue(saved.source.contains("Ваш перевод")); assertTrue(saved.source.contains("MyMemory"))
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
}
