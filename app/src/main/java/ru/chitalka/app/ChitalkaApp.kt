package ru.chitalka.app

import android.app.Application
import ru.chitalka.app.data.LibraryRepository

class ChitalkaApp : Application() {
    val repository by lazy { LibraryRepository(this) }
}
