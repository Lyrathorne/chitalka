package ru.chitalka.app

import android.app.Application
import ru.chitalka.app.data.LibraryRepository
import ru.chitalka.app.data.TranslationRepository
import ru.chitalka.app.data.ModelDownloads

class ChitalkaApp : Application() {
    val repository by lazy { LibraryRepository(this) }
    val translator by lazy { TranslationRepository() }
    val modelDownloads by lazy { ModelDownloads(this, translator) }
}
