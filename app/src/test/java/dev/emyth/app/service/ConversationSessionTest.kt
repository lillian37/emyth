package dev.emyth.app.service

import dev.emyth.app.data.model.Conversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
