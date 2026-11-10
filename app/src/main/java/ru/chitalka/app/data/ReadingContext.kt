package ru.chitalka.app.data

/** Keep the sentence containing the selected occurrence, without cutting arbitrary words. */
fun readingContext(before: String, selected: String, after: String): String {
    val start = before.indexOfLast { it in ".!?\n" } + 1
    var end = after.indexOfFirst { it in ".!?\n" }.let { if (it < 0) after.length else it + 1 }
    while (end < after.length && after[end] in "\"'”’)]") end++
    val prefix = before.substring(start)
    val suffix = after.take(end)
    val left = if (prefix.length <= 300) prefix else "… " + prefix.takeLast(300).substringAfter(' ', "")
    val right = if (suffix.length <= 300) suffix else suffix.take(300).substringBeforeLast(' ', "") + " …"
    return (left + selected + right).trim()
}

fun practiceContext(context: String, original: String): String {
    if (original.isBlank()) return context
    val match = Regex("(?<![\\p{L}])" + Regex.escape(original) + "(?![\\p{L}])", RegexOption.IGNORE_CASE).find(context) ?: return context
    return readingContext(context.take(match.range.first), match.value, context.drop(match.range.last + 1))
}
