package ru.chitalka.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import ru.chitalka.app.ui.AppTheme
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThemeProcessTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ChitalkaApp
    private val saved get() = File(app.cacheDir, "theme-process-checkpoint.txt")
    @Test fun seed() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("themePhase") == "seed")
        check(!saved.exists()); saved.writeText(app.themes.current.value.name)
        withContext(Dispatchers.Main) { app.themes.select(AppTheme.NIGHT) }
    }
    @Test fun resume() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("themePhase") == "resume")
        val previous = AppTheme.valueOf(saved.readText())
        try { assertEquals(AppTheme.NIGHT, app.themes.current.value) }
        finally { withContext(Dispatchers.Main) { app.themes.select(previous) }; saved.delete() }
    }
}
