package dev.emyth.app.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveServiceRunsTest {
    @Test
    fun rejectedNewerStartDoesNotStopAnOlderGeneration() {
        val runs = ProactiveServiceRuns()
        runs.start(1)
        runs.start(2)
        assertNull(runs.finish(2))
        assertFalse(runs.canStop(2))
        assertEquals(2, runs.finish(1))
        assertTrue(runs.canStop(2))
    }

    @Test
    fun anOlderStartFinishingDoesNotStopTheNewerGeneration() {
        val runs = ProactiveServiceRuns()
        runs.start(7)
        runs.start(8)
        assertNull(runs.finish(7))
        assertEquals(8, runs.finish(8))
        assertTrue(runs.canStop(8))
    }

    @Test
    fun aNewStartInvalidatesAnAlreadyComputedStopCandidate() {
        val runs = ProactiveServiceRuns()
        runs.start(3)
        assertEquals(3, runs.finish(3))
        runs.start(4)
        assertFalse(runs.canStop(3))
        assertFalse(runs.canStop(4))
        assertEquals(4, runs.finish(4))
        assertTrue(runs.canStop(4))
    }

    @Test
    fun duplicateAndUnknownCompletionCannotStopActiveWork() {
        val runs = ProactiveServiceRuns()
        runs.start(11)
        runs.start(12)
        assertNull(runs.finish(11))
        assertNull(runs.finish(11))
        assertNull(runs.finish(99))
        assertFalse(runs.canStop(12))
        assertEquals(12, runs.finish(12))
        assertNull(runs.finish(12))
    }
}
