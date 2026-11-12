package dev.emyth.app.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveExecutionStateTest {
    @Test
    fun onlyPreparationAndModelToolStagesAreNonterminal() {
        assertEquals(
            setOf(
                ProactiveExecutionStatus.PREPARING,
                ProactiveExecutionStatus.REQUESTING,
                ProactiveExecutionStatus.STREAMING,
                ProactiveExecutionStatus.RUNNING_TOOLS,
            ),
            ProactiveExecutionStatus.entries.filterNot { it.isTerminal }.toSet(),
        )
        assertTrue(ProactiveExecutionStatus.COMPLETED.isTerminal)
        assertTrue(ProactiveExecutionStatus.SKIPPED.isTerminal)
        assertTrue(ProactiveExecutionStatus.BUSY.isTerminal)
        assertTrue(ProactiveExecutionStatus.VOICE_CALL.isTerminal)
        assertTrue(ProactiveExecutionStatus.FAILED.isTerminal)
        assertTrue(ProactiveExecutionStatus.INTERRUPTED.isTerminal)
        assertTrue(ProactiveExecutionStatus.START_FAILED.isTerminal)
    }

    @Test
    fun modelAndToolRoundsMayProgressBackToAnotherRequest() {
        val initial = execution()
        var history = listOf(initial)
        val stages = listOf(
            ProactiveExecutionStatus.REQUESTING,
            ProactiveExecutionStatus.STREAMING,
            ProactiveExecutionStatus.RUNNING_TOOLS,
            ProactiveExecutionStatus.REQUESTING,
        )

        stages.forEachIndexed { index, stage ->
            history = history.updateProactiveExecution(initial.id, stage, 200L + index)
            assertEquals(stage, history.single().status)
        }

        assertEquals(initial.startedAt, history.single().startedAt)
        assertEquals(initial.scheduledAt, history.single().scheduledAt)
        assertFalse(history.single().status.isTerminal)
    }

    @Test
    fun everyTerminalResultRejectsLateStagesAndConflictingTerminalResults() {
        ProactiveExecutionStatus.entries.filter { it.isTerminal }.forEach { terminal ->
            val history = listOf(execution(status = terminal))
            ProactiveExecutionStatus.entries.filter { it != terminal }.forEach { late ->
                assertSame(history, history.updateProactiveExecution("run", late, 500L, detail = "late callback"))
            }
        }
    }

    @Test
    fun completedResultMayBeEnrichedWithNotificationMetadata() {
        val history = listOf(execution(status = ProactiveExecutionStatus.COMPLETED))

        val updated = history.updateProactiveExecution(
            "run", ProactiveExecutionStatus.COMPLETED, 200L,
            conversationId = "conversation", detail = "Reply saved; notification submitted to the system",
        ).single()

        assertEquals(ProactiveExecutionStatus.COMPLETED, updated.status)
        assertEquals("conversation", updated.conversationId)
        assertEquals("Reply saved; notification submitted to the system", updated.detail)
        assertEquals(200L, updated.updatedAt)
    }

    @Test
    fun nullMetadataDoesNotErasePreviouslyRecordedFields() {
        val initial = execution().copy(conversationId = "conversation", detail = "Preparing tools")

        val updated = listOf(initial).updateProactiveExecution(
            "run", ProactiveExecutionStatus.REQUESTING, 200L,
        ).single()

        assertEquals("conversation", updated.conversationId)
        assertEquals("Preparing tools", updated.detail)
    }

    @Test
    fun lateNonterminalCallbacksDoNotRegressAnActiveExecution() {
        val history = listOf(execution(status = ProactiveExecutionStatus.STREAMING).copy(updatedAt = 200L))

        assertSame(history, history.updateProactiveExecution("run", ProactiveExecutionStatus.REQUESTING, 150L))
    }

    @Test
    fun sameMillisecondCallbacksCanStillAdvanceTheExecution() {
        val updated = listOf(execution()).updateProactiveExecution(
            "run", ProactiveExecutionStatus.REQUESTING, 100L,
        ).single()

        assertEquals(ProactiveExecutionStatus.REQUESTING, updated.status)
        assertEquals(100L, updated.updatedAt)
    }

    @Test
    fun aClockRollbackDoesNotLoseTheTerminalResultOrMoveItsTimestampBackwards() {
        val initial = execution(status = ProactiveExecutionStatus.STREAMING).copy(updatedAt = 300L)

        val completed = listOf(initial).updateProactiveExecution(
            "run", ProactiveExecutionStatus.COMPLETED, 200L,
        ).single()
        val enriched = listOf(completed).updateProactiveExecution(
            "run", ProactiveExecutionStatus.COMPLETED, 250L, detail = "Notification unavailable",
        ).single()

        assertEquals(ProactiveExecutionStatus.COMPLETED, enriched.status)
        assertEquals(300L, enriched.updatedAt)
        assertEquals("Notification unavailable", enriched.detail)
    }

    @Test
    fun callbacksAreMatchedByExecutionIdEvenWhenTheirScheduledTimesMatch() {
        val first = execution(id = "first", status = ProactiveExecutionStatus.COMPLETED)
        val second = execution(id = "second")

        val updated = listOf(second, first).updateProactiveExecution(
            "second", ProactiveExecutionStatus.SKIPPED, 200L,
        )

        assertEquals(ProactiveExecutionStatus.SKIPPED, updated.first().status)
        assertSame(first, updated.last())
    }

    @Test
    fun unknownAndClearedExecutionsAreNeverReinsertedByLateCallbacks() {
        val history = listOf(execution())
        val cleared = emptyList<ProactiveExecution>()

        assertSame(history, history.updateProactiveExecution("missing", ProactiveExecutionStatus.FAILED, 200L))
        assertSame(cleared, cleared.updateProactiveExecution("run", ProactiveExecutionStatus.COMPLETED, 200L))
    }

    @Test
    fun updatesKeepOnlyTheEightMostRecentEntriesWithoutChangingTheirOrder() {
        val history = (10 downTo 1).map { execution(id = "run-$it").copy(startedAt = it.toLong()) }

        val updated = history.updateProactiveExecution("run-10", ProactiveExecutionStatus.REQUESTING, 200L)

        assertEquals(PROACTIVE_EXECUTION_HISTORY_LIMIT, updated.size)
        assertEquals((10 downTo 3).map { "run-$it" }, updated.map { it.id })
    }

    @Test
    fun processRecoveryMarksOnlyUnfinishedExecutionsAsInterruptedWithoutMovingTimeBackwards() {
        val history = ProactiveExecutionStatus.entries.filterNot { it.isTerminal }.mapIndexed { index, status ->
            execution(id = "run-$index", status = status).copy(
                updatedAt = 100L + index * 100L,
                conversationId = "conversation-$index",
            )
        }

        val recovered = history.recoverUnfinishedProactiveExecutions(250L)

        assertEquals(history.size, recovered.size)
        history.zip(recovered).forEach { (previous, current) ->
            assertEquals(ProactiveExecutionStatus.INTERRUPTED, current.status)
            assertEquals(maxOf(previous.updatedAt, 250L), current.updatedAt)
            assertEquals(previous.id, current.id)
            assertEquals(previous.scheduledAt, current.scheduledAt)
            assertEquals(previous.startedAt, current.startedAt)
            assertEquals(previous.conversationId, current.conversationId)
            assertEquals("上次进程结束前未记录最终结果，无法确认本轮已完成", current.detail)
        }
    }

    @Test
    fun processRecoveryPreservesEveryTerminalResultAndAnEmptyHistory() {
        val finished = ProactiveExecutionStatus.entries.filter { it.isTerminal }.map {
            execution(id = it.name, status = it)
        }
        val empty = emptyList<ProactiveExecution>()

        assertSame(finished, finished.recoverUnfinishedProactiveExecutions(500L))
        assertSame(empty, empty.recoverUnfinishedProactiveExecutions(500L))
    }

    private fun execution(
        id: String = "run",
        status: ProactiveExecutionStatus = ProactiveExecutionStatus.PREPARING,
    ) = ProactiveExecution(
        id = id,
        scheduledAt = 50L,
        startedAt = 100L,
        updatedAt = 100L,
        status = status,
    )
}
