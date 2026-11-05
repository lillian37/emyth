package dev.emyth.app.data.service

import dev.emyth.app.data.ai.ProactiveStreamTimeoutException
import dev.emyth.app.data.ai.guardProactiveStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProactiveStreamCheckpointTest {
    @Test
    fun successfulCompletionDrainsTheFinalFullyMergedSnapshot() = runTest {
        val checkpoints = mutableListOf<Pair<Long, String>>()
        flow {
            var merged = ""
            for (delta in listOf("one", " two", " three", " four")) {
                merged += delta
                emit(merged)
                delay(100)
            }
        }.checkpointProactiveSnapshots(500).collect { checkpoints += currentTime to it }

        assertEquals("one two three four", checkpoints.last().second)
        assertTrue(checkpoints.size < 4)
        assertTrue(checkpoints.zipWithNext().all { (first, next) -> next.first - first.first >= 500 })
    }

    @Test
    fun anUpstreamFailureDrainsTheLatestSnapshotBeforeRethrowingTheSameFailure() = runTest {
        val expected = TestIOException("connection lost")
        val checkpoints = mutableListOf<String>()
        val failure = runCatching {
            flow {
                emit("partial")
                delay(100)
                emit("partial reasoning")
                delay(100)
                emit("partial reasoning preserved")
                throw expected
            }.checkpointProactiveSnapshots(500).collect { checkpoints += it }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertEquals("partial reasoning preserved", checkpoints.last())
        assertTrue(checkpoints.size < 3)
    }

    @Test
    fun aFailureBeforeAnyOutputRemainsTheOriginalFailure() = runTest {
        val expected = TestIOException("not connected")
        val checkpoints = mutableListOf<String>()
        val failure = runCatching {
            flow<String> { throw expected }
                .checkpointProactiveSnapshots(500)
                .collect { checkpoints += it }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertTrue(checkpoints.isEmpty())
    }

    @Test
    fun aStreamIdleTimeoutStillPreservesTheLastMergedPartialOutput() = runTest {
        val checkpoints = mutableListOf<String>()
        val checkpointTimes = mutableListOf<Long>()
        var transportClosed = false
        var transportClosedAt: Long? = null
        val failure = runCatching {
            flow {
                try {
                    emit("partial")
                    // Make progress before the idle deadline; the exact boundary may time out first.
                    delay(50)
                    emit("partial reasoning preserved")
                    awaitCancellation()
                } finally {
                    transportClosed = true
                    transportClosedAt = currentTime
                }
            }.guardProactiveStream(
                firstProgressTimeout = 1_000.milliseconds,
                idleTimeout = 100.milliseconds,
                totalTimeout = 10_000.milliseconds,
            ).checkpointProactiveSnapshots(500).collect {
                checkpoints += it
                checkpointTimes += currentTime
            }
        }.exceptionOrNull()

        assertTrue(failure is ProactiveStreamTimeoutException)
        assertEquals(
            ProactiveStreamTimeoutException.Phase.IDLE,
            (failure as ProactiveStreamTimeoutException).phase,
        )
        assertEquals("partial reasoning preserved", checkpoints.last())
        assertTrue(transportClosed)
        assertEquals(150L, transportClosedAt)
        assertEquals(listOf(0L, 500L), checkpointTimes)
    }

    @Test
    fun externalCancellationDropsQueuedSnapshotsAndClosesTheTransport() = runTest {
        val checkpoints = mutableListOf<String>()
        var transportClosed = false
        val collector = launch {
            callbackFlow {
                trySend("first")
                delay(100)
                trySend("queued")
                awaitClose { transportClosed = true }
            }.checkpointProactiveSnapshots(500).collect { checkpoints += it }
        }
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("first"), checkpoints)
        collector.cancel(CancellationException("user sent a new message"))
        collector.join()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(collector.isCancelled)
        assertTrue(transportClosed)
        assertEquals(listOf("first"), checkpoints)
    }

    @Test
    fun downstreamPersistenceFailureDoesNotFlushQueuedSnapshots() = runTest {
        val expected = TestIOException("cannot save")
        val checkpoints = mutableListOf<String>()
        var transportClosed = false
        val failure = runCatching {
            callbackFlow {
                trySend("first")
                awaitClose { transportClosed = true }
            }.checkpointProactiveSnapshots(500).collect {
                checkpoints += it
                throw expected
            }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertTrue(transportClosed)
        assertEquals(listOf("first"), checkpoints)
    }

    @Test
    fun upstreamCancellationIsNeverConvertedIntoDelayedSuccess() = runTest {
        val expected = CancellationException("upstream cancelled")
        val failure = runCatching {
            flow<String> { throw expected }
                .checkpointProactiveSnapshots(500)
                .collect { error("No snapshot should be emitted") }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertFalse(failure is IOException)
    }

    @Test
    fun aNonPositiveCheckpointIntervalIsRejected() = runTest {
        for (interval in listOf(0L, -1L)) {
            val failure = runCatching {
                flowOf("snapshot").checkpointProactiveSnapshots(interval).collect { }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    // Preserve strict exception-identity checks without coroutine debug copies changing the fixture.
    private class TestIOException(message: String) : IOException(message), CopyableThrowable<TestIOException> {
        override fun createCopy(): TestIOException? = null
    }
}
