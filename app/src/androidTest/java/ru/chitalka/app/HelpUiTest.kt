package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelpUiTest {
    @Test fun helpDisplaysVersionAndOfflineLicenseInformation() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ChitalkaApp
        val device = UiDevice.getInstance(instrument)
        ActivityScenario.launch<HelpActivity>(Intent(app, HelpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            assertTrue(device.wait(Until.hasObject(By.text("Читалка 1.0.0")), 10000))
            feedbackCapture("mvp-help-${app.themes.current.value.name.lowercase()}-${app.resources.configuration.fontScale}.png")
            repeat(50) {
                if (!device.hasObject(By.text("Тексты лицензий офлайн"))) {
                    val bounds = device.findObject(By.scrollable(true)).visibleBounds
                    val density = app.resources.displayMetrics.density
                    device.swipe(bounds.centerX(), minOf(bounds.bottom - (16*density).toInt(), device.displayHeight-(96*density).toInt()), bounds.centerX(), bounds.top+(24*density).toInt(), 20)
                    device.waitForIdle()
                }
            }
            assertTrue(device.hasObject(By.text("Тексты лицензий офлайн")))
            for (file in listOf("APACHE-2.0.txt", "READIUM-BSD-3.txt", "JSOUP-MIT.txt")) {
                assertTrue(app.assets.open("licenses/$file").use { it.readBytes().size } > 500)
            }
        }
    }
}
