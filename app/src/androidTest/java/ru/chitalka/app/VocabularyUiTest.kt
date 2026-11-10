package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.Word
import ru.chitalka.app.reader.ReaderActivity
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VocabularyUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun searchMissingBookAndConfirmedDeletion() = runBlocking<Unit> {
        val word = Word(UUID.randomUUID().toString(), "wayfarer", UUID.randomUUID().toString(), "странник", "A wayfarer crossed the bridge.", "e".repeat(64), "Missing source",
            """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""")
        app.repository.dao.insert(word)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                device.wait(Until.findObject(By.text("Слова")), 10000).click()
                val search = device.searchField()
                search.text = "wayfarer"
                assertTrue(device.wait(Until.hasObject(By.text("wayfarer")), 5000))
                search.text = "странник"
                assertTrue(device.wait(Until.hasObject(By.text("wayfarer")), 5000))
                device.findObject(By.desc("Действия со словом wayfarer")).click()
                device.wait(Until.findObject(By.text("К месту в книге")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.textContains("Контекст сохранён")), 5000))
                assertTrue(device.hasObject(By.text(word.context)))
                device.findObject(By.desc("Действия со словом wayfarer")).click()
                device.wait(Until.findObject(By.text("Удалить слово")), 5000).click()
                device.wait(Until.findObject(By.text("Отмена")), 5000).click()
                assertNotNull(app.repository.dao.wordById(word.id))
                device.findObject(By.desc("Действия со словом wayfarer")).click()
                device.wait(Until.findObject(By.text("Удалить слово")), 5000).click()
                device.wait(Until.findObject(By.text("Удалить")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("Слово не найдено")), 5000))
                assertNull(app.repository.dao.wordById(word.id))
            }
        } finally { app.repository.dao.delete(word) }
    }

    @Test fun vocabularyOpensSavedChapter() = runBlocking<Unit> {
        val sample = File(app.cacheDir, "vocabulary-test.epub")
        app.assets.open("sample.epub").use { input -> sample.outputStream().use { input.copyTo(it) } }
        val book = app.repository.import(Uri.fromFile(sample))
        val word = Word(UUID.randomUUID().toString(), "waymark", UUID.randomUUID().toString(), "ориентир", "Test source context", book.hash, book.title,
            """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""")
        app.repository.dao.insert(word)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                device.wait(Until.findObject(By.text("Слова")), 10000).click()
                device.searchField().text = "waymark"
                device.wait(Until.findObject(By.desc("Действия со словом waymark")), 5000).click()
                device.wait(Until.findObject(By.text("К месту в книге")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                var reached = false
                repeat(40) {
                    if (!reached) { reached = app.repository.dao.book(book.id)?.locator?.contains("chapter2") == true; delay(100) }
                }
                assertTrue("Saved chapter was not opened", reached)
                device.pressBack()
            }
        } finally { app.repository.dao.delete(word); sample.delete() }
    }
}
