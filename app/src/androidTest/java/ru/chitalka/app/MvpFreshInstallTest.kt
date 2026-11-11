package ru.chitalka.app

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import ru.chitalka.app.data.ModelPhase
import ru.chitalka.app.reader.ReaderActivity
import java.util.regex.Pattern

/** Dedicated package IDs only: never clear or uninstall the user's application. */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class MvpFreshInstallTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private val args get() = InstrumentationRegistry.getArguments()
    private val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    private fun click(text: String) = device.clickEditorAction(text)
    private fun insensitive(text: String) = By.text(Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE))
    private fun downloads() {
        device.waitForIdle(); Thread.sleep(400)
        device.findObject(By.desc(Pattern.compile("Show roots|Показать корни|Открыть меню", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)))?.click()
        device.waitForIdle()
        val root = device.findObject(UiSelector().resourceId("android:id/title").textMatches("(?i)Downloads|Загрузки"))
        if (root.waitForExists(3000)) root.click()
        device.waitForIdle()
    }
    private fun openFile(name: String) {
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10000)); downloads()
        if (!device.hasObject(By.text(name))) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(name)
        device.wait(Until.findObject(By.text(name)), 5000).click()
    }
    private suspend fun emptyOfflinePackage() {
        assertTrue(app.packageName.endsWith(".mvpcheck") || app.packageName.endsWith(".mvprestore"))
        assertTrue(app.repository.dao.allBooks().isEmpty()); assertTrue(app.repository.dao.allWords().isEmpty())
        assertNull(app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        withTimeout(10000) { while (app.modelDownloads.state.value.phase == ModelPhase.CHECKING) delay(100) }
        assertEquals(ModelPhase.MISSING, app.modelDownloads.state.value.phase)
    }
    private suspend fun importThroughPicker(name: String) {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(flags)).use {
            device.wait(Until.findObject(By.desc("Добавить EPUB")), 10000).click(); openFile(name)
            assertTrue(device.wait(Until.hasObject(By.text("The Quiet Garden")), 15000))
        }
        withTimeout(10000) { while (app.repository.dao.allBooks().isEmpty()) delay(100) }
    }

    @Test fun freshOfflineReadingDictionaryMomentsPracticeAndSafBackup() = runBlocking<Unit> {
        assumeTrue(args.getString("mvpPhase") == "fresh")
        emptyOfflinePackage()
        val epubName = args.getString("epubName")!!
        val backupName = args.getString("backupName")!!
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, epubName); put(MediaStore.Downloads.MIME_TYPE, "application/epub+zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val epub = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        app.contentResolver.openOutputStream(epub)!!.use { output -> app.assets.open("sample.epub").use { it.copyTo(output) } }
        importThroughPicker(epubName)
        val book = app.repository.dao.allBooks().single()
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(flags)).use { scenario ->
            device.wait(Until.findObject(insensitive("Понятно")), 10000).click()
            val point = scenario.readerPoint("curious"); delay(300)
            device.swipe(point.first, point.second, point.first, point.second, 150)
            device.wait(Until.findObject(By.desc("Перевести выделенное")), 5000).click()
            device.wait(Until.findObject(By.desc("Словарные значения")), 15000).click()
            device.wait(Until.findObject(By.textContains("любопыт")), 5000).click()
            device.wait(Until.findObject(By.desc("Сохранить слово")), 5000).click()
            device.wait(Until.findObject(By.desc("Сохранить цитату")), 5000).click()
            withTimeout(5000) { while (app.repository.dao.allBookmarks().isEmpty()) delay(100) }
            val notePoint = scenario.readerPoint("gate"); delay(300)
            device.swipe(notePoint.first, notePoint.second, notePoint.first, notePoint.second, 150)
            device.wait(Until.findObject(By.desc("Добавить заметку")), 5000).click()
            device.wait(Until.findObject(By.desc("Ваша заметка")), 5000).text = "MVP offline note"
            click("Сохранить")
            withTimeout(5000) { while (app.repository.dao.allBookmarks().size != 2) delay(100) }
            feedbackCapture("mvp-offline-reading.png")
        }
        assertTrue(app.repository.dao.allWords().single().source.contains("FreeDict"))
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(flags)).use {
            device.wait(Until.findObject(By.text("Практика")), 10000).click(); click("Начать повторение")
            click("Показать ответ"); click("Помню")
            assertTrue(device.wait(Until.hasObject(By.text("Повторение завершено")), 5000))
            feedbackCapture("mvp-offline-practice.png")
            assertEquals(1, app.repository.dao.allReviews().size)
            device.findObject(By.desc("Меню библиотеки")).click(); click("Копии и перенос")
            click("Сохранить резервную копию")
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10000)); downloads()
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000).text = backupName
            device.waitForIdle(); delay(500)
            val save = device.wait(Until.findObject(By.res("com.android.documentsui:id/action_menu_save").enabled(true)), 5000)
                ?: device.wait(Until.findObject(By.text(Pattern.compile("Save|Сохранить", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)).enabled(true)), 5000)
            save.click()
            if (!device.wait(Until.gone(By.pkg("com.android.documentsui")), 3000)) {
                // The picker can first consume a tap to finish editing the filename.
                device.waitForIdle()
                device.findObject(By.res("com.android.documentsui:id/action_menu_save").enabled(true))?.click()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Резервная копия сохранена")), 10000))
        }
        assertEquals(ModelPhase.MISSING, app.modelDownloads.state.value.phase)
    }

    @Test fun restoreIntoAnotherFreshInstallationAndReimportOriginalEpub() = runBlocking<Unit> {
        assumeTrue(args.getString("mvpPhase") == "restore")
        emptyOfflinePackage()
        ActivityScenario.launch<BackupActivity>(Intent(app, BackupActivity::class.java).addFlags(flags)).use {
            click("Восстановить из JSON"); openFile(args.getString("backupName")!!)
            click("Объединить")
            withTimeout(10000) { while (app.repository.dao.allWords().size != 1) delay(100) }
            assertEquals(2, app.repository.dao.allBookmarks().size); assertEquals(1, app.repository.dao.allReviews().size)
            assertTrue(app.repository.dao.allBookmarks().any { it.note == "MVP offline note" })
            assertTrue(app.repository.dao.allWords().single().translation.contains("любопыт"))
            feedbackCapture("mvp-fresh-restore.png")
        }
        importThroughPicker(args.getString("epubName")!!)
        val book = app.repository.dao.allBooks().single()
        assertTrue(book.locator.orEmpty().contains("chapter2"))
        assertEquals(book.hash, app.repository.dao.allWords().single().bookHash)
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(flags)).use { scenario ->
            device.wait(Until.findObject(insensitive("Понятно")), 10000).click()
            scenario.readerPoint("Mira")
            withTimeout(10000) { while ((scenario.readerJs("document.querySelectorAll('[data-style]').length")?.toIntOrNull() ?: 0) < 2) delay(100) }
            scenario.recreate(); delay(1500); scenario.readerPoint("Mira")
            feedbackCapture("mvp-restored-moments.png")
        }
    }

    @Test fun readerSurvivesForceStop() = runBlocking<Unit> {
        assumeTrue(args.getString("mvpPhase") == "reader-resume")
        val book = app.repository.dao.allBooks().single()
        assertTrue(book.locator.orEmpty().contains("chapter2"))
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(flags)).use { scenario ->
            device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000)
            scenario.readerPoint("Mira")
            scenario.onActivity { assertTrue((it.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment).currentLocator.value.href.toString().contains("chapter2")) }
            withTimeout(10000) { while ((scenario.readerJs("document.querySelectorAll('[data-style]').length")?.toIntOrNull() ?: 0) < 2) delay(100) }
        }
    }
}
