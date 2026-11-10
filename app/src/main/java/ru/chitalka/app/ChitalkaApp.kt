package ru.chitalka.app

import android.app.Application
import ru.chitalka.app.data.LibraryRepository
import ru.chitalka.app.data.TranslationRepository
import ru.chitalka.app.data.ModelDownloads

class ChitalkaApp : Application() {
    val themes by lazy { ru.chitalka.app.ui.ThemeSettings(this) }
    override fun onCreate() {
        super.onCreate()
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { themes.load() }
        themes.applyNative()
    }
    val repository by lazy { LibraryRepository(this) }
    val translator by lazy { TranslationRepository() }
    val modelDownloads by lazy { ModelDownloads(this, translator) }
}
