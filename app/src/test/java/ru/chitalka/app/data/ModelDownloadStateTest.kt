package ru.chitalka.app.data

import org.junit.Assert.*
import org.junit.Test

class ModelDownloadStateTest {
    @Test fun unknownSizeDoesNotInventPercentage() {
        assertNull(ModelDownloadState(ModelPhase.DOWNLOADING, bytes = 2048).percent)
    }
    @Test fun actualByteCountProducesPercentage() {
        assertEquals(25, ModelDownloadState(ModelPhase.DOWNLOADING, bytes = 256, total = 1024).percent)
    }
    @Test fun completeArchiveDoesNotClaimInstalledModel() {
        val state = ModelDownloadState(ModelPhase.DOWNLOADING, bytes = 1024, total = 1024)
        assertEquals(100, state.percent)
        assertEquals("Установка модели…", state.title)
        assertNotEquals(ModelPhase.READY, state.phase)
    }
}
