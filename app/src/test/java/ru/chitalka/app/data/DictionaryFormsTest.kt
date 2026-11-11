package ru.chitalka.app.data

import org.junit.Assert.*
import org.junit.Test

class DictionaryFormsTest {
    @Test fun normalizesPunctuationWithoutDestroyingContractionsAndHyphens() {
        assertEquals("don't", dictionaryKey("“Don’t!”"))
        assertEquals("well-known", dictionaryKey("Well‑Known"))
        assertEquals(listOf("went", "go"), dictionaryCandidates("went"))
        assertTrue(dictionaryCandidates("children").contains("child"))
        assertTrue(dictionaryCandidates("stopped").contains("stop"))
        assertTrue(dictionaryCandidates("studied").contains("study"))
        assertTrue(dictionaryCandidates("running").contains("run"))
        assertEquals("glass", dictionaryCandidates("glass").first())
        assertFalse(dictionaryCandidates("glass").contains("glas"))
    }
}
