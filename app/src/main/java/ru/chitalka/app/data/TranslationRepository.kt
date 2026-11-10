package ru.chitalka.app.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranslationRepository : AutoCloseable, TranslationProvider {
    override val source = "ML Kit • машинный перевод"
    override val attribution = "Машинный перевод • Google Translate"
    private fun createClient() = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.RUSSIAN).build())
    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private val gate = Mutex()
    @Volatile private var removing = false
    private fun client(): com.google.mlkit.nl.translate.Translator {
        check(!removing) { "Языковая модель удаляется" }
        return translator ?: createClient().also { translator = it }
    }
    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 300
    }
    suspend fun prepare() = gate.withLock { client().downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
    override suspend fun translate(text: String): String = gate.withLock {
        check(!removing) { "Языковая модель удаляется" }
        cache[text] ?: client().translate(text).await().also { cache[text] = it }
    }
    suspend fun beginRemoval() {
        removing = true
        gate.withLock { translator?.close(); translator = null; cache.clear() }
    }
    fun endRemoval() { removing = false }
    override fun close() { removing = true; translator?.close(); translator = null; cache.clear() }
}
