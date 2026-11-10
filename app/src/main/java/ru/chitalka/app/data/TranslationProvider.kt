package ru.chitalka.app.data

interface TranslationProvider {
    val source: String
    val attribution: String get() = source
    val isOnline: Boolean get() = false
    suspend fun translate(text: String): String
}
