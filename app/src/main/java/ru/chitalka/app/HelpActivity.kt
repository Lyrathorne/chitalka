package ru.chitalka.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import org.json.JSONObject
import ru.chitalka.app.ui.*

class HelpActivity : ThemedActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val dictionary = assets.open("dictionary/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val licenses = listOf("APACHE-2.0.txt", "READIUM-BSD-3.txt", "JSOUP-MIT.txt", "PDFBOX-LICENSE.txt", "PDFBOX-NOTICE.txt").joinToString("\n\n") { name ->
            assets.open("licenses/$name").bufferedReader().use { "$name\n\n${it.readText()}" }
        }
        setContent { ChitalkaTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("Помощь") }, navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
            }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp)) {
                    var showLicenses by remember { mutableStateOf(false) }
                    Text("Читалка $version", style = MaterialTheme.typography.headlineLarge)
                    Section("Начало", "Добавьте EPUB, PDF, DOCX, FB2 (в том числе ZIP с одной FB2) или TXT кнопкой «+» в библиотеке. Книга копируется на устройство. Удерживайте слово и нажмите «Перевести»; кнопка «+» сохраняет выбранный перевод в «Мои слова». Там можно исправить перевод и контекст. Во вкладке «Практика» повторяйте сохранённые слова.")
                    Section("Цитаты и заметки", "Выделите фрагмент и нажмите «Цитата» или «Заметка». Цитаты отмечаются фоном, заметки — линией. Нажмите отметку, чтобы изменить запись. «Закладка здесь» сохраняет позицию страницы. Все моменты доступны из библиотеки и внутри книги. Удаление EPUB сохраняет слова, цитаты и заметки; для перехода добавьте тот же файл снова.")
                    Section("Чтение", "Края страницы и свайпы перелистывают книгу; касание центра скрывает панели. Через «Aa» меняйте размер текста и оформление. Оглавление открывает главы. Поддерживаются EPUB без DRM, PDF, DOCX, FB2 и TXT до 100 МБ. Документы открываются как текст: сложная вёрстка, таблицы и изображения могут отличаться. PDF без текстового слоя требует предварительного OCR; зашифрованные PDF и старый DOC не поддерживаются. Для TXT доступны UTF-8, UTF-16 с BOM и Windows-1251.")
                    Section("Перевод", "Встроенный словарь работает без сети и модели. При наличии нескольких вариантов нажмите «Выбрать значение»: это доступные значения источника, а не автоматический выбор по контексту. Если слово не найдено, используется ML Kit. Его модель EN → RU нужно один раз загрузить на экране «Переводчик»; после этого перевод работает офлайн. Модель можно удалить и загрузить снова. Машинный перевод может ошибаться.")
                    Section("Онлайн-режим", "MyMemory вызывается только отдельной кнопкой и после подтверждения каждого запроса. Сервис получает выделенный фрагмент, хранит его и может передавать партнёрам. Личные заметки, словарь и книга целиком ему не отправляются. Доступны только короткие абзацы; есть дневная квота.")
                    Link("Условия MyMemory", "https://mymemory.translated.net/terms-and-conditions")
                    Section("Хранение и резервные копии", "Книги и учебные данные хранятся в закрытом хранилище приложения. Регистрация и собственный сервер не требуются. Экран «Копии и перенос» экспортирует JSON и CSV; перед восстановлением сохраняется страховочная копия. JSON не содержит исходные книги, языковую модель, оформление и незавершённую практику. После удаления приложения локальные данные будут потеряны, если заранее не сохранить JSON вне приложения и исходные файлы книг. Файлы JSON и CSV содержат ваши записи: передавайте их только по своему решению.")
                    Section("Источники", "Встроенный English-Русский FreeDict+WikDict dictionary: Karl Bartel и участники Wiktionary через DBnary. Версия 2025.11.23, ${dictionary.getString("headwords")} заголовков, ${dictionary.getString("senses")} вариантов. Данные преобразованы в SQLite с нормализацией ключей; переводы и связанные английские пояснения сохранены. Производная база распространяется по CC BY-SA 3.0, отдельно от личных данных.")
                    Link("FreeDict и исходные данные", "https://download.freedict.org/generated/eng-rus/")
                    Link("CC BY-SA 3.0", "https://creativecommons.org/licenses/by-sa/3.0/")
                    Section("Библиотеки", "Readium Kotlin Toolkit — BSD-3-Clause. Kotlin, AndroidX, Compose, Room, DataStore и Coroutines — Apache-2.0. Jsoup — MIT. PdfBox-Android — Apache-2.0. Google ML Kit используется на условиях Google; машинный перевод выполняется Google Translate. Применяются системные шрифты Android.")
                    Link("Google Translate и атрибуция", "https://cloud.google.com/translate")
                    TextButton(onClick = { showLicenses = !showLicenses }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showLicenses) "Скрыть тексты лицензий" else "Тексты лицензий офлайн") }
                    if (showLicenses) androidx.compose.foundation.text.selection.SelectionContainer { Text(licenses, style = MaterialTheme.typography.bodySmall) }
                    Section("Машинный перевод", "Перевод Google Translate предоставляется без гарантий точности, надёжности, пригодности для конкретной цели и отсутствия нарушения прав. Он помогает при чтении, но не заменяет проверку важных фрагментов.")
                    Link("Google: конфиденциальность", "https://policies.google.com/privacy")
                    Link("Исходный код, лицензии и сообщения об ошибках", "https://github.com/Lyrathorne/chitalka")
                    Spacer(Modifier.height(16.dp))
                }
            }
        } }
    }

    @Composable private fun Section(title: String, text: String) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
    @Composable private fun Link(title: String, url: String) {
        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(title) }
    }
}
