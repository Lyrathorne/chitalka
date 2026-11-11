package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.reader.ReaderActivity
import java.io.File

@RunWith(AndroidJUnit4::class)
class MvpCorpusTest {
    @Test fun importsFiveRealPublicDomainBooksAndReadsMiddleChapters() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("corpusPhase") == "1")
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        assertTrue("Only use an isolated QA package", app.packageName.endsWith(".mvpcheck"))
        val folder = File(app.getExternalFilesDir(null), "qa-corpus")
        for (id in listOf("11", "1342", "84", "46", "43")) {
            // ADB-created external directories can retain shell ownership under scoped storage.
            // Transfer the fixture through UiAutomation into the app-owned cache, without new app permissions.
            val file = File(app.cacheDir, "qa-corpus-$id.epub")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cat ${folder.absolutePath}/$id.epub")).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            assertTrue("Missing QA EPUB $id", file.length() > 1000)
            val start = android.os.SystemClock.elapsedRealtime()
            val imported = app.repository.importWithResult(Uri.fromFile(file)); assertFalse(imported.duplicate)
            val book = imported.book
            try {
                val publication = app.repository.open(File(book.path))
                assertTrue(book.title.isNotBlank()); assertTrue(book.author.isNotBlank())
                assertTrue(publication.readingOrder.isNotEmpty())
                val link = publication.readingOrder[publication.readingOrder.size / 2]
                val locator = publication.locatorFromLink(link)!!.toJSON().toString()
                publication.close()
                ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", locator).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                    withTimeout(20000) { var ready = false; while (!ready) { scenario.onActivity { ready = it.supportFragmentManager.findFragmentByTag("epub") != null }; delay(100) } }
                    val raw = scenario.readerJs("document.body.textContent.trim().length")
                    assertTrue("Empty body in real EPUB $id", raw!!.toInt() > 100)
                    assertTrue(JSONTokener(scenario.readerJs("document.querySelector('meta[http-equiv=Content-Security-Policy]')?.content")!!).nextValue().toString().contains("connect-src 'none'"))
                    val word = JSONTokener(scenario.readerJs("(document.body.textContent.match(/[a-zA-Z]{5,}/)||[''])[0]")!!).nextValue().toString()
                    assertTrue(word.isNotBlank())
                    scenario.readerPoint(word)
                    delay(500)
                    if (id == "11" || id == "1342") feedbackCapture("mvp-corpus-$id.png")
                    println("CORPUS id=$id elapsedMs=${android.os.SystemClock.elapsedRealtime()-start} heapBytes=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()}")
                }
            } finally { app.repository.remove(book); file.delete() }
        }
    }
}
