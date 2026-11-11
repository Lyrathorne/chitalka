package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.reader.ReaderActivity
import org.readium.r2.shared.util.getOrElse

@RunWith(AndroidJUnit4::class)
class EpubContentUiTest {
    @Test fun authorScriptsAreRemovedAndExternalImagesBlockedWhileReadiumWorks() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        val device = UiDevice.getInstance(instrument)
        val (book, file) = feedbackBook(app, """<span id="author-probe" onclick="window.authorEvent=true">Mira was curious about the quiet garden behind the old library.</span><script>window.authorScript=true</script>""")
        try {
            val publication = app.repository.open(java.io.File(book.path))
            try {
                val content = publication.get(publication.readingOrder[1])!!.read().getOrElse { error("Cannot read fixture") }.toString(Charsets.UTF_8)
                assertFalse("Publication serving bypassed sanitization", content.contains("window.authorScript"))
                assertTrue(content.contains("Content-Security-Policy"))
            } finally { publication.close() }
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", book.id).putExtra("locator", """{"href":"EPUB/chapter2.xhtml","type":"application/xhtml+xml"}""").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Настройки чтения")), 10000))
                scenario.readerPoint("curious")
                assertEquals("false", scenario.readerJs("Boolean(window.authorScript)"))
                assertEquals("false", scenario.readerJs("document.getElementById('author-probe').hasAttribute('onclick')"))
                scenario.readerJs("window.blockedExternal=false;document.addEventListener('securitypolicyviolation',e=>{if(e.blockedURI.indexOf('https://example.org')===0)window.blockedExternal=true;});var img=document.createElement('img');img.src='https://example.org/chitalka-test-pixel.png';document.body.appendChild(img);true")
                withTimeout(5000) { while (scenario.readerJs("window.blockedExternal") != "true") delay(100) }
                assertEquals("object", org.json.JSONTokener(scenario.readerJs("typeof readium")!!).nextValue())
            }
        } finally { cleanupFeedbackBook(app, book, file) }
    }
}
