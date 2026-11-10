package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import ru.chitalka.app.reader.ReaderActivity
import ru.chitalka.app.data.ModelPhase
import java.io.File

@RunWith(AndroidJUnit4::class)
class MissingModelUiTest {
    @Test fun readingRemainsAvailableAndTranslationRequestsModelExplicitly() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelPhase") == "missing")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        val device = UiDevice.getInstance(instrument)
        val file = File(app.cacheDir, "missing-model-fixture.epub")
        app.assets.open("sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = app.repository.import(Uri.fromFile(file)); file.delete()
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
            val point = CompletableDeferred<Pair<Int, Int>>()
            scenario.onActivity { activity -> activity.lifecycleScope.launch {
                var nav: EpubNavigatorFragment? = null
                repeat(50) { if (nav == null) { nav = activity.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment; delay(100) } }
                val n = requireNotNull(nav)
                val raw = n.evaluateJavascript("""(function(){const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(w.nextNode()){const t=w.currentNode,i=t.textContent.indexOf('curious');if(i>=0){const r=document.createRange();r.setStart(t,i);r.setEnd(t,i+7);const s=window.getSelection();s.removeAllRanges();s.addRange(r);return readium.getCurrentSelection();}}return null;})()""")
                val rect = requireNotNull(n.currentSelection()?.rect)
                val height = JSONObject(raw!!).getJSONObject("rect").getDouble("height")
                val offset = IntArray(2); n.view!!.getLocationOnScreen(offset)
                point.complete(Pair((offset[0]+rect.centerX()).toInt(), (offset[1]+rect.top+height/2).toInt()))
                n.clearSelection()
            } }
            val p = withTimeout(10000) { point.await() }; delay(400)
            device.swipe(p.first, p.second, p.first, p.second, 150)
            device.wait(Until.findObject(By.desc("Перевести выделенное")), 5000).click()
            assertTrue(device.wait(Until.hasObject(By.text("Нужна языковая модель")), 15000))
            assertTrue(device.hasObject(By.text("Загрузить переводчик")))
            assertTrue(device.hasObject(By.desc("Сохранить цитату")))
            assertTrue(device.hasObject(By.desc("Добавить заметку")))
            assertFalse(device.hasObject(By.desc("Сохранить слово")))
            assertEquals(ModelPhase.MISSING, app.modelDownloads.state.value.phase)
        }
    }
}
