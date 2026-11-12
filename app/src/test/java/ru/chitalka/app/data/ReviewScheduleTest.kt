package ru.chitalka.app.data

import org.junit.Assert.*
import org.junit.Test

class ReviewScheduleTest {
    private val day = 86_400_000L
    @Test fun newWordsAndLapses() {
        assertEquals(0L, ReviewSchedule.dueAt(emptyList()))
        assertEquals(600_100L, ReviewSchedule.dueAt(listOf(ReviewEvent(wordId="w",rating=0,createdAt=100))))
    }
    @Test fun growthSortingDuplicatesAndLapseReset() {
        val a=ReviewEvent(wordId="w",rating=2,createdAt=100)
        val b=ReviewEvent(wordId="w",rating=2,createdAt=day+100)
        assertEquals(3*day+day/2+100,ReviewSchedule.dueAt(listOf(b,a,a)))
        val lapse=ReviewEvent(wordId="w",rating=0,createdAt=4*day)
        assertEquals(4*day+600_000,ReviewSchedule.dueAt(listOf(b,a,lapse)))
    }
    @Test fun utcElapsedIntervalsDoNotDependOnTimezone() {
        val old=java.util.TimeZone.getDefault()
        try {
            val event=ReviewEvent(wordId="w",rating=1,createdAt=1_741_486_200_000L)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));val due=ReviewSchedule.dueAt(listOf(event))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Moscow"));assertEquals(due,ReviewSchedule.dueAt(listOf(event)));assertEquals(event.createdAt+day,due)
        } finally { java.util.TimeZone.setDefault(old) }
    }
    @Test fun dueBoundaryAndMissedDays() {
        val word=Word("w","word","word","слово","","hash","Book","{}","QA",0)
        val review=ReviewEvent(wordId="w",rating=2,createdAt=100)
        assertTrue(ReviewSchedule.dueWords(listOf(word),listOf(review),day+99).isEmpty())
        assertEquals(listOf(word),ReviewSchedule.dueWords(listOf(word),listOf(review),day+100))
        assertEquals(listOf(word),ReviewSchedule.dueWords(listOf(word),listOf(review),30*day))
    }
}
