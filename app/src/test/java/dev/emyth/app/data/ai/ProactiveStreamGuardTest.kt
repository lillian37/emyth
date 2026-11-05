package dev.emyth.app.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProactiveStreamGuardTest {
    @Test
    fun aSilentFirstResponseTimesOutAndClosesItsTransport() = runTest {
        var closed = false
        val failure = runCatching {
            callbackFlow<String> { awaitClose { closed = true } }
                .guardProactiveStream(1.seconds, 2.seconds, 10.seconds)
                .toList()
        }.exceptionOrNull()

        assertTimeout(ProactiveStreamTimeoutException.Phase.FIRST_PROGRESS, failure)
        assertEquals(1_000L, currentTime)
        assertTrue(closed)
    }

    @Test
    fun aPartiallyReceivedStreamUsesTheIdleDeadlineAndPreservesItsValues() = runTest {
        var closed = false
        val received = mutableListOf<String>()
        val failure = runCatching {
            flow {
                try {
                    delay(1.seconds)
                    emit("partial reasoning")
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }.guardProactiveStream(5.seconds, 2.seconds, 20.seconds)
                .collect { received += it }
        }.exceptionOrNull()

        assertTimeout(ProactiveStreamTimeoutException.Phase.IDLE, failure)
        assertEquals(3_000L, currentTime)
        assertEquals(listOf("partial reasoning"), received)
        assertTrue(closed)
    }

    @Test
    fun heartbeatsDoNotExtendTheWaitForFirstProgress() = runTest {
        val received = mutableListOf<String>()
        val failure = runCatching {
            flow {
                while (true) {
                    emit("heartbeat")
                    delay(200.milliseconds)
                }
            }.guardProactiveStream(1.seconds, 5.seconds, 20.seconds) { it != "heartbeat" }
                .collect { received += it }
        }.exceptionOrNull()

        assertTimeout(ProactiveStreamTimeoutException.Phase.FIRST_PROGRESS, failure)
        assertEquals(1_000L, currentTime)
        assertTrue(received.isNotEmpty())
        assertTrue(received.all { it == "heartbeat" })
    }

    @Test
    fun heartbeatsAfterReasoningDoNotExtendTheIdleDeadline() = runTest {
        val received = mutableListOf<String>()
        val failure = runCatching {
            flow {
                emit("reasoning")
                while (true) {
                    delay(200.milliseconds)
                    emit("heartbeat")
                }
            }.guardProactiveStream(5.seconds, 1.seconds, 20.seconds) { it != "heartbeat" }
                .collect { received += it }
        }.exceptionOrNull()

        assertTimeout(ProactiveStreamTimeoutException.Phase.IDLE, failure)
        assertEquals(1_000L, currentTime)
        assertEquals("reasoning", received.first())
        assertTrue(received.drop(1).all { it == "heartbeat" })
    }

    @Test
    fun continuousProgressStillHasAnOverallBoundAndNoValuesAreDropped() = runTest {
        var closed = false
        val received = mutableListOf<Int>()
        val failure = runCatching {
            flow {
                try {
                    var value = 0
                    while (true) {
                        emit(value++)
                        delay(200.milliseconds)
                    }
                } finally {
                    closed = true
                }
            }.guardProactiveStream(1.seconds, 1.seconds, 1_500.milliseconds)
                .collect { received += it }
        }.exceptionOrNull()

        assertTimeout(ProactiveStreamTimeoutException.Phase.TOTAL, failure)
        assertEquals(1_500L, currentTime)
        assertEquals((0..7).toList(), received)
        assertTrue(closed)
    }

    @Test
    fun normalCompletionForwardsProgressHeartbeatsAndNullValues() = runTest {
        val received = flowOf<String?>(null, "heartbeat", "reasoning", "answer", "heartbeat")
            .guardProactiveStream(1.seconds, 1.seconds, 5.seconds) {
                it != null && it != "heartbeat"
            }.toList()

        assertEquals(listOf(null, "heartbeat", "reasoning", "answer", "heartbeat"), received)
    }

    @Test
    fun deliveringUsefulChunksDoesNotConsumeTheUpstreamIdleBudget() = runTest {
        val received = mutableListOf<Int>()
        flowOf(1, 2).guardProactiveStream(1.seconds, 1.seconds, 10.seconds).collect {
            delay(2.seconds)
            received += it
        }

        assertEquals(listOf(1, 2), received)
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun stalledDownstreamWorkStillHasTheOverallDeadline() = runTest {
        val failure = runCatching {
            flowOf("reasoning").guardProactiveStream(1.seconds, 1.seconds, 3.seconds).collect {
                delay(20.seconds)
            }
        }.exceptionOrNull()

        assertTimeout(ProactiveStreamTimeoutException.Phase.TOTAL, failure)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun emptySuccessfulCompletionDoesNotWaitForAFirstChunk() = runTest {
        assertEquals(emptyList<String>(), emptyFlow<String>().guardProactiveStream().toList())
        assertEquals(0L, currentTime)
    }

    @Test
    fun upstreamErrorsRemainTheOriginalErrors() = runTest {
        val expected = IOException("connection failed")
        val received = mutableListOf<String>()
        val failure = runCatching {
            flow {
                emit("partial reasoning")
                throw expected
            }.guardProactiveStream().collect { received += it }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertEquals(listOf("partial reasoning"), received)
    }

    @Test
    fun externalCancellationIsNotReportedAsTimeoutAndClosesTheCallbackFlow() = runTest {
        var closed = false
        var failure: Throwable? = null
        val received = mutableListOf<String>()
        val collector = launch {
            try {
                callbackFlow {
                    trySend("reasoning")
                    awaitClose { closed = true }
                }.guardProactiveStream().collect { received += it }
            } catch (error: Throwable) {
                failure = error
                throw error
            }
        }
        runCurrent()
        collector.cancel(CancellationException("user started another request"))
        collector.join()

        assertTrue(collector.isCancelled)
        assertTrue(failure is CancellationException)
        assertFalse(failure is ProactiveStreamTimeoutException)
        assertEquals(listOf("reasoning"), received)
        assertTrue(closed)
    }

    @Test
    fun downstreamFailureAlsoClosesTheTransport() = runTest {
        var closed = false
        val expected = IOException("cannot persist message")
        val failure = runCatching {
            callbackFlow {
                trySend("reasoning")
                awaitClose { closed = true }
            }.guardProactiveStream().collect { throw expected }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertTrue(closed)
    }

    @Test
    fun aCallerDeadlineIsNotConvertedIntoAProactiveTimeout() = runTest {
        var closed = false
        val failure = runCatching {
            withTimeout(500.milliseconds) {
                callbackFlow<String> { awaitClose { closed = true } }
                    .guardProactiveStream(1.seconds, 1.seconds, 5.seconds)
                    .toList()
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertFalse(failure is ProactiveStreamTimeoutException)
        assertEquals(500L, currentTime)
        assertTrue(closed)
    }

    private fun assertTimeout(phase: ProactiveStreamTimeoutException.Phase, failure: Throwable?) {
        assertTrue(failure is ProactiveStreamTimeoutException)
        assertFalse(failure is CancellationException)
        assertEquals(phase, (failure as ProactiveStreamTimeoutException).phase)
        assertTrue(failure.message.orEmpty().isNotBlank())
    }
}
