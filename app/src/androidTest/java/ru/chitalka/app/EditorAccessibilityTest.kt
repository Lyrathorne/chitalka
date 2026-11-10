package ru.chitalka.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.Bookmark
import ru.chitalka.app.reader.ReaderActivity
import java.util.UUID

@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class EditorAccessibilityTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrument)
    private fun click(text: String) = device.clickEditorAction(text)
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    @Test fun nativeAndGlobalEditorsKeepAllActionsReachableAndSaveData() = runBlocking<Unit> {
        val (book, file) = feedbackBook(app)
        val note = Bookmark(UUID.randomUUID().toString(), book.hash, book.title, book.author,
            """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""",
            "An accessible quotation. ".repeat(35).trim(), "Large font note")
        app.repository.dao.insert(note)
        val variant = "${app.themes.current.value.name.lowercase()}-${app.resources.configuration.fontScale}"
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Закладки книги")), 10000))
                scenario.readerPoint("Mira")
                device.findObject(By.desc("Закладки книги")).click(); click("Заметки")
                device.wait(Until.findObject(By.textContains("An accessible quotation.")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("Сохранённый момент")), 5000))
                instrument.runOnMainSync {
                    val root = WindowInspector.getGlobalWindowViews().first { views(it).any { v -> v.contentDescription == "Сохранить момент" } }
                    val buttons = views(root).filterIsInstance<Button>().filter { it.isShown }
                    assertEquals(setOf("К месту в книге", "Копировать", "Поделиться", "Сохранить", "Закрыть", "Удалить"), buttons.map { it.text.toString() }.toSet())
                    buttons.forEach { b ->
                        assertEquals("${b.text} wraps", 1, b.layout.lineCount)
                        assertEquals(0, b.layout.getEllipsisCount(0))
                        assertTrue("${b.text} clips", b.layout.getLineWidth(0) <= b.width - b.compoundPaddingLeft - b.compoundPaddingRight + 1)
                        assertTrue(b.height >= (48 * b.resources.displayMetrics.density).toInt())
                    }
                }
                listOf("К месту в книге", "Копировать", "Поделиться", "Сохранить", "Закрыть", "Удалить").forEach { device.editorAction(it) }
                feedbackCapture("editor-native-actions-$variant.png")
                click("Копировать")
                click("Поделиться")
                repeat(40) { if (device.currentPackageName == "ru.chitalka.app") delay(100) }
                assertNotEquals("ru.chitalka.app", device.currentPackageName)
                device.pressBack()
                val nativeInput = device.editorAction("Large font note")
                nativeInput.click(); nativeInput.text = "Unsaved native draft"
                if (InstrumentationRegistry.getArguments().getString("keyboardPhase") == "1") {
                    val keyboard = device.executeShellCommand("settings get secure default_input_method").trim().substringBefore('/')
                    assertTrue("Native editor did not show the keyboard", device.wait(Until.hasObject(By.pkg(keyboard)), 5000))
                }
                click("Закрыть")
                assertEquals(note, app.repository.dao.bookmarkById(note.id))
            }
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                device.wait(Until.findObject(By.desc("Понравившиеся моменты")), 10000).click(); click("Заметки")
                device.wait(Until.findObject(By.textContains("An accessible quotation.")), 5000).click()
                assertTrue(device.wait(Until.hasObject(By.text("Сохранённый момент")), 5000))
                listOf("Копировать", "Поделиться", "К месту в книге", "Сохранить", "Закрыть", "Удалить").forEach { device.editorAction(it) }
                feedbackCapture("editor-global-actions-$variant.png")
                click("Удалить"); click("Отмена")
                assertNotNull(app.repository.dao.bookmarkById(note.id))
                device.waitForIdle(); delay(400)
                val globalInput = device.editorAction("Large font note")
                globalInput.click(); globalInput.text = "Saved global edit"
                if (InstrumentationRegistry.getArguments().getString("keyboardPhase") == "1") {
                    val keyboard = device.executeShellCommand("settings get secure default_input_method").trim().substringBefore('/')
                    assertTrue("Global editor did not show the keyboard", device.wait(Until.hasObject(By.pkg(keyboard)), 5000))
                }
                device.editorAction("Сохранить")
                feedbackCapture("editor-keyboard-save-$variant.png")
                click("Сохранить")
                assertTrue(device.wait(Until.gone(By.text("Сохранённый момент")), 5000))
                assertEquals(note.quote, app.repository.dao.bookmarkById(note.id)?.quote)
                assertEquals("Saved global edit", app.repository.dao.bookmarkById(note.id)?.note)
                assertEquals(note.locator, app.repository.dao.bookmarkById(note.id)?.locator)
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
}
