package ru.chitalka.app

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import ru.chitalka.app.data.*
import ru.chitalka.app.reader.ReaderActivity
import java.util.UUID
import ru.chitalka.app.ui.palette

@RunWith(AndroidJUnit4::class)
class ReaderFeedbackUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private fun click(text: String) = device.clickEditorAction(text)
    private fun view(activity: ReaderActivity, name: String) = ReaderActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as View

    @Test fun centerTapPreservesViewportTextAndLocatorIncludingRecreation() = runBlocking<Unit> {
        val (book, file) = feedbackBook(app)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                scenario.readerPoint("Mira"); delay(700)
                val metrics = """JSON.stringify({w:innerWidth,h:innerHeight,x:scrollX,y:scrollY,rects:Array.from(document.querySelectorAll('p')).slice(0,5).map(p=>{const r=p.getBoundingClientRect();return [r.x,r.y,r.width,r.height,p.textContent]})})"""
                val before = scenario.readerJs(metrics)
                var bounds = android.graphics.Rect(); var locator = ""
                scenario.onActivity { activity ->
                    view(activity, "container").getGlobalVisibleRect(bounds)
                    locator = (activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment).currentLocator.value.toJSON().toString()
                }
                repeat(4) { index ->
                    device.click(bounds.centerX(), bounds.centerY()); delay(350)
                    scenario.onActivity { activity ->
                        assertEquals(if (index % 2 == 0) View.INVISIBLE else View.VISIBLE, view(activity, "toolbar").visibility)
                        val now = android.graphics.Rect(); view(activity, "container").getGlobalVisibleRect(now)
                        assertEquals(bounds, now)
                        assertEquals(locator, (activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment).currentLocator.value.toJSON().toString())
                    }
                    assertEquals("Text geometry changed when controls were hidden", before, scenario.readerJs(metrics))
                }
                device.click(bounds.centerX(), bounds.centerY()); delay(300)
                scenario.recreate(); delay(2000)
                scenario.readerPoint("Mira"); delay(400)
                scenario.onActivity { activity -> assertEquals(View.VISIBLE, view(activity, "toolbar").visibility) }
                val restored = scenario.readerJs(metrics)
                scenario.onActivity { activity -> view(activity, "container").getGlobalVisibleRect(bounds) }
                device.click(bounds.centerX(), bounds.centerY()); delay(350)
                scenario.onActivity { activity -> assertEquals(View.INVISIBLE, view(activity, "toolbar").visibility) }
                assertEquals("A center tap must also preserve geometry in the recreated reader", restored, scenario.readerJs(metrics))
                feedbackCapture("reader-controls-hidden.png")
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }

    @Test fun quotesUseHighlightNotesUseUnderlineAndTappingEditsTheMoment() = runBlocking<Unit> {
        val (book, file) = feedbackBook(app)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Закладки книги")), 10000))
                suspend fun mark(text: String, note: String): Bookmark {
                    scenario.readerPoint(text)
                    scenario.readerJs("""(function(){const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(w.nextNode()){const t=w.currentNode,i=t.textContent.indexOf(${JSONObject.quote(text)});if(i>=0){const r=document.createRange();r.setStart(t,i);r.setEnd(t,i+${text.length});const s=window.getSelection();s.removeAllRanges();s.addRange(r);return true;}}return false;})()""")
                    val answer = CompletableDeferred<String>()
                    scenario.onActivity { activity -> activity.lifecycleScope.launch {
                        val n = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                        answer.complete(n.currentSelection()!!.locator.toJSON().toString()); n.clearSelection()
                    } }
                    return Bookmark(UUID.randomUUID().toString(), book.hash, book.title, book.author, answer.await(), text, note).also { app.repository.dao.insert(it) }
                }
                val quote = mark("curious", "")
                val note = mark("gate", "My original note")
                delay(700)
                withTimeout(10000) {
                    while ((scenario.readerJs("document.querySelectorAll('[data-style$=Highlight]').length")?.toIntOrNull() ?: 0) < 1 || (scenario.readerJs("document.querySelectorAll('[data-style$=Underline]').length")?.toIntOrNull() ?: 0) < 1) delay(100)
                }
                val highlight = app.themes.current.value.palette.highlight
                val background = org.json.JSONTokener(scenario.readerJs("getComputedStyle(document.querySelector('[data-style$=Highlight] > div')).backgroundColor")).nextValue()
                assertEquals("rgb(${android.graphics.Color.red(highlight)}, ${android.graphics.Color.green(highlight)}, ${android.graphics.Color.blue(highlight)})", background)
                assertEquals(if (app.themes.current.value == ru.chitalka.app.ui.AppTheme.NIGHT) "screen" else "multiply", org.json.JSONTokener(scenario.readerJs("getComputedStyle(document.querySelector('[data-style$=Highlight] > div')).mixBlendMode")).nextValue())
                assertEquals("rgba(0, 0, 0, 0)", org.json.JSONTokener(scenario.readerJs("getComputedStyle(document.querySelector('[data-style$=Underline] > div')).backgroundColor")).nextValue())
                // Publisher reset rules must not erase the reader's own note markers.
                scenario.readerJs("var reset=document.createElement('style');reset.textContent='div { border: 0 !important; }';document.head.appendChild(reset);true")
                val border = org.json.JSONTokener(scenario.readerJs("getComputedStyle(document.querySelector('[data-style$=Underline] > div')).borderBottomWidth")).nextValue().toString().removeSuffix("px").toDouble()
                assertEquals("Publisher reset hid the underline", 2.0, border, .6)
                feedbackCapture("quote-highlight-note-underline.png")
                val p = scenario.readerPoint("gate"); delay(300); device.click(p.first, p.second)
                assertTrue("Tapping an underlined note must open its editor", device.wait(Until.hasObject(By.text("Сохранённый момент")), 5000))
                assertEquals("My original note", device.findObject(By.desc("Ваша заметка")).text)
                feedbackCapture("moment-reader-editor.png")
                device.findObject(By.desc("Ваша заметка")).text = "Edited directly in the book"
                device.findObject(By.desc("Текст цитаты")).text = "My edited excerpt"
                click("Сохранить")
                withTimeout(5000) { while (app.repository.dao.bookmarkById(note.id)?.note != "Edited directly in the book") delay(100) }
                val saved = app.repository.dao.bookmarkById(note.id)!!
                assertEquals(note.locator, saved.locator); assertEquals("My edited excerpt", saved.quote)
                device.wait(Until.findObject(By.desc("Закладки книги")), 5000).click(); click("Закладка здесь")
                withTimeout(5000) { while (app.repository.dao.allBookmarks().none { it.bookHash == book.hash && it.section == MomentSection.BOOKMARK }) delay(100) }
                device.wait(Until.findObject(By.desc("Закладки книги")), 5000).click(); click("Закладки")
                feedbackCapture("moments-page-bookmarks.png"); click("Цитаты")
                assertTrue(device.hasObject(By.textContains(quote.quote))); assertFalse(device.hasObject(By.textContains(saved.quote)))
                click("Заметки"); assertTrue(device.hasObject(By.textContains(saved.quote)))
                device.findObject(By.textContains(saved.quote)).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Ваша заметка").text(saved.note)), 5000))
                click("Закрыть")
            }
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                device.wait(Until.findObject(By.desc("Понравившиеся моменты")), 10000).click(); click("Заметки")
                assertTrue(device.wait(Until.hasObject(By.text("My edited excerpt")), 5000))
                click("My edited excerpt")
                assertTrue(device.wait(Until.hasObject(By.text("Сохранённый момент")), 5000))
                feedbackCapture("moments-direct-editor.png")
                click("Закрыть"); click("Закладки"); feedbackCapture("moments-three-sections.png")
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
}
