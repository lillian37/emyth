package dev.emyth.app.data.service

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProactivePreparationTest {
    @Test
    fun successfulAndNullableResultsArePreserved() = runTest {
        assertEquals("ready", prepareProactiveStep("tools", 100) { delay(20); "ready" })
        assertEquals(null, prepareProactiveStep<String?>("context", 100) { null })
        assertEquals(20L, currentTime)
    }

    @Test
    fun aStalledPreparationFailsAndClosesItsWork() = runTest {
        var closed = false
        val failure = runCatching {
            prepareProactiveStep("准备工具", 100) {
                try { awaitCancellation() } finally { closed = true }
            }
        }.exceptionOrNull()

        assertTrue(failure is ProactivePreparationTimeoutException)
        assertFalse(failure is CancellationException)
        assertTrue(failure?.message.orEmpty().contains("准备工具"))
        assertTrue(closed)
        assertEquals(100L, currentTime)
    }

    @Test
    fun anOrdinaryFailureIsNotRelabeledAsTimeout() = runTest {
        val failure = runCatching {
            prepareProactiveStep("tools", 100) { throw IOException("initialization failed") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure is ProactivePreparationTimeoutException)
        assertEquals("initialization failed", failure?.message)
    }

    @Test
    fun externalCancellationClosesPreparationWithoutRetry() = runTest {
        var calls = 0
        var closed = false
        val collector = launch {
            prepareProactiveStep("tools", 100) {
                calls++
                try { awaitCancellation() } finally { closed = true }
            }
        }
        runCurrent()
        advanceTimeBy(20)
        collector.cancel()
        collector.join()
        assertTrue(collector.isCancelled)
        assertTrue(closed)
        assertEquals(1, calls)
        assertEquals(20L, currentTime)
    }

    @Test
    fun aCallerDeadlineRemainsCancellation() = runTest {
        val failure = runCatching {
            withTimeout(20L) {
                prepareProactiveStep("context", 100) { awaitCancellation() }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(failure is ProactivePreparationTimeoutException)
        assertEquals(20L, currentTime)
    }
}
