package ru.chitalka.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

object MyMemoryResponse {
    fun validate(text: String) {
        require(text.isNotBlank()) { "Сначала выделите текст" }
        require(text.toByteArray(Charsets.UTF_8).size <= 500 && text.trim().lines().size == 1) {
            "Для онлайн-перевода выделите один короткий абзац"
        }
    }
    fun decode(json: String): String {
        val root = JSONObject(json)
        check(!root.optBoolean("quotaFinished") && root.optInt("responseStatus") !in listOf(403, 429)) {
            "Дневной лимит MyMemory исчерпан. Используйте офлайн-перевод или повторите позже."
        }
        check(root.optInt("responseStatus") == 200) { "MyMemory не смог перевести текст. Повторите позже." }
        val translated = root.getJSONObject("responseData").getString("translatedText").trim()
        check(translated.isNotBlank() && translated.length <= 20000) { "Сервис вернул пустой или слишком длинный ответ" }
        return translated
    }
}

/** An explicit opt-in provider. Nothing is sent at construction or during offline translation. */
class MyMemoryTranslation : TranslationProvider {
    override val source = "MyMemory • онлайн-перевод"
    override val isOnline = true
    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        MyMemoryResponse.validate(text)
        val query = URLEncoder.encode(text, "UTF-8")
        val connection = URL("https://api.mymemory.translated.net/get?langpair=en%7Cru&mt=1&q=$query").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Chitalka/0.8.0")
            check(connection.responseCode == 200) { "Сервис онлайн-перевода недоступен. Проверьте сеть и повторите позже." }
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096); val body = StringBuilder()
                while (true) {
                    val count = reader.read(buffer); if (count < 0) break
                    check(body.length + count <= 1048576) { "Слишком большой ответ сервиса" }
                    body.append(buffer, 0, count)
                }
                body.toString()
            }
            android.text.Html.fromHtml(MyMemoryResponse.decode(json), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } finally { connection.disconnect() }
    }
}
