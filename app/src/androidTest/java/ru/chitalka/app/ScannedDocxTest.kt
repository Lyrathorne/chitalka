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
@RunWith(AndroidJUnit4::class)
class ScannedDocxTest {
    @Test fun importsImageOnlyDocxWithBundledOcr()=runBlocking<Unit>{
        assumeTrue(InstrumentationRegistry.getArguments().getString("scanPhase")=="1")
        val instrument=InstrumentationRegistry.getInstrumentation();val app=instrument.targetContext.applicationContext as ChitalkaApp
        assertTrue(app.packageName.endsWith(".mvpcheck"))
        val file=File(app.cacheDir,"scanned.docx")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cat /sdcard/Download/chitalka-scanned.docx")).use{input->file.outputStream().use{input.copyTo(it)}}
        assertTrue(file.length()>200_000_000)
        val result=app.repository.importWithResult(Uri.fromFile(file)){println(it)};val book=result.book
        try{
            assertTrue(result.notice!!.contains("OCR"))
            val pub=app.repository.open(File(book.path));assertTrue(pub.readingOrder.size>=100);pub.close()
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("bookId",book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use{scenario->
                withTimeout(20000){var ready=false;while(!ready){scenario.onActivity{ready=it.supportFragmentManager.findFragmentByTag("epub")!=null};delay(100)}}
                val text=scenario.readerJs("document.body.textContent")!!;assertTrue(text.length>30)
                val token=org.json.JSONTokener(scenario.readerJs("(document.body.textContent.match(/[A-Za-z]{4,}/)||[''])[0]")!!).nextValue().toString()
                assertTrue(token.isNotBlank());scenario.readerPoint(token);feedbackCapture("scan-docx-reader.png")
            }
        }finally{app.repository.remove(book);file.delete()}
    }
}
