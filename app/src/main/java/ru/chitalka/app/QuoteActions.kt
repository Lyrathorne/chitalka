package ru.chitalka.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import ru.chitalka.app.data.Bookmark

fun quoteText(mark: Bookmark): String = mark.quote + "\n\n— " + listOf(mark.author, mark.bookTitle).filter { it.isNotBlank() }.joinToString(", ")
fun quoteDate(mark: Bookmark): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(mark.createdAt))
fun quoteChapter(mark: Bookmark): String = runCatching { org.json.JSONObject(mark.locator).optString("title").takeIf { it.isNotBlank() } }.getOrNull() ?: "Фрагмент книги"
fun copyQuote(context: Context, mark: Bookmark) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Цитата из книги", quoteText(mark)))
}
fun quoteShareIntent(mark: Bookmark): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, quoteText(mark)); putExtra(Intent.EXTRA_SUBJECT, mark.bookTitle)
}
fun shareQuote(context: Context, mark: Bookmark) { context.startActivity(Intent.createChooser(quoteShareIntent(mark), "Поделиться цитатой")) }
