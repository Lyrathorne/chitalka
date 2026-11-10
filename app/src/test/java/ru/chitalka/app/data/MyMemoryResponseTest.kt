package ru.chitalka.app.data

import org.junit.Assert.*
import org.junit.Test

class MyMemoryResponseTest {
    @Test fun quotaAndServiceErrorsNeverBecomeASavedTranslation() {
        assertThrows(IllegalStateException::class.java) { MyMemoryResponse.decode("""{"responseStatus":200,"quotaFinished":true,"responseData":{"translatedText":"DAILY LIMIT"}}""") }
        assertThrows(IllegalStateException::class.java) { MyMemoryResponse.decode("""{"responseStatus":403,"responseData":{"translatedText":"Invalid"}}""") }
        assertThrows(IllegalStateException::class.java) { MyMemoryResponse.decode("""{"responseStatus":200,"responseData":{"translatedText":" "}}""") }
        assertEquals("справа", MyMemoryResponse.decode("""{"responseStatus":200,"responseData":{"translatedText":"справа"}}"""))
    }
    @Test fun validatesUtf8SizeAndOneParagraphBeforeAnyRequest() {
        MyMemoryResponse.validate("a".repeat(500))
        assertThrows(IllegalArgumentException::class.java) { MyMemoryResponse.validate("a".repeat(501)) }
        assertThrows(IllegalArgumentException::class.java) { MyMemoryResponse.validate("я".repeat(251)) }
        assertThrows(IllegalArgumentException::class.java) { MyMemoryResponse.validate("One paragraph.\nAnother paragraph.") }
    }
}
