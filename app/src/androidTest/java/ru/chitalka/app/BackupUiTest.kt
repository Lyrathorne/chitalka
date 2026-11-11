package ru.chitalka.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.Word
import ru.chitalka.app.data.Book
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private val ignoreCase = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    @Test fun duplicateBookOffersReaderAndLibrarySortsByTitleAndAuthor() = runBlocking<Unit> {
        val id = UUID.randomUUID().toString()
        val sample = File(app.cacheDir, "duplicate-$id.epub")
        app.assets.open("sample.epub").use { input -> sample.outputStream().use { input.copyTo(it) } }
        app.repository.import(Uri.fromFile(sample))
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val alpha = Book("alpha-$id", hash("alpha-$id"), "Sort test Alpha", "Z test", "", lastRead = 1)
        val beta = Book("beta-$id", hash("beta-$id"), "Sort test Beta", "A test", "", lastRead = 2)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("Мои книги")), 10000))
                val before = app.repository.dao.allBooks().size
                scenario.onActivity { activity -> ViewModelProvider(activity)[LibraryViewModel::class.java].import(Uri.fromFile(sample)) }
                assertTrue(device.wait(Until.hasObject(By.text("Книга уже добавлена")), 10000))
                assertEquals(before, app.repository.dao.allBooks().size)
                device.findObject(By.text("Открыть книгу")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                device.pressBack()
                app.repository.dao.insert(alpha); app.repository.dao.insert(beta)
                val search = device.searchField()
                search.text = "Sort test"
                device.findObject(By.desc("Меню библиотеки")).click()
                device.wait(Until.findObject(By.text("По названию")), 5000).click()
                val found = device.wait(Until.hasObject(By.textContains("Sort test Alpha")), 5000)
                if (!found) { screenshot("sort-theme-debug.png"); device.dumpWindowHierarchy(File(app.getExternalFilesDir(null), "sort-theme-ui.xml")) }
                assertTrue("Filtered book is not visible after sorting", found)
                device.waitForIdle()
                assertTrue(device.findObject(By.textContains("Sort test Alpha")).visibleBounds.top < device.findObject(By.textContains("Sort test Beta")).visibleBounds.top)
                device.findObject(By.desc("Меню библиотеки")).click()
                device.wait(Until.findObject(By.text("По автору")), 5000).click()
                device.waitForIdle()
                assertTrue(device.findObject(By.textContains("Sort test Beta")).visibleBounds.top < device.findObject(By.textContains("Sort test Alpha")).visibleBounds.top)
            }
        } finally { app.repository.dao.delete(alpha); app.repository.dao.delete(beta); sample.delete() }
    }

    @Test fun editExportPreviewAndMergeThroughSystemFilePicker() = runBlocking<Unit> {
        val id = UUID.randomUUID().toString()
        val fileName = "chitalka-ui-${id.take(8)}.json"
        val word = Word(id, "lantern", "lantern-$id", "фонарь", "A lantern lit the path.", "d".repeat(64), "Демонстрационная книга",
            """{"href":"chapter.xhtml","type":"application/xhtml+xml"}""")
        app.repository.dao.insert(word)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                assertTrue(device.wait(Until.hasObject(By.text("Слова")), 10000))
                device.findObject(By.text("Слова")).click()
                assertTrue(device.wait(Until.hasObject(By.text("lantern")), 5000))
                device.findObject(By.text("lantern")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Перевод")), 5000))
                val fields = device.findObjects(By.clazz("android.widget.EditText"))
                assertEquals(2, fields.size)
                fields[0].text = "светильник"
                screenshot("word-editor.png")
                device.findObject(By.text("Сохранить")).click()
                withTimeout(5000) { while (app.repository.dao.wordById(id)?.translation != "светильник") delay(100) }
                val countBefore = app.repository.dao.allWords().size
                device.findObject(By.desc("Меню библиотеки")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Копии и перенос")), 5000))
                device.findObject(By.text("Копии и перенос")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Сохранить резервную копию")), 5000))
                screenshot("backup-screen.png")
                device.findObject(By.text("Сохранить резервную копию")).click()
                saveDocument(fileName)
                assertTrue(device.wait(Until.hasObject(By.text("Резервная копия сохранена")), 10000))
                device.findObject(By.text("Восстановить из JSON")).click()
                openDocument(fileName)
                assertTrue(device.wait(Until.hasObject(By.text("Объединить данные?")), 10000))
                screenshot("backup-preview.png")
                device.findObject(By.text("Отмена")).click()
                assertEquals(countBefore, app.repository.dao.allWords().size)
                device.findObject(By.text("Восстановить из JSON")).click()
                openDocument(fileName)
                assertTrue(device.wait(Until.hasObject(By.text("Объединить данные?")), 10000))
                device.findObject(By.text("Объединить")).click()
                assertTrue(device.wait(Until.hasObject(By.textContains("Добавлено слов:")), 10000))
                assertEquals(countBefore, app.repository.dao.allWords().size)
                assertEquals("светильник", app.repository.dao.wordById(id)?.translation)
                device.findObject(By.text("Экспорт слов в CSV")).click()
                saveDocument(fileName.removeSuffix(".json") + ".csv")
                assertTrue(device.wait(Until.hasObject(By.text("Слова сохранены в CSV")), 10000))
                device.pressBack()
            }
        } finally { app.repository.dao.delete(word) }
    }

    private fun downloadsRoot() {
        device.findObject(By.desc(Pattern.compile("Show roots|Показать корни|Открыть меню", ignoreCase)))?.click()
        device.waitForIdle()
        val root = By.res("android:id/title").text(Pattern.compile("Downloads|Загрузки", ignoreCase))
        device.wait(Until.hasObject(root), 2000)
        device.findObject(root)?.click()
        device.wait(Until.gone(root), 5000)
    }
    private fun saveDocument(name: String) {
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10000))
        downloadsRoot()
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
        assertNotNull("Filename field missing", input)
        input.text = name
        val save = device.findObject(By.res("com.android.documentsui:id/action_menu_save"))
            ?: device.findObject(By.text(Pattern.compile("Save|Сохранить", ignoreCase)))
        assertNotNull("System Save action missing", save)
        save.click()
    }
    private fun openDocument(name: String) {
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10000))
        downloadsRoot()
        if (!device.hasObject(By.text(name))) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(name)
        val item = device.wait(Until.findObject(By.text(name)), 5000)
        assertNotNull("Exported document missing", item)
        item.click()
    }
    private fun screenshot(name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, testPictureName(name))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChitalkaTests")
        }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                app.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }
}
