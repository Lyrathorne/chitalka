package ru.chitalka.app.data
import org.junit.Assert.*
import org.junit.Test
class PracticeExercisesTest {
    @Test fun normalizesCaseSpacesUnicodeAndAcceptsExplicitAlternatives(){
        assertTrue(PracticeExercises.matches("  ФРУКТОВЫЙ\u00a0 САД! ","сад; фруктовый сад"))
        assertTrue(PracticeExercises.matches("don’t","don't"))
        assertTrue(PracticeExercises.matches("ＨＥＬＬＯ","hello"))
        assertFalse(PracticeExercises.matches("","сад"));assertFalse(PracticeExercises.matches(" ",";\n"))
        assertFalse(PracticeExercises.matches("все","всё"));assertFalse(PracticeExercises.matches("дом","сад; фруктовый сад"))
    }
    @Test fun clozeMasksAllOccurrencesAndRespectsWordBoundaries(){
        assertEquals("The ____ and ____.",PracticeExercises.cloze("The orchard and ORCHARD.","orchard"))
        assertNull(PracticeExercises.cloze("The garden.","he"));assertNull(PracticeExercises.cloze("No example","orchard"))
        assertEquals("A ____ garden.",PracticeExercises.cloze("A well-known garden.","well-known"))
    }
}
