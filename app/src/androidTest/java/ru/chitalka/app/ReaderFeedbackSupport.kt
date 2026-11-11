package ru.chitalka.app

import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import ru.chitalka.app.reader.ReaderActivity
import java.io.File
import java.util.UUID
import java.util.zip.*

internal fun testPictureName(name: String) = name.removeSuffix(".png") + "-" + System.currentTimeMillis() + ".png"

internal fun UiDevice.editorAction(text: String): UiObject2 {
    val instrument = InstrumentationRegistry.getInstrumentation()
    val density = instrument.targetContext.resources.displayMetrics.density
    val selector = By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(text), java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE))
    repeat(16) { attempt ->
        val target = wait(Until.findObject(selector), 300)
        if (target != null && target.visibleBounds.width() > 0 && target.visibleBounds.height() > 0 && (target.className != "android.widget.Button" || target.visibleBounds.height() >= (44 * density).toInt())) {
            waitForIdle(); instrument.waitForIdleSync(); Thread.sleep(120)
            return wait(Until.findObject(selector), 1000)
        }
        val scroller = findObjects(By.pkg(instrument.targetContext.packageName).scrollable(true)).firstOrNull { it.className != "android.widget.EditText" }
            ?: run {
                wait(Until.findObject(selector), 1000)?.let { return it }
                feedbackCapture("editor-unreachable.png")
                dumpWindowHierarchy(java.io.File(instrument.targetContext.cacheDir, "editor-unreachable.xml"))
                error("No scrollable editor for action: $text")
            }
        val bounds = scroller.visibleBounds
        val gutter = (8 * density).toInt()
        // Stay clear of Android's home/back gesture edges on short screens.
        val x = (bounds.left + gutter).coerceIn((24 * density).toInt(), displayWidth - (24 * density).toInt())
        val top = maxOf(bounds.top + gutter, (48 * density).toInt())
        val bottom = minOf(bounds.bottom - gutter, displayHeight - (96 * density).toInt())
        if (attempt < 8) swipe(x, bottom, x, top, 30)
        else swipe(x, top, x, bottom, 30)
        waitForIdle(); Thread.sleep(150)
    }
    error("Action not reachable: $text")
}
internal fun UiDevice.clickEditorAction(text: String) = editorAction(text).click()

internal suspend fun ActivityScenario<ReaderActivity>.readerJs(script: String): String? {
    val answer = CompletableDeferred<String?>()
    onActivity { activity -> activity.lifecycleScope.launch {
        runCatching {
            val nav = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
            nav.evaluateJavascript(script)
        }.fold(answer::complete, answer::completeExceptionally)
    } }
    return withTimeout(10000) { answer.await() }
}

internal suspend fun ActivityScenario<ReaderActivity>.readerPoint(text: String): Pair<Int, Int> {
    val script = """(function(){const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(w.nextNode()){const t=w.currentNode,i=t.textContent.indexOf(${JSONObject.quote(text)});if(i>=0){const r=document.createRange();r.setStart(t,i);r.setEnd(t,i+${text.length});const s=window.getSelection();s.removeAllRanges();s.addRange(r);return readium.getCurrentSelection();}}return null;})()"""
    repeat(40) {
        val raw = readerJs(script)
        if (raw != null && raw != "null") {
            val answer = CompletableDeferred<Pair<Int, Int>?>()
            onActivity { activity -> activity.lifecycleScope.launch {
                runCatching {
                    val nav = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                    val selected = requireNotNull(nav.currentSelection())
                    val rect = requireNotNull(selected.rect)
                    val height = JSONObject(raw).getJSONObject("rect").getDouble("height")
                    val offset = IntArray(2); nav.view!!.getLocationOnScreen(offset)
                    val point = Pair((offset[0] + rect.centerX()).toInt(), (offset[1] + rect.top + height / 2).toInt())
                    nav.clearSelection()
                    if (point.first !in offset[0] until offset[0] + nav.view!!.width || point.second !in offset[1] until offset[1] + nav.view!!.height) {
                        nav.go(selected.locator); null
                    } else point
                }.fold(answer::complete, answer::completeExceptionally)
            } }
            withTimeout(10000) { answer.await() }?.let { return it }
        }
        delay(100)
    }
    error("Text not found: $text")
}

internal suspend fun feedbackBook(app: ChitalkaApp, sentence: String? = null, fixedFont: Boolean = false): Pair<ru.chitalka.app.data.Book, File> {
    val file = File(app.cacheDir, "feedback-${UUID.randomUUID()}.epub")
    ZipInputStream(app.assets.open("sample.epub")).use { input -> ZipOutputStream(file.outputStream()).use { output ->
        while (true) {
            val entry = input.nextEntry ?: break
            var bytes = input.readBytes()
            if (entry.name.endsWith("book.opf")) bytes = bytes.toString(Charsets.UTF_8).replace("urn:chitalka:quiet-garden:1", "urn:chitalka:feedback:${UUID.randomUUID()}").replace("The Quiet Garden", "Reader feedback test").toByteArray()
            if (sentence != null && entry.name.endsWith(".xhtml")) bytes = bytes.toString(Charsets.UTF_8).replace("Mira was curious about the quiet garden behind the old library.", sentence).toByteArray()
            if (fixedFont && entry.name.endsWith(".xhtml")) bytes = bytes.toString(Charsets.UTF_8).replace("</head>", "<style>p { font-size: 18px !important; }</style></head>").toByteArray()
            val target = ZipEntry(entry.name)
            if (entry.name == "mimetype") { target.method = ZipEntry.STORED; target.size = bytes.size.toLong(); target.compressedSize = target.size; target.crc = CRC32().apply { update(bytes) }.value }
            output.putNextEntry(target); output.write(bytes); output.closeEntry()
        }
    } }
    return app.repository.import(Uri.fromFile(file)) to file
}

internal fun feedbackCapture(name: String) {
    val instrument = InstrumentationRegistry.getInstrumentation()
    val app = instrument.targetContext.applicationContext as ChitalkaApp
    UiDevice.getInstance(instrument).waitForIdle(); instrument.waitForIdleSync(); Thread.sleep(300)
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, testPictureName(name))
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChitalkaTests")
    }
    instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
        app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri -> app.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
    }
}

internal suspend fun cleanupFeedbackBook(app: ChitalkaApp, book: ru.chitalka.app.data.Book, file: File) {
    app.repository.dao.allBookmarks().filter { it.bookHash == book.hash }.forEach { app.repository.dao.delete(it) }
    app.repository.dao.allWords().filter { it.bookHash == book.hash }.forEach { app.repository.dao.delete(it) }
    app.repository.remove(book)
    app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM ReadingState WHERE hash = ?", arrayOf(book.hash))
    file.delete()
}
