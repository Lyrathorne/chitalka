package ru.chitalka.app.data

import org.junit.Assert.*
import org.junit.Test

class ReadingContextTest {
    @Test fun keepsTheSentenceContainingTheWordInsteadOfAPartialPreviousWord() {
        val text = "he frame. He was pretty small. \"What kind of question is that?\" I shook my head."
        assertEquals("\"What kind of question is that?\"", practiceContext(text, "question"))
    }
    @Test fun preservesTheSelectedOccurrenceAndDoesNotMatchInsideAnotherWord() {
        assertEquals("A curious rabbit.", readingContext("Previous sentence. A ", "curious", " rabbit. Next sentence."))
        assertEquals("questionable", practiceContext("questionable", "question"))
    }
    @Test fun longContextClipsAtWordBoundaries() {
        val result = readingContext("prefix ".repeat(100), "curious", " suffix".repeat(100))
        assertTrue(result.startsWith("… ")); assertTrue(result.endsWith(" …")); assertTrue(result.contains("curious"))
        assertTrue(result.split(Regex("\\s+")).all { it in listOf("…", "prefix", "curious", "suffix") })
    }
}
