package dev.emyth.app.data.editprotection

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditProtectionEngineTest {
    @Test
    fun unconfiguredProtectionPreservesExistingHumanWritesWithoutInventingAuditEvents() = runTest {
        val fixture = Fixture()
        var saved = false

        val value = fixture.engine.mutate(listOf(change("a"))) { saved = true; "saved" }

        assertTrue(saved)
        assertEquals("saved", value)
        assertFalse(fixture.engine.status("a").enabled)
        assertTrue(fixture.engine.status("a").editingAllowed)
        assertTrue(fixture.engine.events("a").isEmpty())
        assertTrue(fixture.engine.pendingAssistantIds().isEmpty())
    }

    @Test
    fun oneLockedOwnerRejectsAnEntireBatchBeforeAnyPersistenceRuns() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("locked", true)
        var writes = 0

        val failure = runCatching {
            fixture.engine.mutate(listOf(change("unlocked"), change("locked"))) { writes++ }
        }.exceptionOrNull()

        assertTrue(failure is ProtectedEditDeniedException)
        assertEquals("locked", (failure as ProtectedEditDeniedException).assistantId)
        assertEquals(0, writes)
        assertTrue(fixture.engine.events("unlocked").isEmpty())
        assertEquals(EditProtectionEventKind.BLOCKED, fixture.engine.events("locked").first().kind)
        assertEquals(listOf("locked"), fixture.engine.pendingAssistantIds())
        assertFalse(fixture.snapshot.events.any { it.kind == EditProtectionEventKind.CHANGED })
    }

    @Test
    fun assistantGrantIsExactlyFifteenMinutesAndDoesNotWakeItself() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)

        val granted = fixture.engine.grantFromAssistant("a", "chat", "Discussed in this chat")

        assertTrue(granted.enabled)
        assertTrue(granted.editingAllowed)
        assertEquals(900_000L, granted.remainingMillis)
        assertEquals(fixture.wall + 900_000L, granted.grantedUntilMillis)
        assertEquals("chat", fixture.engine.events("a").first().conversationId)
        assertEquals(EditProtectionEventKind.GRANTED, fixture.engine.events("a").first().kind)
        assertTrue(fixture.engine.pendingAssistantIds().isEmpty())
    }

    @Test
    fun repeatedGrantWithinAnActiveWindowDoesNotMoveItsDeadline() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        val first = fixture.engine.grantFromAssistant("a")
        fixture.advance(40_000L)

        val repeated = fixture.engine.grantFromAssistant("a", reason = "Repeated tool delivery")

        assertEquals(first.grantedUntilMillis, repeated.grantedUntilMillis)
        assertEquals(860_000L, repeated.remainingMillis)
        assertEquals(1, fixture.engine.events("a").count { it.kind == EditProtectionEventKind.GRANTED })
    }

    @Test
    fun anExpiredWindowRejectsTheNextSaveWithoutRunningItsBlock() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.grantFromAssistant("a")
        fixture.advance(EDIT_PERMISSION_DURATION_MILLIS)
        var saved = false

        val failure = runCatching { fixture.engine.mutate(listOf(change("a"))) { saved = true } }
            .exceptionOrNull()

        assertFalse(fixture.engine.status("a").editingAllowed)
        assertNull(fixture.engine.status("a").grantedUntilMillis)
        assertEquals(0L, fixture.engine.status("a").remainingMillis)
        assertTrue(failure is ProtectedEditDeniedException)
        assertFalse(saved)
    }

    @Test
    fun aWallClockRollbackBeforeIssueTimeCannotRevivePermission() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.grantFromAssistant("a")
        fixture.wall--

        assertFalse(fixture.engine.status("a").editingAllowed)
    }

    @Test
    fun elapsedTimeStillExpiresAWindowWhenTheWallClockHasAdvancedLess() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.grantFromAssistant("a")
        fixture.wall += 1_000L
        fixture.elapsed += EDIT_PERMISSION_DURATION_MILLIS

        assertFalse(fixture.engine.status("a").editingAllowed)
    }

    @Test
    fun elapsedClockRollbackAndAnotherBootInvalidateTheGrant() = runTest {
        for (restart in listOf(false, true)) {
            val fixture = Fixture()
            fixture.engine.setEnabled("a", true)
            fixture.engine.grantFromAssistant("a")
            if (restart) fixture.boot = "boot-after-restart" else fixture.elapsed--

            assertFalse(fixture.engine.status("a").editingAllowed)
            assertEquals(0L, fixture.engine.status("a").remainingMillis)
        }
    }

    @Test
    fun aNewAppProcessRetainsOnlyTheUnexpiredRemainderOfTheSameBootGrant() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        val original = fixture.engine.grantFromAssistant("a")
        fixture.advance(30_000L)
        val restarted = fixture.newEngine("new-process")

        restarted.recoverAfterProcessStart()

        assertEquals(original.grantedUntilMillis, restarted.status("a").grantedUntilMillis)
        assertEquals(870_000L, restarted.status("a").remainingMillis)
    }

    @Test
    fun changedIsRecordedOnlyAfterTheActualSaveFinishes() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.grantFromAssistant("a")
        var savedValue = "before"

        fixture.engine.mutate(listOf(change("a", conversationId = "chat", summary = "before -> after"))) {
            assertEquals(EditProtectionEventKind.CHANGE_PENDING, fixture.engine.events("a").first().kind)
            assertFalse(fixture.engine.events("a").any { it.kind == EditProtectionEventKind.CHANGED })
            savedValue = "after"
        }

        assertEquals("after", savedValue)
        val recorded = fixture.engine.events("a").first()
        assertEquals(EditProtectionEventKind.CHANGED, recorded.kind)
        assertEquals("chat", recorded.conversationId)
        assertTrue(recorded.summary.contains("before -> after"))
        assertEquals(listOf("a"), fixture.engine.pendingAssistantIds())
    }

    @Test
    fun aFailingSaveNeverProducesAChangedEvent() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.grantFromAssistant("a")
        val problem = IOException("Write did not finish")

        val failure = runCatching {
            fixture.engine.mutate(listOf(change("a"))) { throw problem }
        }.exceptionOrNull()

        assertSame(problem, failure)
        assertEquals(EditProtectionEventKind.CHANGE_FAILED, fixture.engine.events("a").first().kind)
        assertFalse(fixture.engine.events("a").any { it.kind == EditProtectionEventKind.CHANGED })
    }

    @Test
    fun aTrustedAssistantMayWriteItsOwnProtectedStateWithoutAHumanGrant() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        var saves = 0

        fixture.engine.mutate(listOf(change("a")), EditActor.Assistant("a")) { saves++ }

        assertEquals(1, saves)
        assertFalse(fixture.engine.status("a").editingAllowed)
        assertTrue(fixture.engine.pendingAssistantIds().isEmpty())
    }

    @Test
    fun assistantIdentityCannotAuthorizeWritesToAnotherAssistant() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.setEnabled("b", true)
        fixture.engine.grantFromAssistant("b")
        var saves = 0

        val failure = runCatching {
            fixture.engine.mutate(listOf(change("a"), change("b")), EditActor.Assistant("a")) { saves++ }
        }.exceptionOrNull()

        assertTrue(failure is ProtectedEditDeniedException)
        assertEquals("b", (failure as ProtectedEditDeniedException).assistantId)
        assertEquals(0, saves)
        assertEquals(EditProtectionEventKind.BLOCKED, fixture.engine.events("b").first().kind)
    }

    @Test
    fun closingProtectionRequiresAValidWindowAndProducesANotificationEvent() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)

        val failure = runCatching { fixture.engine.setEnabled("a", false) }.exceptionOrNull()
        assertTrue(failure is ProtectedEditDeniedException)
        assertTrue(fixture.engine.status("a").enabled)

        fixture.engine.grantFromAssistant("a")
        fixture.engine.setEnabled("a", false)

        assertFalse(fixture.engine.status("a").enabled)
        assertTrue(fixture.engine.status("a").editingAllowed)
        assertEquals(EditProtectionEventKind.DISABLED, fixture.engine.events("a").first().kind)
        assertTrue(fixture.engine.status("a").hasPendingWake)
    }

    @Test
    fun emergencyRecoveryTemporarilyAllowsEditingAndKeepsProtectionEnabled() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)

        val recovered = fixture.engine.emergencyRecover("a", "chat")

        assertTrue(recovered.enabled)
        assertTrue(recovered.editingAllowed)
        assertEquals(EDIT_PERMISSION_DURATION_MILLIS, recovered.remainingMillis)
        assertEquals(EditProtectionEventKind.RECOVERED, fixture.engine.events("a").first().kind)
        assertEquals(listOf("a"), fixture.engine.pendingAssistantIds())
        fixture.advance(EDIT_PERMISSION_DURATION_MILLIS)
        assertFalse(fixture.engine.status("a").editingAllowed)
        assertTrue(fixture.engine.status("a").enabled)
    }

    @Test
    fun repeatedRequestsAreDeduplicatedForThirtySecondsButDifferentScopesAreIndependent() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "chat")
        fixture.advance(29_999L)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "chat")
        assertEquals(1, fixture.engine.events("a").count { it.kind == EditProtectionEventKind.BLOCKED })

        fixture.engine.requestHumanEdit("a", ProtectedEditScope.CONVERSATION_PROMPT, "chat")
        assertEquals(2, fixture.engine.events("a").count { it.kind == EditProtectionEventKind.BLOCKED })
        fixture.advance(1L)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "chat")
        assertEquals(3, fixture.engine.events("a").count { it.kind == EditProtectionEventKind.BLOCKED })
    }

    @Test
    fun eventQueriesRespectBothAssistantAndConversationOwnership() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.setEnabled("b", true)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "a-chat-1")
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "a-chat-2")
        fixture.engine.requestHumanEdit("b", ProtectedEditScope.ASSISTANT_PROMPT, "b-chat")

        val visible = fixture.engine.events("a", "a-chat-1")

        assertTrue(visible.all { it.assistantId == "a" })
        assertEquals(listOf("a-chat-1"), visible.filter {
            it.kind == EditProtectionEventKind.BLOCKED
        }.map { it.conversationId })
        assertTrue(visible.any { it.kind == EditProtectionEventKind.ENABLED && it.conversationId == null })
    }

    @Test
    fun persistedHistoryRemainsBoundedPerAssistantAndAcrossAssistants() = runTest {
        val fixture = Fixture()
        repeat(5) { owner ->
            val id = "assistant-$owner"
            fixture.engine.setEnabled(id, true)
            repeat(40) { index ->
                fixture.engine.requestHumanEdit(id, ProtectedEditScope.BUILTIN_MEMORY, "chat-$index")
                fixture.advance(30_001L)
            }
        }

        assertEquals(EDIT_EVENT_LIMIT, fixture.snapshot.events.size)
        assertTrue(fixture.snapshot.events.groupBy { it.assistantId }.values.all {
            it.size <= EDIT_EVENTS_PER_ASSISTANT
        })
        assertTrue(fixture.snapshot.events.all { it.summary.length <= 1_536 })
    }

    @Test
    fun oneWakeClaimConsumesOnlyOneReceiptAndEnforcesTheCooldown() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "first")
        val first = fixture.engine.claimWake("a")!!

        assertNull(fixture.engine.claimWake("a"))
        assertEquals(listOf("first"), first.events.map { it.conversationId })
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.CONVERSATION_PROMPT, "second")
        fixture.engine.finishWake("a", first.id)

        assertTrue(fixture.engine.status("a").hasPendingWake)
        assertTrue(fixture.engine.pendingAssistantIds().isEmpty())
        fixture.advance(EDIT_WAKE_COOLDOWN_MILLIS)
        assertEquals(listOf("a"), fixture.engine.pendingAssistantIds())
        val second = fixture.engine.claimWake("a")!!
        assertNotEquals(first.id, second.id)
        assertEquals(listOf("second"), second.events.map { it.conversationId })
    }

    @Test
    fun deliveryCanRetryBeforeTheModelRequestStartsButNotAfterItStarts() = runTest {
        for (started in listOf(false, true)) {
            val fixture = Fixture()
            fixture.engine.setEnabled("a", true)
            fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT)
            val first = fixture.engine.claimWake("a")!!
            if (started) {
                assertTrue(fixture.engine.markWakeRequestStarted("a", first.id))
                assertFalse(fixture.engine.markWakeRequestStarted("a", first.id))
            }

            fixture.engine.finishWake("a", first.id, retryBeforeStart = true)
            fixture.advance(EDIT_WAKE_COOLDOWN_MILLIS)

            assertEquals(!started, fixture.engine.status("a").hasPendingWake)
            val second = fixture.engine.claimWake("a")
            if (started) assertNull(second) else {
                assertNotEquals(first.id, second!!.id)
                assertEquals(first.events, second.events)
            }
        }
    }

    @Test
    fun staleReceiptsCannotStartOrFinishAnotherWake() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT)
        val claimed = fixture.engine.claimWake("a")!!

        assertFalse(fixture.engine.markWakeRequestStarted("a", "stale-id"))
        fixture.engine.finishWake("a", "stale-id", retryBeforeStart = true)

        assertTrue(fixture.engine.markWakeRequestStarted("a", claimed.id))
        assertNull(fixture.engine.claimWake("a"))
    }

    @Test
    fun processRecoveryRetriesOnlyClaimsThatNeverStartedAModelRequest() = runTest {
        for (started in listOf(false, true)) {
            val fixture = Fixture()
            fixture.engine.setEnabled("a", true)
            fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "chat")
            val claimed = fixture.engine.claimWake("a")!!
            if (started) fixture.engine.markWakeRequestStarted("a", claimed.id)
            val restarted = fixture.newEngine("new-process")

            restarted.recoverAfterProcessStart()
            fixture.advance(EDIT_WAKE_COOLDOWN_MILLIS)

            assertEquals(!started, restarted.status("a").hasPendingWake)
            val retry = restarted.claimWake("a")
            if (started) assertNull(retry) else assertEquals(claimed.events, retry!!.events)
        }
    }

    @Test
    fun newEditsDuringAStartedRequestSurviveItsProcessRecovery() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "original")
        val old = fixture.engine.claimWake("a")!!
        fixture.engine.markWakeRequestStarted("a", old.id)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.BUILTIN_MEMORY, "new-edit")
        val restarted = fixture.newEngine("new-process")

        restarted.recoverAfterProcessStart()
        fixture.advance(EDIT_WAKE_COOLDOWN_MILLIS)
        val pending = restarted.claimWake("a")!!

        assertEquals(listOf("new-edit"), pending.events.map { it.conversationId })
        assertFalse(pending.events.any { it.id in old.events.map { oldEvent -> oldEvent.id } })
    }

    @Test
    fun revocationCannotRaceBetweenTheGrantCheckAndTheProtectedSave() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.grantFromAssistant("a")
        val saveEntered = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        var writes = 0
        val mutation = launch {
            fixture.engine.mutate(listOf(change("a"))) {
                saveEntered.complete(Unit)
                finishSave.await()
                writes++
            }
        }
        saveEntered.await()
        val revocation = async { fixture.engine.revokeFromAssistant("a") }
        runCurrent()
        assertFalse(revocation.isCompleted)

        finishSave.complete(Unit)
        mutation.join()
        revocation.await()

        assertEquals(1, writes)
        assertFalse(fixture.engine.status("a").editingAllowed)
        assertEquals(1, fixture.engine.events("a").count { it.kind == EditProtectionEventKind.CHANGED })
        assertTrue(runCatching { fixture.engine.mutate(listOf(change("a"))) { writes++ } }
            .exceptionOrNull() is ProtectedEditDeniedException)
        assertEquals(1, writes)
    }

    @Test
    fun cooldownReportsARealDelaySoNewEditsCannotStayQueuedWithoutATimer() = runTest {
        val fixture = Fixture()
        assertNull(fixture.engine.nextWakeDelayMillis())
        fixture.engine.setEnabled("a", true)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "first")
        assertEquals(0L, fixture.engine.nextWakeDelayMillis())
        val claimed = fixture.engine.claimWake("a")!!
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.BUILTIN_MEMORY, "second")
        // In-flight owners are not dispatched twice, even when another event has arrived.
        assertNull(fixture.engine.nextWakeDelayMillis())
        fixture.engine.finishWake("a", claimed.id)

        assertEquals(60_000L, fixture.engine.nextWakeDelayMillis())
        fixture.advance(12_000L)
        assertEquals(48_000L, fixture.engine.nextWakeDelayMillis())
        fixture.advance(48_000L)
        assertEquals(0L, fixture.engine.nextWakeDelayMillis())
        assertEquals(listOf("second"), fixture.engine.claimWake("a")!!.events.map { it.conversationId })
    }

    @Test
    fun failureToPersistTheAttemptPreventsTheActualContentWrite() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.grantFromAssistant("a")
        val problem = IOException("Audit storage unavailable")
        val engine = EditProtectionEngine(
            read = { fixture.snapshot },
            write = { throw problem },
            clock = { EditProtectionClock(fixture.wall, fixture.elapsed, fixture.boot) },
            processId = "same-process",
        )
        var saved = false

        val failure = runCatching { engine.mutate(listOf(change("a"))) { saved = true } }.exceptionOrNull()

        assertSame(problem, failure)
        assertFalse(saved)
        assertFalse(fixture.snapshot.events.any { it.kind == EditProtectionEventKind.CHANGED })
    }

    @Test
    fun finishingAStartedWakeDoesNotDiscardAnotherEditThatArrivedDuringGeneration() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.ASSISTANT_PROMPT, "first")
        val claimed = fixture.engine.claimWake("a")!!
        fixture.engine.markWakeRequestStarted("a", claimed.id)
        fixture.engine.requestHumanEdit("a", ProtectedEditScope.CONVERSATION_PROMPT, "second")

        fixture.engine.finishWake("a", claimed.id, retryBeforeStart = true)
        fixture.advance(EDIT_WAKE_COOLDOWN_MILLIS)

        assertEquals(listOf("second"), fixture.engine.claimWake("a")!!.events.map { it.conversationId })
    }

    @Test
    fun explicitRecoveryOfDamagedStateKeepsEveryKnownLockAndOnlyOpensTheRequestedOwner() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.setEnabled("b", true)
        fixture.engine.grantFromAssistant("b")
        var damaged = true
        val engine = EditProtectionEngine(
            read = {
                if (damaged) throw IOException("Damaged state")
                fixture.snapshot
            },
            write = { fixture.snapshot = it; damaged = false },
            clock = { EditProtectionClock(fixture.wall, fixture.elapsed, fixture.boot) },
            processId = "recovery-process",
        )

        val result = engine.emergencyRecover("a", "chat") {
            recoverProtectedOwnersSnapshot(setOf("a", "b"), "a")
        }

        assertTrue(result.enabled)
        assertTrue(result.editingAllowed)
        assertEquals(EDIT_PERMISSION_DURATION_MILLIS, result.remainingMillis)
        assertTrue(engine.status("b").enabled)
        assertFalse(engine.status("b").editingAllowed)
        assertNull(fixture.snapshot.assistants["b"]?.grant)
        assertEquals(EditProtectionEventKind.RECOVERED, engine.events("a").single().kind)
        assertTrue(engine.events("a").single().summary.contains("保留原有锁"))
        assertEquals(listOf("a"), engine.pendingAssistantIds())
    }

    @Test
    fun damagedStateWithMissingOrUnreliableOwnerMetadataCannotBeResetByRecovery() = runTest {
        for (owners in listOf(null, emptySet(), setOf("another-owner"), setOf("a", ""))) {
            val fixture = Fixture()
            var writes = 0
            val engine = EditProtectionEngine(
                read = { throw IOException("Damaged state") },
                write = { writes++ },
                clock = { EditProtectionClock(fixture.wall, fixture.elapsed, fixture.boot) },
                processId = "recovery-process",
            )

            val failure = runCatching {
                engine.emergencyRecover("a") { recoverProtectedOwnersSnapshot(owners, "a") }
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(0, writes)
        }
    }

    @Test
    fun retiringAnActuallyDeletedOwnerClearsOnlyItsPolicyAndWakeWhileKeepingAuditHistory() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.setEnabled("b", true)
        fixture.engine.grantFromAssistant("a")
        fixture.engine.mutate(listOf(change("a", summary = "Assistant removed from saved settings"))) { }
        val claim = fixture.engine.claimWake("a")!!
        fixture.engine.markWakeRequestStarted("a", claim.id)

        fixture.engine.retireDeletedAssistant("a")

        val retired = fixture.engine.status("a")
        assertFalse(retired.enabled)
        assertTrue(retired.editingAllowed)
        assertNull(retired.grantedUntilMillis)
        assertFalse(retired.hasPendingWake)
        assertNull(fixture.engine.claimWake("a"))
        assertFalse(fixture.engine.markWakeRequestStarted("a", claim.id))
        assertEquals(listOf("b"), fixture.engine.lockedAssistantIds())
        assertTrue(fixture.engine.events("a").any { it.kind == EditProtectionEventKind.CHANGED })
        assertEquals(EditProtectionEventKind.DISABLED, fixture.engine.events("a").first().kind)
        assertTrue(fixture.engine.events("a").first().summary.contains("成功删除"))
        var cleanupRan = false
        fixture.engine.mutate(listOf(change("a", ProtectedEditScope.BUILTIN_MEMORY))) { cleanupRan = true }
        assertTrue(cleanupRan)
    }

    @Test
    fun retirementOfMissingAndAlreadyRetiredOwnersDoesNotCreateOrRepeatPolicyChanges() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        val before = fixture.snapshot

        fixture.engine.retireDeletedAssistant("missing")
        assertSame(before, fixture.snapshot)
        fixture.engine.retireDeletedAssistant("a")
        val retired = fixture.snapshot
        fixture.engine.retireDeletedAssistant("a")

        assertSame(retired, fixture.snapshot)
        assertEquals(1, fixture.engine.events("a").count { it.kind == EditProtectionEventKind.DISABLED })
    }

    @Test
    fun wholeBackupRestoreIsBlockedForEveryEnabledOwnerEvenWhenAllHaveEditGrants() = runTest {
        val fixture = Fixture()
        fixture.engine.setEnabled("a", true)
        fixture.engine.setEnabled("b", true)
        fixture.engine.grantFromAssistant("a")
        fixture.engine.grantFromAssistant("b")

        val failure = runCatching { fixture.engine.ensureBackupRestoreAllowed() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("关闭所有编辑保护"))
        assertEquals(listOf("a", "b"), fixture.engine.lockedAssistantIds())
        assertEquals(setOf("a", "b"), fixture.engine.pendingAssistantIds().toSet())
        for (id in listOf("a", "b")) {
            assertEquals(EditProtectionEventKind.BLOCKED, fixture.engine.events(id).first().kind)
            assertTrue(fixture.engine.status(id).editingAllowed)
        }
    }

    @Test
    fun backupRestoreCanProceedOnlyAfterAllOwnersExplicitlyDisableTheirProtection() = runTest {
        val fixture = Fixture()
        fixture.engine.ensureBackupRestoreAllowed()
        fixture.engine.setEnabled("a", true)
        fixture.engine.setEnabled("b", true)
        for (id in listOf("a", "b")) {
            fixture.engine.grantFromAssistant(id)
            fixture.engine.setEnabled(id, false)
        }

        fixture.engine.ensureBackupRestoreAllowed()

        assertTrue(fixture.engine.lockedAssistantIds().isEmpty())
    }

    private fun change(
        assistantId: String,
        scope: ProtectedEditScope = ProtectedEditScope.ASSISTANT_PROMPT,
        conversationId: String? = null,
        summary: String = "Prompt text changed",
    ) = ProtectedEditChange(assistantId, scope, conversationId, summary)

    private class Fixture {
        var snapshot = EditProtectionSnapshot()
        var wall = 1_000_000L
        var elapsed = 10_000L
        var boot = "same-boot"
        private var serial = 0
        val engine = newEngine("initial-process")

        fun newEngine(processId: String) = EditProtectionEngine(
            read = { snapshot },
            write = { snapshot = it },
            clock = { EditProtectionClock(wall, elapsed, boot) },
            processId = processId,
            nextId = { "record-${serial++}" },
        )

        fun advance(millis: Long) {
            wall += millis
            elapsed += millis
        }
    }
}
