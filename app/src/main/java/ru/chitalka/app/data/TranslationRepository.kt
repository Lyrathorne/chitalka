package ru.chitalka.app.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class TranslationRepository : AutoCloseable {
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.RUSSIAN).build())
    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
    }
    suspend fun prepare() = translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    suspend fun translate(text: String): String {
        cache[text]?.let { return it }
        return translator.translate(text).await().also { cache[text] = it }
    }
    override fun close() = translator.close()
}
