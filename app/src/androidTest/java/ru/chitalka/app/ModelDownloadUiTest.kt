package ru.chitalka.app

import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ModelDownloadUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ChitalkaApp
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Test fun downloadRemainsVisibleAcrossScreensAndRecreation() = runBlocking {
        val model = TranslateRemoteModel.Builder(TranslateLanguage.RUSSIAN).build()
        RemoteModelManager.getInstance().deleteDownloadedModel(model).await()
        shell("cmd connectivity airplane-mode enable")
        shell("svc wifi disable")
        withTimeout(10000) {
            while (app.getSystemService(ConnectivityManager::class.java).activeNetwork != null) delay(200)
        }
        val downloads = app.modelDownloads
        downloads.refresh()
        withTimeout(10000) { downloads.state.first { it.phase != ModelPhase.CHECKING } }
        ActivityScenario.launch<ModelDownloadActivity>(Intent(app, ModelDownloadActivity::class.java)
            .putExtra("start", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { screen ->
            withTimeout(10000) { downloads.state.first { it.phase == ModelPhase.DOWNLOADING && it.detail.contains("Нет подключения") } }
            assertTrue(device.wait(Until.hasObject(By.textContains("Нет подключения")), 5000))
            screenshot("model-waiting-network.png")
            val elapsed = downloads.state.value.elapsedSeconds
            screen.recreate()
            assertTrue(device.wait(Until.hasObject(By.textContains("Нет подключения")), 5000))
            assertEquals(ModelPhase.DOWNLOADING, downloads.state.value.phase)
            assertTrue(downloads.state.value.elapsedSeconds >= elapsed)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.textContains("Загрузка модели")), 5000))
            screenshot("model-library-status.png")
        }
        // Check timeout/retry state using the real SDK, without waiting 15 minutes offline.
        val timeoutClient = TranslationRepository()
        val timed = ModelDownloads(app, timeoutClient, timeoutMillis = 1500)
        timed.start()
        withTimeout(10000) { timed.state.first { it.phase == ModelPhase.ERROR } }
        assertTrue(timed.state.value.detail.contains("Повторить"))
        timeoutClient.close()
        shell("cmd connectivity airplane-mode disable")
        shell("svc wifi enable")
        ActivityScenario.launch<ModelDownloadActivity>(Intent(app, ModelDownloadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val progress = withTimeout(180000) { downloads.state.first { it.total > 0 && it.bytes > 0 || it.phase == ModelPhase.READY } }
            if (progress.phase == ModelPhase.DOWNLOADING) {
                assertNotNull(progress.percent)
                assertTrue(device.wait(Until.hasObject(By.textContains("МБ")), 10000))
                screenshot("model-real-download-progress.png")
            }
            withTimeout(10 * 60 * 1000L) { downloads.state.first { it.phase == ModelPhase.READY } }
            assertTrue(device.wait(Until.hasObject(By.text("Переводчик готов")), 5000))
            screenshot("model-ready.png")
        }
    }

    private fun screenshot(name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChitalkaTests")
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                app.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }
}
