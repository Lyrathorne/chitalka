package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.reader.ReaderActivity
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DocumentImportTest {
    @Test fun importsDocumentsIntoRealReader() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formatsPhase") == "1")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        assertTrue(app.packageName.endsWith(".mvpcheck"))
        fun fixture(name: String): File = File(app.cacheDir, name).apply {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cat /sdcard/Download/chitalka-formats/$name")).use { input -> outputStream().use { input.copyTo(it) } }
            assertTrue(length() > 0)
        }
        for (name in listOf("sample.txt", "unicode.txt", "sample.docx", "sample.fb2", "sample.fb2.zip", "sample.pdf")) {
            val file = fixture(name)
            val imported = app.repository.importWithResult(Uri.fromFile(file))
            val book = imported.book
            try {
                assertFalse(imported.duplicate)
                assertNotNull(imported.notice)
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(hash, book.hash)
                assertTrue(app.repository.importWithResult(Uri.fromFile(file)).duplicate)
                val publication = app.repository.open(File(book.path))
                assertTrue(publication.readingOrder.isNotEmpty())
                publication.close()
                ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                    withTimeout(25000) { var ready = false; while (!ready) { scenario.onActivity { ready = it.supportFragmentManager.findFragmentByTag("epub") != null }; delay(100) } }
                    val body = scenario.readerJs("document.body.textContent")!!
                    assertTrue("Missing converted text in $name: $body", body.contains("curious") || body.contains("Любопытный"))
                    if (body.contains("curious")) scenario.readerPoint("curious")
                    println("FORMAT_READER_PASS $name hash=$hash")
                }
            } finally { app.repository.remove(book); file.delete() }
        }
        for (name in listOf("scan.pdf", "password.pdf", "broken.docx", "xxe.fb2")) {
            val file = fixture(name)
            try {
                val result = runCatching { app.repository.importWithResult(Uri.fromFile(file)) }
                assertTrue("Invalid document accepted: $name", result.isFailure)
                println("FORMAT_REJECT_PASS $name: ${result.exceptionOrNull()?.message}")
            } finally { file.delete() }
        }
    }
}
