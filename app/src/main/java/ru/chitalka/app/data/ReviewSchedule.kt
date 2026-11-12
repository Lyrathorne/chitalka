package ru.chitalka.app.data

/** Deterministic intervals derived from the portable review log. Durations use UTC milliseconds. */
object ReviewSchedule {
    private const val DAY = 86_400_000L
    fun dueAt(events: List<ReviewEvent>): Long {
        var interval = 0L
        var due = 0L
        events.filter { it.rating in 0..2 }.distinctBy { Triple(it.wordId, it.createdAt, it.rating) }
            .sortedWith(compareBy<ReviewEvent> { it.createdAt }.thenBy { it.rating }).forEach {
                interval = when (it.rating) {
                    0 -> 600_000L
                    1 -> maxOf(DAY, interval * 12 / 10).coerceAtMost(180 * DAY)
                    else -> if (interval < DAY) DAY else (interval * 25 / 10).coerceAtMost(180 * DAY)
                }
                due = it.createdAt.coerceAtMost(Long.MAX_VALUE - interval) + interval
            }
        return due
    }
    fun dueWords(words: List<Word>, events: List<ReviewEvent>, now: Long): List<Word> {
        val history = events.groupBy { it.wordId }
        return words.map { it to dueAt(history[it.id].orEmpty()) }.filter { it.second <= now }
            .sortedWith(compareBy<Pair<Word, Long>> { it.second }.thenBy { it.first.createdAt }.thenBy { it.first.id }).map { it.first }
    }
}
