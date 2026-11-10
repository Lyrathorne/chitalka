package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import ru.chitalka.app.reader.ReaderActivity
import java.io.File
import java.util.UUID
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class QuoteSelectionUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private fun click(text: String) = device.clickEditorAction(text)

    @Test fun quoteAndNotePreserveTwoParagraphsWithoutDuplicatesOrTranslation() = runBlocking<Unit> {
        val file = File(app.cacheDir, "quote-ui-${UUID.randomUUID()}.epub")
        ZipInputStream(app.assets.open("sample.epub")).use { input -> ZipOutputStream(file.outputStream()).use { output ->
            while (true) {
                val entry = input.nextEntry ?: break
                var bytes = input.readBytes()
                if (entry.name.endsWith("book.opf")) bytes = bytes.toString(Charsets.UTF_8)
                    .replace("urn:chitalka:quiet-garden:1", "urn:chitalka:quote:${UUID.randomUUID()}")
                    .replace("The Quiet Garden", "Quote selection test").toByteArray()
                val target = ZipEntry(entry.name)
                if (entry.name == "mimetype") { target.method = ZipEntry.STORED; target.size = bytes.size.toLong(); target.compressedSize = target.size; target.crc = CRC32().apply { update(bytes) }.value }
                output.putNextEntry(target); output.write(bytes); output.closeEntry()
            }
        } }
        val book = app.repository.import(Uri.fromFile(file))
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                suspend fun select(): String {
                    var point: Pair<Int, Int>? = null
                    repeat(40) {
                        if (point == null) {
                            val answer = CompletableDeferred<Pair<Int, Int>?>()
                            scenario.onActivity { activity -> activity.lifecycleScope.launch {
                                answer.complete(runCatching {
                                    val nav = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                                    val raw = nav.evaluateJavascript("""(function(){const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(w.nextNode()){const t=w.currentNode,i=t.textContent.indexOf('curious');if(i>=0){const r=document.createRange();r.setStart(t,i);r.setEnd(t,i+7);const s=window.getSelection();s.removeAllRanges();s.addRange(r);return readium.getCurrentSelection();}}return null;})()""")
                                    val rect = requireNotNull(nav.currentSelection()?.rect)
                                    val height = JSONObject(raw!!).getJSONObject("rect").getDouble("height")
                                    val offset = IntArray(2); nav.view!!.getLocationOnScreen(offset)
                                    nav.clearSelection()
                                    Pair((offset[0] + rect.centerX()).toInt(), (offset[1] + rect.top + height / 2).toInt())
                                }.getOrNull())
                            } }
                            point = answer.await(); if (point == null) delay(100)
                        }
                    }
                    device.findObject(By.text(java.util.regex.Pattern.compile("Понятно", java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE)))?.click()
                    val p = requireNotNull(point)
                    delay(400)
                    device.swipe(p.first, p.second, p.first, p.second, 150)
                    assertTrue(device.wait(Until.hasObject(By.desc("Сохранить цитату")), 5000))
                    val selected = CompletableDeferred<String>()
                    scenario.onActivity { activity -> activity.lifecycleScope.launch {
                        val nav = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                        val raw = nav.evaluateJavascript("""(function(){const p=Array.from(document.querySelectorAll('p')).filter(p=>p.textContent.trim());const r=document.createRange();r.setStart(p[0],0);r.setEnd(p[1],p[1].childNodes.length);const s=window.getSelection();s.removeAllRanges();s.addRange(r);return s.toString();})()""")
                        selected.complete((JSONTokener(raw).nextValue() as String).trim())
                    } }
                    val original = selected.await()
                    assertTrue(original.contains('\n'))
                    assertTrue(device.wait(Until.hasObject(By.desc("Выделенный фрагмент").textContains("Mira was curious")), 5000))
                    assertFalse(device.hasObject(By.desc("Сохранить слово")))
                    return original
                }
                val original = select()
                scenario.onActivity { activity ->
                    val field = ReaderActivity::class.java.getDeclaredField("popup").apply { isAccessible = true }
                    val popup = field.get(activity) as android.widget.PopupWindow
                    fun buttons(view: View): List<android.widget.Button> = when (view) {
                        is android.widget.Button -> if (view.isShown) listOf(view) else emptyList()
                        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                        else -> emptyList()
                    }
                    val actions = buttons(popup.contentView).filter { it.text.toString() in listOf("Перевести", "Заметка", "Цитата") }
                    assertEquals(3, actions.size)
                    actions.forEach { button ->
                        val layout = requireNotNull(button.layout)
                        assertEquals("${button.text} must stay on one line", 1, layout.lineCount)
                        assertEquals("${button.text} must not be shortened", 0, layout.getEllipsisCount(0))
                        assertTrue("${button.text} must fit without clipping", layout.getLineWidth(0) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1)
                        val bounds = android.graphics.Rect()
                        assertTrue(button.getGlobalVisibleRect(bounds))
                        assertEquals("Action must remain fully visible", button.width, bounds.width())
                        assertEquals("Action must remain fully visible", button.height, bounds.height())
                    }
                }
                capture("quote-actions")
                InstrumentationRegistry.getArguments().getString("layoutCase")?.let { capture("quote-actions-$it") }
                device.findObject(By.desc("Добавить заметку")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Заметка к фрагменту")), 5000))
                assertFalse(device.editorAction("Сохранить").isEnabled)
                click("Отмена")
                assertTrue(app.repository.dao.allBookmarks().none { it.bookHash == book.hash })
                select(); device.findObject(By.desc("Сохранить цитату")).click()
                repeat(30) { if (app.repository.dao.allBookmarks().none { it.bookHash == book.hash }) delay(100) }
                val initial = app.repository.dao.allBookmarks().single { it.bookHash == book.hash }
                assertEquals(original, initial.quote); assertEquals(book.title, initial.bookTitle); assertEquals(book.author, initial.author)
                click("Отменить")
                repeat(30) { if (app.repository.dao.allBookmarks().any { it.bookHash == book.hash }) delay(100) }
                assertTrue(app.repository.dao.allBookmarks().none { it.bookHash == book.hash })
                select(); device.findObject(By.desc("Сохранить цитату")).click()
                repeat(30) { if (app.repository.dao.allBookmarks().none { it.bookHash == book.hash }) delay(100) }
                val first = app.repository.dao.allBookmarks().single { it.bookHash == book.hash }
                select(); device.findObject(By.desc("Сохранить цитату")).click(); delay(400)
                assertEquals(1, app.repository.dao.allBookmarks().count { it.bookHash == book.hash })
                select(); device.findObject(By.desc("Добавить заметку")).click()
                device.findObject(By.clazz("android.widget.ScrollView"))?.scroll(Direction.DOWN, .9f)
                device.wait(Until.findObject(By.desc("Ваша заметка")), 5000).text = "Моя мысль об этих абзацах"
                capture("quote-note")
                click("Сохранить")
                repeat(30) { if (app.repository.dao.bookmarkById(first.id)?.note.isNullOrEmpty()) delay(100) }
                val annotated = requireNotNull(app.repository.dao.bookmarkById(first.id))
                assertEquals("Моя мысль об этих абзацах", annotated.note); assertEquals(original, annotated.quote)
                assertEquals(1, app.repository.dao.allBookmarks().count { it.bookHash == book.hash })
                assertTrue(app.repository.dao.allWords().none { it.bookHash == book.hash })
                device.findObject(By.desc("Оглавление")).click(); click("The Open Gate")
                repeat(40) { if (app.repository.dao.book(book.id)?.locator?.contains("chapter1") != true) delay(100) }
                assertTrue(app.repository.dao.book(book.id)?.locator?.contains("chapter1") == true)
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                var restored = false
                repeat(40) {
                    if (!restored) {
                        scenario.onActivity { activity -> restored = (activity.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment)?.currentLocator?.value?.href?.toString()?.contains("chapter1") == true }
                        delay(100)
                    }
                }
                assertTrue("Rotation must preserve the current chapter, not the initial launch target", restored)
                device.waitForIdle()
                device.wait(Until.findObject(By.desc("Закладки книги")), 10000).click()
                click("Заметки")
                device.waitForIdle()
                device.wait(Until.findObject(By.textContains("Mira was curious")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("Сохранённый момент")), 5000))
                repeat(4) {
                    if (!device.hasObject(By.text("К месту в книге"))) {
                        val bounds = device.findObject(By.clazz("android.widget.ScrollView")).visibleBounds
                        val gutter = (8 * app.resources.displayMetrics.density).toInt()
                        // Scroll the editor's gutter, so an editable excerpt does not consume the drag.
                        device.swipe(bounds.left + gutter, bounds.bottom - gutter, bounds.left + gutter, bounds.top + gutter, 30)
                        device.waitForIdle()
                    }
                }
                capture("quote-source-action")
                click("К месту в книге")
                delay(500)
                capture("quote-source-after-click")
                repeat(40) { if (app.repository.dao.book(book.id)?.locator?.contains("chapter2") != true) delay(100) }
                assertTrue(app.repository.dao.book(book.id)?.locator?.contains("chapter2") == true)
                assertEquals(original, app.repository.dao.bookmarkById(first.id)?.quote)
                val rendered = CompletableDeferred<Int>()
                scenario.onActivity { activity -> activity.lifecycleScope.launch {
                    val nav = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                    var count = 0
                    repeat(30) { if (count == 0) { count = nav.evaluateJavascript("document.querySelectorAll('[data-style]').length")?.toIntOrNull() ?: 0; delay(100) } }
                    rendered.complete(count)
                } }
                assertTrue("Saved multi-paragraph highlight was not restored", rendered.await() > 0)
                capture("quote-restored")
            }
        } finally {
            app.repository.dao.allBookmarks().filter { it.bookHash == book.hash }.forEach { app.repository.dao.delete(it) }
            app.repository.dao.allWords().filter { it.bookHash == book.hash }.forEach { app.repository.dao.delete(it) }
            app.repository.remove(book)
            app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM ReadingState WHERE hash = ?", arrayOf(book.hash))
            file.delete()
        }
    }

    private fun capture(name: String) {
        device.waitForIdle(); instrument.waitForIdleSync(); Thread.sleep(300)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name-${app.themes.current.value.name.lowercase()}.png")
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
