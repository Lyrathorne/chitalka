package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.json.JSONObject
import ru.chitalka.app.data.*
import ru.chitalka.app.reader.ReaderActivity
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class ReaderIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ChitalkaApp

    private suspend fun sample(): Book {
        val file = File(app.cacheDir, "integration.epub")
        app.assets.open("sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        return app.repository.import(Uri.fromFile(file))
    }

    @Test fun importDeduplicatesAndBookmarksSurviveBookRemoval() = runBlocking {
        val repo = app.repository
        val first = sample()
        val duplicate = sample()
        assertEquals(first.id, duplicate.id)
        assertEquals("The Quiet Garden", first.title)
        val pub = repo.open(File(first.path))
        assertEquals(2, pub.readingOrder.size)
        pub.close()
        val mark = Bookmark(UUID.randomUUID().toString(), first.hash, first.title, first.author,
            "test-locator-${UUID.randomUUID()}", "A good story gives you a place to return to.", "Любимая мысль")
        assertTrue(repo.dao.insert(mark) != -1L)
        assertEquals(-1L, repo.dao.insert(mark.copy(id = UUID.randomUUID().toString())))
        repo.remove(first)
        assertTrue(repo.dao.bookmarks().first().any { it.id == mark.id && it.note == "Любимая мысль" })
        val importedAgain = sample()
        assertEquals(first.hash, importedAgain.hash)
        assertNotEquals(first.id, importedAgain.id)
        repo.dao.delete(mark)
    }

    @Test fun epubNavigatorLoadsAndRestoresLocator() = runBlocking {
        val book = sample()
        val intent = Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            device.wait(Until.hasObject(By.res("android:id/button1")), 6000)
            device.findObject(By.res("android:id/button1"))?.click()
            var ready = false
            repeat(40) {
                scenario.onActivity { activity ->
                    val nav = activity.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment
                    ready = nav?.view != null && nav.currentLocator.value.href.toString().contains("chapter")
                }
                if (!ready) delay(250)
            }
            assertTrue("Readium navigator did not load an EPUB chapter", ready)
            val pub = app.repository.open(File(book.path))
            val second = pub.readingOrder[1]
            scenario.onActivity { activity ->
                val nav = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                activity.lifecycleScope.launch { nav.go(second) }
            }
            var storedSecondChapter = false
            repeat(40) {
                storedSecondChapter = app.repository.dao.book(book.id)?.locator?.contains("chapter2") == true
                if (!storedSecondChapter) delay(250)
            }
            pub.close()
            assertTrue("Second chapter position was not persisted", storedSecondChapter)
            screenshot("reader.png")
            scenario.recreate()
            delay(2000)
            var restored = false
            scenario.onActivity { activity ->
                val nav = activity.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment
                restored = nav?.currentLocator?.value?.href?.toString()?.contains("chapter2") == true
            }
            assertTrue("Navigator was not recreated from the book file", restored)
        }
        assertNotNull(app.repository.dao.book(book.id)?.locator)
    }

    @Test fun mlKitDownloadsAndTranslatesOnDevice() = runBlocking {
        val translator = TranslationRepository()
        try {
            withTimeout(600000) { translator.prepare() }
            val translated = withTimeout(30000) { translator.translate("curious") }
            assertTrue("Empty translation", translated.isNotBlank())
            assertTrue("Expected Russian text, got: $translated", translated.any { it in 'А'..'я' })
        } finally { translator.close() }
    }

    @Test fun longPressCreatesBookmarkWithRealSelection() = runBlocking {
        val book = sample()
        // Model download is a network-dependent prerequisite, separate from selection UI timing.
        val model = TranslationRepository()
        try { withTimeout(600000) { model.prepare() } } finally { model.close() }
        val device = UiDevice.getInstance(instrumentation)
        val intent = Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            device.wait(Until.hasObject(By.res("android:id/button1")), 6000)
            device.findObject(By.res("android:id/button1"))?.click()
            var point: Pair<Int, Int>? = null
            repeat(40) {
                if (point == null) {
                    val answer = CompletableDeferred<Pair<Int, Int>?>()
                    scenario.onActivity { activity ->
                        fun webviews(view: View): List<WebView> = when (view) {
                            is WebView -> listOf(view)
                            is ViewGroup -> (0 until view.childCount).flatMap { webviews(view.getChildAt(it)) }
                            else -> emptyList()
                        }
                        val web = webviews(activity.window.decorView).firstOrNull { it.isShown && it.width > 0 }
                        if (web == null) answer.complete(null)
                        else {
                            val offset = IntArray(2); web.getLocationOnScreen(offset)
                            @Suppress("DEPRECATION") val scale = web.scale
                            web.evaluateJavascript("""(function(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(w.nextNode()){var n=w.currentNode;var i=n.textContent.indexOf('curious');if(i>=0){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+7);var b=r.getBoundingClientRect();if(b.top>=0&&b.bottom<innerHeight)return {x:b.x+b.width/2,y:b.y+b.height/2};}}return null;})()""") { value ->
                                answer.complete(runCatching { val obj = JSONObject(value); Pair(offset[0] + (obj.getDouble("x") * scale).toInt(), offset[1] + (obj.getDouble("y") * scale).toInt()) }.getOrNull())
                            }
                        }
                    }
                    point = withTimeout(3000) { answer.await() }
                    if (point == null) delay(250)
                }
            }
            val selected = requireNotNull(point) { "No visible curious word found in rendered EPUB" }
            device.swipe(selected.first, selected.second, selected.first, selected.second, 150)
            val markAction = By.text(java.util.regex.Pattern.compile("В закладки", java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE))
            val menuPresent = device.wait(Until.hasObject(markAction), 8000)
            screenshot("translation-selection.png")
            device.dumpWindowHierarchy(File(app.getExternalFilesDir(null), "test-screenshots/selection.xml"))
            assertTrue("Selection menu is absent; long press at $selected", menuPresent)
            assertTrue("Translation did not become available", device.wait(Until.hasObject(By.desc("Сохранить слово").enabled(true)), 30000))
            screenshot("translation-result.png")
            device.findObject(By.desc("Сохранить слово")).click()
            var savedWord: Word? = null
            repeat(30) {
                savedWord = app.repository.dao.words().first().find { it.normalized == "curious" }
                if (savedWord == null) delay(200)
            }
            assertNotNull("Translation was not saved to vocabulary", savedWord)
            assertTrue(savedWord!!.translation.any { it in 'А'..'я' })
            assertTrue(savedWord!!.context.contains("curious"))
            device.findObject(markAction).click()
            var saved: Bookmark? = null
            repeat(30) {
                saved = app.repository.dao.bookmarks().first().find { it.bookHash == book.hash && it.quote.contains("curious") }
                if (saved == null) delay(200)
            }
            assertNotNull("Selected word was not saved as a bookmark", saved)
            assertTrue(saved!!.locator.contains("highlight"))
            screenshot("bookmark-highlight.png")
            app.repository.dao.delete(saved!!)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("The Quiet Garden")), 10000))
            screenshot("library.png")
            device.findObject(By.text("Слова")).click()
            assertTrue(device.wait(Until.hasObject(By.text("curious")), 5000))
            screenshot("vocabulary.png")
        }
    }

    private fun screenshot(name: String) {
        val folder = File(app.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChitalkaTests")
            }
            app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                app.contentResolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            image.recycle()
        }
    }
}
