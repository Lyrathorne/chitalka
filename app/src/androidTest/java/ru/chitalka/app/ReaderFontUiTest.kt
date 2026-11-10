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
class ReaderFontUiTest {
    @Test fun sliderChangesActualTextAndSurvivesRecreation() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        val device = UiDevice.getInstance(instrument)
        val fixed = InstrumentationRegistry.getArguments().getString("fixedFont") == "1"
        val (book, file) = feedbackBook(app, fixedFont = fixed)
        var original = 110.0
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                suspend fun ready() { withTimeout(10000) { var loaded = false; while (!loaded) { scenario.onActivity { loaded = it.supportFragmentManager.findFragmentByTag("epub") != null }; delay(100) } } }
                ready()
                scenario.readerPoint("Mira")
                scenario.onActivity { original = InstrumentationRegistry.getArguments().getString("restoreFont")?.toDouble()
                    ?: ReaderActivity::class.java.getDeclaredField("fontSize").apply { isAccessible = true }.getDouble(it) }
                suspend fun resize(right: Boolean) {
                    device.wait(Until.findObject(By.desc("Настройки чтения")), 5000).click()
                    val slider = device.wait(Until.findObject(By.clazz("android.widget.SeekBar")), 5000).visibleBounds
                    val inset = (16 * app.resources.displayMetrics.density).toInt()
                    device.swipe(if (right) slider.left + inset else slider.right - inset, slider.centerY(), if (right) slider.right - inset else slider.left + inset, slider.centerY(), 35)
                    device.wait(Until.findObject(By.text("Готово")), 5000).click()
                    delay(1500)
                }
                suspend fun height(): Double {
                    val raw = scenario.readerJs("""(function(){const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(w.nextNode()){const t=w.currentNode,i=t.textContent.indexOf('Mira');if(i>=0){const r=document.createRange();r.setStart(t,i);r.setEnd(t,i+4);return r.getBoundingClientRect().height;}}return 0;})()""")
                    return raw!!.toDouble().also { assertTrue("Glyph height is outside a readable range: $it", it in 8.0..150.0) }
                }
                try {
                    resize(false); val small = height(); feedbackCapture("reader-font-small-${if (fixed) "fixed" else "relative"}.png")
                    resize(true); val large = height(); feedbackCapture("reader-font-large-${if (fixed) "fixed" else "relative"}.png")
                    println("FONT_METRICS fixed=$fixed small=$small large=$large")
                    assertTrue("Slider changed its label but actual glyph height stayed $small -> $large", large > small * 1.4)
                    scenario.recreate(); ready(); scenario.readerPoint("Mira"); delay(700)
                    assertEquals(large, height(), 2.0)
                } finally {
                    scenario.onActivity { activity ->
                        ReaderActivity::class.java.getDeclaredField("fontSize").apply { isAccessible = true }.setDouble(activity, original)
                        ReaderActivity::class.java.getDeclaredMethod("applyPreferences").apply { isAccessible = true }.invoke(activity)
                    }
                    delay(300)
                }
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
}
