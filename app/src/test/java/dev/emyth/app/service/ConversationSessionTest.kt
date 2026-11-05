package dev.emyth.app.service

import dev.emyth.app.data.model.Conversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionTest {
    @Test
    fun oldBackgroundCleanupCannotClearAReplacementForegroundJob() = runTest {
        val session = session(backgroundScope)
        val allowOldCleanup = CompletableDeferred<Unit>()
        val old = backgroundScope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { allowOldCleanup.await() }
            }
        }
        assertTrue(session.tryClaimGeneration(old))
        runCurrent()
        val foreground = backgroundScope.launch { awaitCancellation() }
        try {
            session.setJob(foreground)
            runCurrent()
            assertSame(foreground, session.getJob())
            allowOldCleanup.complete(Unit)
            runCurrent()
            assertTrue(old.isCompleted)
            assertSame(foreground, session.getJob())
            assertTrue(session.isGenerating)
        } finally {
            allowOldCleanup.complete(Unit)
            session.cleanup()
        }
    }

    @Test
    fun backgroundClaimsCannotReplaceAnActiveForegroundGeneration() = runTest {
        val session = session(backgroundScope)
        val foreground = Job()
        val background = Job()
        try {
            session.setJob(foreground)
            assertFalse(session.tryClaimGeneration(background))
            assertSame(foreground, session.getJob())
            assertTrue(foreground.isActive)
        } finally {
            background.cancel()
            session.cleanup()
        }
    }

    @Test
    fun completingTheCurrentJobClearsItAndAllowsAnotherClaim() = runTest {
        val session = session(backgroundScope)
        val first = Job()
        assertTrue(session.tryClaimGeneration(first))
        first.complete()
        assertNull(session.getJob())
        val next = Job()
        try {
            assertTrue(session.tryClaimGeneration(next))
            assertSame(next, session.getJob())
        } finally {
            session.cleanup()
        }
    }

    @Test
    fun reinstallingTheSameJobDoesNotCancelIt() = runTest {
        val session = session(backgroundScope)
        val job = Job()
        try {
            session.setJob(job)
            session.setJob(job)
            assertTrue(job.isActive)
            assertSame(job, session.getJob())
        } finally {
            session.cleanup()
        }
    }

    @Test
    fun anAlreadyCompletedJobCannotClaimBackgroundOwnership() = runTest {
        val session = session(backgroundScope)
        val completed = Job().apply { complete() }
        assertFalse(session.tryClaimGeneration(completed))
        assertNull(session.getJob())
    }

    @Test
    fun scheduledWakeWaitsAndRegistersItsOuterJobWhenTheSessionBecomesAvailable() = runTest {
        val session = session(backgroundScope)
        val foreground = Job()
        val allowWakeToFinish = CompletableDeferred<Unit>()
        var claimed: Boolean? = null
        session.setJob(foreground)
        val wake = backgroundScope.launch {
            claimed = session.claimGenerationWhenAvailable(currentCoroutineContext().job, 2_000L)
            if (claimed == true) allowWakeToFinish.await()
        }
        try {
            runCurrent()
            assertNull(claimed)
            assertSame(foreground, session.getJob())
            foreground.complete()
            runCurrent()
            assertEquals(true, claimed)
            assertSame(wake, session.getJob())
            assertTrue(wake.isActive)
            allowWakeToFinish.complete(Unit)
            runCurrent()
            assertNull(session.getJob())
        } finally {
            allowWakeToFinish.complete(Unit)
            wake.cancel()
            session.cleanup()
        }
    }

    @Test
    fun scheduledWakeTimeoutDoesNotCancelTheExistingGeneration() = runTest {
        val session = session(backgroundScope)
        val foreground = Job()
        var claimed: Boolean? = null
        session.setJob(foreground)
        val wake = backgroundScope.launch {
            claimed = session.claimGenerationWhenAvailable(currentCoroutineContext().job, 2_000L)
        }
        try {
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(false, claimed)
            assertTrue(wake.isCompleted)
            assertSame(foreground, session.getJob())
            assertTrue(foreground.isActive)
        } finally {
            wake.cancel()
            session.cleanup()
        }
    }

    @Test
    fun scheduledWakeWaitsForACancelledOwnersFinalizerBeforeClaiming() = runTest {
        val session = session(backgroundScope)
        val allowOldCleanup = CompletableDeferred<Unit>()
        val allowWakeToFinish = CompletableDeferred<Unit>()
        val old = backgroundScope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { allowOldCleanup.await() }
            }
        }
        session.setJob(old)
        runCurrent()
        old.cancel()
        runCurrent()
        var claimed: Boolean? = null
        val wake = backgroundScope.launch {
            claimed = session.claimGenerationWhenAvailable(currentCoroutineContext().job, 2_000L)
            if (claimed == true) allowWakeToFinish.await()
        }
        try {
            runCurrent()
            assertNull(claimed)
            assertSame(old, session.getJob())
            allowOldCleanup.complete(Unit)
            runCurrent()
            assertTrue(old.isCompleted)
            assertEquals(true, claimed)
            assertSame(wake, session.getJob())
        } finally {
            allowOldCleanup.complete(Unit)
            allowWakeToFinish.complete(Unit)
            wake.cancel()
            session.cleanup()
        }
    }

    @Test
    fun cancellingAWaitingWakeCannotClaimTheSessionLater() = runTest {
        val session = session(backgroundScope)
        val foreground = Job()
        var claimed: Boolean? = null
        session.setJob(foreground)
        val wake = backgroundScope.launch {
            claimed = session.claimGenerationWhenAvailable(currentCoroutineContext().job, 2_000L)
        }
        try {
            runCurrent()
            wake.cancel()
            runCurrent()
            assertTrue(wake.isCancelled)
            assertNull(claimed)
            assertTrue(foreground.isActive)
            foreground.complete()
            runCurrent()
            assertNull(session.getJob())
            assertNull(claimed)
        } finally {
            wake.cancel()
            session.cleanup()
        }
    }

    @Test
    fun theCallersDeadlineIsNotReportedAsTheWakeQueueTimeout() = runTest {
        val session = session(backgroundScope)
        val foreground = Job()
        var claimed: Boolean? = null
        var callerTimedOut = false
        session.setJob(foreground)
        val wake = backgroundScope.launch {
            val outerJob = currentCoroutineContext().job
            try {
                withTimeout(250L) {
                    claimed = session.claimGenerationWhenAvailable(outerJob, 2_000L)
                }
            } catch (_: TimeoutCancellationException) {
                callerTimedOut = true
            }
        }
        try {
            runCurrent()
            advanceTimeBy(250L)
            runCurrent()
            assertTrue(callerTimedOut)
            assertNull(claimed)
            assertSame(foreground, session.getJob())
            assertTrue(foreground.isActive)
        } finally {
            wake.cancel()
            session.cleanup()
        }
    }

    @Test
    fun anActiveJobThatAlreadyOwnsTheSessionCanClaimAgainWithoutWaiting() = runTest {
        val session = session(backgroundScope)
        val owner = Job()
        try {
            assertTrue(session.tryClaimGeneration(owner))
            assertTrue(session.claimGenerationWhenAvailable(owner, 2_000L))
            assertSame(owner, session.getJob())
            assertTrue(owner.isActive)
        } finally {
            session.cleanup()
        }
    }

    @Test
    fun clearingAJobCancelsItAndEventuallyReleasesAnIdleSession() = runTest {
        var idleCalls = 0
        val session = session(backgroundScope) { idleCalls++ }
        val job = Job()
        session.setJob(job)
        session.setJob(null)
        assertTrue(job.isCancelled)
        assertNull(session.getJob())
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, idleCalls)
        session.cleanup()
    }

    private fun session(scope: CoroutineScope, onIdle: () -> Unit = {}): ConversationSession {
        val id = Uuid.random()
        return ConversationSession(id, Conversation.ofId(id, assistantId = Uuid.random()), scope) { onIdle() }
    }
}
