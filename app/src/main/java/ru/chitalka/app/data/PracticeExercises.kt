package ru.chitalka.app.data

import java.text.Normalizer
import java.util.Locale

object PracticeExercises {
    fun normalize(answer: String): String = Normalizer.normalize(answer, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
        .replace(Regex("[‐‑‒–—―]"), "-").replace(Regex("[\\s\\p{Z}]+"), " ").trim(' ', '.', '!', '?')
    fun variants(expected: String): Set<String> = (listOf(expected) + expected.split(';', '\n')).map(::normalize).filter { it.isNotBlank() }.toSet()
    fun matches(answer: String, expected: String): Boolean = normalize(answer).let { it.isNotEmpty() && it in variants(expected) }
    fun cloze(context: String, original: String): String? {
        if (original.isBlank()) return null
        val pattern=Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(original)+"(?![\\p{L}\\p{N}])",RegexOption.IGNORE_CASE)
        val first=pattern.find(context) ?: return null
        val start=(first.range.first-160).coerceAtLeast(0)
        val end=(first.range.last+241).coerceAtMost(context.length)
        return (if(start>0) "…" else "")+pattern.replace(context.substring(start,end),"____")+(if(end<context.length) "…" else "")
    }
}
