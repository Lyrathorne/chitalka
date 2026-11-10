package ru.chitalka.app

import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*
import ru.chitalka.app.reader.ReaderActivity
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookmarkUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private fun click(text: String) { device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(text), java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE))), 5000).click() }

    @Test fun filterSearchEditCopyAndDeleteWithUndo() = runBlocking<Unit> {
        val id = UUID.randomUUID().toString()
        val a = Bookmark("a-$id", "a".repeat(64), "Alpha test book", "Author Alpha", """{"href":"chapter.xhtml"}""", "Alpha test quote", "alpha-note-$id")
        val b = Bookmark("b-$id", "b".repeat(64), "Beta test book", "Author Beta", """{"href":"chapter.xhtml","title":"Chapter Beta"}""", "Beta test quote", "beta-note-$id")
        app.repository.dao.insert(a); app.repository.dao.insert(b)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                device.wait(Until.findObject(By.desc("Понравившиеся моменты")), 10000).click()
                device.wait(Until.findObject(By.desc("Фильтр закладок")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("Все книги")), 5000))
                device.findObjects(By.text("Beta test book")).last().click()
                assertTrue(device.wait(Until.hasObject(By.text(b.quote)), 5000))
                assertFalse(device.hasObject(By.text(a.quote)))
                capture("bookmarks-filter.png")
                val query = device.searchField()
                query.text = "beta-note"
                assertTrue(device.wait(Until.hasObject(By.text(b.quote)), 5000))
                click(b.quote)
                assertTrue(device.wait(Until.hasObject(By.textContains("Добавьте исходную книгу")), 5000))
                click("Заметка и действия")
                val input = device.wait(Until.findObject(By.text(b.note).clazz("android.widget.EditText")), 5000)
                input.text = "Updated bookmark note"
                click("Сохранить")
                repeat(20) { if (app.repository.dao.bookmarkById(b.id)?.note != "Updated bookmark note") delay(100) }
                assertEquals("Updated bookmark note", app.repository.dao.bookmarkById(b.id)?.note)
                query.text = "Beta test quote"
                click("Заметка и действия"); capture("bookmark-note.png"); click("Копировать")
                var copied = ""
                instrument.runOnMainSync { copied = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
                assertEquals(quoteText(b), copied)
                val share = quoteShareIntent(b)
                assertEquals(Intent.ACTION_SEND, share.action); assertEquals("text/plain", share.type)
                assertEquals(quoteText(b), share.getStringExtra(Intent.EXTRA_TEXT))
                assertTrue(copied.contains(b.author)); assertTrue(copied.contains(b.bookTitle)); assertFalse(copied.contains(b.note))
                click("Поделиться")
                repeat(40) { if (device.currentPackageName == "ru.chitalka.app") delay(100) }
                assertNotEquals("System share chooser did not open", "ru.chitalka.app", device.currentPackageName)
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("Сохранённый момент")), 5000))
                click("Удалить"); click("Отмена")
                assertNotNull(app.repository.dao.bookmarkById(b.id))
                click("Удалить")
                device.wait(Until.findObject(By.text("Удалить момент?")), 5000)
                device.findObjects(By.text("Удалить")).last().click()
                device.wait(Until.findObject(By.text("Отменить")), 5000).click()
                repeat(20) { if (app.repository.dao.bookmarkById(b.id) == null) delay(100) }
                assertEquals("Updated bookmark note", app.repository.dao.bookmarkById(b.id)?.note)
            }
        } finally { app.repository.dao.delete(a); app.repository.dao.delete(b) }
    }

    @Test fun bookListEditsNoteNavigatesAndReturnsAfterRecreate() = runBlocking<Unit> {
        val file = File(app.cacheDir, "bookmark-ui-${UUID.randomUUID()}.epub")
        app.assets.open("sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = app.repository.import(Uri.fromFile(file))
        val first = """{"href":"EPUB/chapter1.xhtml","type":"application/xhtml+xml"}"""
        val mark = Bookmark(UUID.randomUUID().toString(), book.hash, book.title, book.author,
            """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml","title":"Chapter Two"}""", "Bookmark navigation fixture", "Old bookmark note")
        app.repository.dao.insert(mark)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Закладки книги")), 10000))
                suspend fun at(chapter: String) {
                    repeat(50) { if (app.repository.dao.book(book.id)?.locator?.contains(chapter) != true) delay(100) }
                    assertTrue("Chapter $chapter was not reached", app.repository.dao.book(book.id)?.locator?.contains(chapter) == true)
                }
                at("chapter1")
                fun openMark() {
                    device.findObject(By.desc("Закладки книги")).click()
                    device.wait(Until.findObject(By.textContains(mark.quote)), 5000).click()
                }
                openMark(); click("Заметка")
                device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000).text = "Reader note updated"
                click("Сохранить")
                repeat(20) { if (app.repository.dao.bookmarkById(mark.id)?.note != "Reader note updated") delay(100) }
                assertEquals("Reader note updated", app.repository.dao.bookmarkById(mark.id)?.note)
                openMark(); click("К месту в книге"); at("chapter2")
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.desc("Закладки книги")), 10000))
                device.findObject(By.desc("Закладки книги")).click()
                click("Назад к чтению"); at("chapter1")
            }
        } finally { app.repository.dao.delete(mark); file.delete() }
    }

    private fun capture(name: String) {
        device.waitForIdle(); instrument.waitForIdleSync(); Thread.sleep(300)
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
