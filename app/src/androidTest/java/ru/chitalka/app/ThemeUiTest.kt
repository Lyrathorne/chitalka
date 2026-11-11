package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.provider.MediaStore
import android.content.ContentValues
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import ru.chitalka.app.data.*
import ru.chitalka.app.reader.ReaderActivity
import ru.chitalka.app.ui.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ThemeUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private fun click(text: String) = device.wait(Until.findObject(By.text(text)), 5000).click()
    private fun capture(name: String) {
        device.waitForIdle(); instrument.waitForIdleSync(); Thread.sleep(350)
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, testPictureName(name)); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChitalkaTests") }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri -> app.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            bitmap.recycle()
        }
    }

    @Test fun readerThemeChoicePreservesChapterAndAppliesGlobally() = runBlocking<Unit> {
        val previous = app.themes.current.value
        val next = if (previous == AppTheme.PAPER) AppTheme.NIGHT else AppTheme.PAPER
        val file = File(app.cacheDir, "theme-reader-fixture.epub")
        app.assets.open("sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = app.repository.import(Uri.fromFile(file))
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                repeat(40) { if (app.repository.dao.book(book.id)?.locator?.contains("chapter2") != true) delay(100) }
                device.findObject(By.desc("Настройки чтения")).click()
                click("Оформление · ${previous.title}"); click(next.title)
                var restored = false
                repeat(50) {
                    if (!restored) {
                        scenario.onActivity { activity ->
                            val dark = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                            val nav = activity.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment
                            restored = dark == (next == AppTheme.NIGHT) && nav?.currentLocator?.value?.href?.toString()?.contains("chapter2") == true
                        }
                        delay(100)
                    }
                }
                assertTrue("Theme recreation lost the reader chapter", restored)
                assertEquals(next, app.themes.current.value)
                device.findObject(By.desc("Настройки чтения")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Оформление · ${next.title}")), 5000))
                device.pressBack()
            }
        } finally { withContext(Dispatchers.Main) { app.themes.select(previous) }; file.delete() }
    }

    @Test fun themesApplyToLibraryWordsPracticeReaderAndUtilityScreens() = runBlocking<Unit> {
        val previous = app.themes.current.value
        val file = File(app.cacheDir, "theme-fixture.epub")
        app.assets.open("sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val real = app.repository.import(Uri.fromFile(file))
        val fixtures = listOf("The Open Gate", "A Place to Return").mapIndexed { index, title -> real.copy(id = UUID.randomUUID().toString(), hash = (if (index == 0) "1" else "2").repeat(64), title = title, author = "Chitalka", lastRead = 0) }
        val words = listOf("curious" to "любопытный", "wonder" to "удивляться", "quietly" to "тихо", "glimpse" to "взгляд").map { (original, translation) ->
            Word(UUID.randomUUID().toString(), original, UUID.randomUUID().toString(), translation, "A ${original} moment in the quiet garden.", real.hash, real.title, """{"href":"EPUB/chapter1.xhtml","type":"application/xhtml+xml"}""", source = "Тестовая запись")
        }
        fixtures.forEach { app.repository.dao.insert(it) }; words.forEach { app.repository.dao.insert(it) }
        try {
            for (theme in AppTheme.entries) {
                ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                    device.wait(Until.findObject(By.desc("Меню библиотеки")), 10000).click(); click("Оформление"); click(theme.title)
                    device.waitForIdle()
                    device.findObject(By.text("Готово"))?.click()
                    assertTrue(device.wait(Until.hasObject(By.text("Мои книги")), 10000))
                    val disk = ThemeSettings(app); disk.load(); assertEquals(theme, disk.current.value)
                    capture("${theme.name.lowercase()}-library.png")
                    click("Слова"); assertTrue(device.wait(Until.hasObject(By.text("Мои слова")), 5000)); capture("${theme.name.lowercase()}-words.png")
                    click("Практика"); capture("${theme.name.lowercase()}-practice.png")
                }
                ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", real.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                    assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                    device.findObject(By.text(java.util.regex.Pattern.compile("Понятно", java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE)))?.click()
                    val color = CompletableDeferred<String?>()
                    scenario.onActivity { activity -> activity.lifecycleScope.launch {
                        var nav: EpubNavigatorFragment? = null
                        repeat(40) { if (nav == null) { nav = activity.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment; delay(100) } }
                        color.complete(nav?.evaluateJavascript("getComputedStyle(document.body).color"))
                    } }
                    val expected = if (theme == AppTheme.NIGHT) "rgb(242,238,223)" else "rgb(41,40,32)"
                    assertEquals(expected, color.await()?.replace("\"", "")?.replace(" ", ""))
                    capture("${theme.name.lowercase()}-reader.png")
                    device.findObject(By.desc("Настройки чтения")).click(); capture("${theme.name.lowercase()}-reader-settings.png"); device.pressBack()
                }
                ActivityScenario.launch(BackupActivity::class.java).use { assertTrue(device.wait(Until.hasObject(By.text("Копии и перенос")), 10000)); capture("${theme.name.lowercase()}-backup.png") }
                ActivityScenario.launch(ModelDownloadActivity::class.java).use { assertTrue(device.wait(Until.hasObject(By.text("Переводчик")), 10000)); capture("${theme.name.lowercase()}-model.png") }
                val p = theme.palette
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(p.ink, p.background) >= 7)
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(p.accent, p.background) >= 4.5)
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(p.onAccent, p.accent) >= 4.5)
            }
        } finally {
            fixtures.forEach { app.repository.dao.delete(it) }; words.forEach { app.repository.dao.delete(it) }; file.delete()
            withContext(Dispatchers.Main) { app.themes.select(previous) }
        }
    }
}
