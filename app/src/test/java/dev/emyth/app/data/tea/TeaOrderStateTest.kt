package dev.emyth.app.data.tea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeaOrderStateTest {
    private val now = 1_800_000_000_000L

    @Test
    fun onlyTaobaoConfirmedEventsBecomePending() {
        val wrongSource = TeaOrderStateReducer.record(
            TeaOrderState(), candidate().copy(packageName = "another.package"), "wrong"
        )
        assertEquals(TeaOrderRecordRejection.WRONG_SOURCE, wrongSource.result.rejection)
        assertTrue(wrongSource.state.events.isEmpty())

        val ignored = record(detection = detection(TeaOrderDetectionStatus.IGNORED))
        assertTrue(ignored.state.events.isEmpty())
        assertFalse(ignored.result.shouldProcess)

        val confirmed = record()
        assertEquals(TeaOrderStatus.PENDING, confirmed.result.event?.status)
        assertTrue(confirmed.result.shouldProcess)
        assertEquals("assistant-1", confirmed.result.event?.targetAssistantId)
    }

    @Test
    fun uncertainEvidenceIsVisibleButCannotBeClaimedUntilConfirmed() {
        val observed = record(detection = detection(TeaOrderDetectionStatus.UNCERTAIN))
        assertEquals(TeaOrderStatus.OBSERVED, observed.result.event?.status)
        assertFalse(observed.result.shouldProcess)
        assertNull(TeaOrderStateReducer.claim(observed.state, "event-1", now).result)

        val confirmed = TeaOrderStateReducer.record(
            observed.state,
            candidate(at = now + 1_000).copy(targetAssistantId = "assistant-2"),
            "unused-id",
        )
        assertEquals("event-1", confirmed.result.event?.id)
        assertEquals(1, confirmed.state.events.size)
        assertEquals(TeaOrderStatus.PENDING, confirmed.result.event?.status)
        assertEquals("assistant-2", confirmed.result.event?.targetAssistantId)
        assertTrue(confirmed.result.shouldProcess)
    }

    @Test
    fun oneOrderAcrossKeysAndStagesCannotGenerateAnotherContact() {
        val initial = record()
        val claimed = TeaOrderStateReducer.claim(initial.state, "event-1", now)
        val started = TeaOrderStateReducer.markGenerationStarted(claimed.state, "event-1", now)
        val delivered = TeaOrderStateReducer.delivered(started.state, "event-1", "conversation-1", now)
        val update = TeaOrderStateReducer.record(
            delivered.state,
            candidate(key = "replacement-key", at = now + 60_000).copy(
                targetAssistantId = "assistant-2",
                detection = detection(stage = TeaOrderStage.DELIVERED),
            ),
            "unused-id",
        )
        assertEquals(1, update.state.events.size)
        assertEquals("event-1", update.result.event?.id)
        assertEquals(TeaOrderStatus.DELIVERED, update.result.event?.status)
        assertEquals(TeaOrderStage.DELIVERED, update.result.event?.stage)
        assertEquals("assistant-1", update.result.event?.targetAssistantId)
        assertEquals("conversation-1", update.result.event?.conversationId)
        assertTrue(update.result.duplicate)
        assertFalse(update.result.shouldProcess)
    }

    @Test
    fun aDistinctOrderCanUseTheSameNotificationKeyImmediately() {
        val first = record()
        val second = TeaOrderStateReducer.record(
            first.state,
            candidate(at = now + 1_000).copy(detection = detection(orderId = "order-222222")),
            "event-2",
        )
        assertEquals(2, second.state.events.size)
        assertEquals("event-2", second.result.event?.id)
        assertTrue(second.result.shouldProcess)
        assertFalse(second.result.duplicate)
    }

    @Test
    fun noOrderIdShortKeyWindowEndsForANewPlacedNotification() {
        val initial = record(detection = detection(orderId = null))
        val claimed = TeaOrderStateReducer.claim(initial.state, "event-1", now)
        val update = TeaOrderStateReducer.record(
            claimed.state,
            candidate(at = now + TeaOrderStateReducer.KEY_DEDUP_MILLIS - 1).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERING)
            ),
            "unused-id",
        )
        assertEquals("event-1", update.result.event?.id)
        assertFalse(update.result.shouldProcess)

        val later = TeaOrderStateReducer.record(
            update.state,
            candidate(at = now + TeaOrderStateReducer.KEY_DEDUP_MILLIS + 1).copy(
                detection = detection(orderId = null)
            ),
            "event-2",
        )
        assertEquals("event-2", later.result.event?.id)
        assertTrue(later.result.shouldProcess)
    }

    @Test
    fun noOrderIdDeliveryProgressAt25And45MinutesKeepsTheOriginalContact() {
        val initial = deliveredWithoutOrderId()
        val delivering = TeaOrderStateReducer.record(
            initial,
            candidate(at = now + 25 * 60_000).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERING)
            ),
            "unused-id",
        )
        assertEquals("event-1", delivering.result.event?.id)
        assertEquals(TeaOrderStatus.DELIVERED, delivering.result.event?.status)
        assertFalse(delivering.result.shouldProcess)
        val delivered = TeaOrderStateReducer.record(
            delivering.state,
            candidate(at = now + 45 * 60_000).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERED)
            ),
            "unused-id-2",
        )
        assertEquals(1, delivered.state.events.size)
        assertEquals("event-1", delivered.result.event?.id)
        assertEquals(TeaOrderStage.DELIVERED, delivered.result.event?.stage)
        assertFalse(delivered.result.shouldProcess)
    }

    @Test
    fun clearedNoIdOrderStillSuppressesDeliveryProgressUsingItsReceipt() {
        val cleared = TeaOrderStateReducer.clearHistory(deliveredWithoutOrderId())
        val delivering = TeaOrderStateReducer.record(
            cleared,
            candidate(at = now + 25 * 60_000).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERING)
            ),
            "unused-id",
        )
        assertEquals(TeaOrderRecordRejection.DUPLICATE, delivering.result.rejection)
        assertEquals(TeaOrderStage.DELIVERING, delivering.state.receipts.single().stage)
        val delivered = TeaOrderStateReducer.record(
            delivering.state,
            candidate(at = now + 45 * 60_000).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERED)
            ),
            "unused-id-2",
        )
        assertTrue(delivered.state.events.isEmpty())
        assertEquals(TeaOrderRecordRejection.DUPLICATE, delivered.result.rejection)
        assertFalse(delivered.result.shouldProcess)
    }

    @Test
    fun sameKeyProgressCanCompleteAnUnknownOrderIdWithoutAnotherContact() {
        val initial = deliveredWithoutOrderId()
        val next = TeaOrderStateReducer.record(
            initial,
            candidate(at = now + 60_000).copy(
                detection = detection(orderId = "new-order-222222", stage = TeaOrderStage.PREPARING)
            ),
            "unused-id",
        )
        assertEquals("event-1", next.result.event?.id)
        assertEquals("new-order-222222", next.result.event?.orderId)
        assertFalse(next.result.shouldProcess)
        assertEquals(1, next.state.events.size)

        val distinct = TeaOrderStateReducer.record(
            next.state,
            candidate(at = now + 61_000).copy(detection = detection(orderId = "another-order-333333")),
            "event-2",
        )
        assertEquals("event-2", distinct.result.event?.id)
        assertTrue(distinct.result.shouldProcess)
        assertEquals(2, distinct.state.events.size)

        val advancedUnknown = TeaOrderStateReducer.record(
            initial,
            candidate(at = now + 1_000).copy(detection = detection(orderId = null, stage = TeaOrderStage.PREPARING)),
            "unused-id",
        )
        val newPlaced = TeaOrderStateReducer.record(
            advancedUnknown.state,
            candidate(at = now + 60_000).copy(detection = detection(orderId = "new-placed-444444")),
            "new-placed-event",
        )
        assertTrue(newPlaced.result.shouldProcess)
        assertEquals("new-placed-event", newPlaced.result.event?.id)
    }

    @Test
    fun clearedNoIdReceiptCanLearnItsOrderIdButNotMergeAConflictingId() {
        val cleared = TeaOrderStateReducer.clearHistory(deliveredWithoutOrderId())
        val completed = TeaOrderStateReducer.record(
            cleared,
            candidate(at = now + 60_000).copy(
                detection = detection(orderId = "new-order-222222", stage = TeaOrderStage.PREPARING)
            ),
            "unused-id",
        )
        assertEquals(TeaOrderRecordRejection.DUPLICATE, completed.result.rejection)
        assertFalse(completed.result.shouldProcess)
        assertTrue(completed.state.events.isEmpty())
        val sameOrderNewKey = TeaOrderStateReducer.record(
            completed.state,
            candidate(key = "replacement-key", at = now + 61_000).copy(
                detection = detection(orderId = "new-order-222222", stage = TeaOrderStage.DELIVERING)
            ),
            "unused-id-2",
        )
        assertEquals(TeaOrderRecordRejection.DUPLICATE, sameOrderNewKey.result.rejection)
        assertFalse(sameOrderNewKey.result.shouldProcess)
        val conflicting = TeaOrderStateReducer.record(
            sameOrderNewKey.state,
            candidate(at = now + 61_000).copy(detection = detection(orderId = "another-order-333333")),
            "event-2",
        )
        assertTrue(conflicting.result.shouldProcess)
        assertEquals("event-2", conflicting.result.event?.id)
    }

    @Test
    fun anExactOldOrderReceiptTakesPriorityOverANewUnknownEventOnTheSameKey() {
        val cleared = TeaOrderStateReducer.clearHistory(record().state)
        val newUnknown = TeaOrderStateReducer.record(
            cleared,
            candidate(at = now + 21 * 60_000).copy(detection = detection(orderId = null)),
            "new-unknown",
        )
        assertTrue(newUnknown.result.shouldProcess)
        val oldIdProgress = TeaOrderStateReducer.record(
            newUnknown.state,
            candidate(at = now + 22 * 60_000).copy(
                detection = detection(stage = TeaOrderStage.DELIVERING)
            ),
            "unused-id-3",
        )
        assertEquals(TeaOrderRecordRejection.DUPLICATE, oldIdProgress.result.rejection)
        assertFalse(oldIdProgress.result.shouldProcess)
        assertEquals("new-unknown", oldIdProgress.state.events.single().id)
        assertNull(oldIdProgress.state.events.single().orderId)
    }

    @Test
    fun noIdLifecycleWindowIsThreeHoursFromOriginalDetectionAndDoesNotSlide() {
        val cleared = TeaOrderStateReducer.clearHistory(deliveredWithoutOrderId())
        val atBoundary = TeaOrderStateReducer.record(
            cleared,
            candidate(at = now + TeaOrderStateReducer.ORDER_LIFECYCLE_MILLIS).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERED)
            ),
            "unused-id",
        )
        assertEquals(TeaOrderRecordRejection.DUPLICATE, atBoundary.result.rejection)
        assertFalse(atBoundary.result.shouldProcess)
        val expired = TeaOrderStateReducer.record(
            atBoundary.state,
            candidate(at = now + TeaOrderStateReducer.ORDER_LIFECYCLE_MILLIS + 1).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERED)
            ),
            "event-2",
        )
        assertEquals("event-2", expired.result.event?.id)
        assertTrue(expired.result.shouldProcess)
    }

    @Test
    fun noIdStageRegressionOrChangedTeaEvidenceCanStartANewOrderAfterShortWindow() {
        val initial = deliveredWithoutOrderId()
        val progress = TeaOrderStateReducer.record(
            initial,
            candidate(at = now + 25 * 60_000).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERING)
            ),
            "unused-id",
        )
        val regression = TeaOrderStateReducer.record(
            progress.state,
            candidate(at = now + 26 * 60_000).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.PREPARING)
            ),
            "regression-order",
        )
        assertEquals("regression-order", regression.result.event?.id)
        assertTrue(regression.result.shouldProcess)
        val changedTea = TeaOrderStateReducer.record(
            progress.state,
            candidate(at = now + 26 * 60_000).copy(
                detection = detection(orderId = null, stage = TeaOrderStage.DELIVERED).copy(
                    matchedTeaKeywords = listOf("另一家茶饮")
                )
            ),
            "changed-tea-order",
        )
        assertEquals("changed-tea-order", changedTea.result.event?.id)
        assertTrue(changedTea.result.shouldProcess)
    }

    @Test
    fun staleOrInvalidNotificationTimesNeverAutoContact() {
        val stale = TeaOrderStateReducer.record(
            TeaOrderState(),
            candidate().copy(postedAtMillis = now - TeaOrderStateReducer.FRESH_NOTIFICATION_MILLIS - 1),
            "event-1",
        )
        assertEquals(TeaOrderFailure.STALE_NOTIFICATION, stale.result.event?.failureReason)
        assertFalse(stale.result.shouldProcess)
        assertNull(TeaOrderStateReducer.claim(stale.state, "event-1", now).result)

        val future = TeaOrderStateReducer.record(
            TeaOrderState(), candidate().copy(postedAtMillis = now + 120_000), "future"
        )
        assertEquals(TeaOrderFailure.INVALID_NOTIFICATION_TIME, future.result.event?.failureReason)
        val invalid = TeaOrderStateReducer.record(
            TeaOrderState(), candidate().copy(postedAtMillis = 0), "invalid"
        )
        assertFalse(invalid.result.shouldProcess)
    }

    @Test
    fun claimExpiresPendingWorkEvenWhenTheServiceStartsLate() {
        val initial = record()
        val claim = TeaOrderStateReducer.claim(
            initial.state, "event-1", now + TeaOrderStateReducer.FRESH_NOTIFICATION_MILLIS + 1
        )
        assertNull(claim.result)
        assertEquals(TeaOrderStatus.FAILED, claim.state.events.single().status)
        assertEquals(TeaOrderFailure.STALE_NOTIFICATION, claim.state.events.single().failureReason)
    }

    @Test
    fun claimIsExclusiveAndOnlyUnstartedWorkCanReturnToPending() {
        val initial = record()
        val claimed = TeaOrderStateReducer.claim(initial.state, "event-1", now)
        assertEquals(TeaOrderStatus.PROCESSING, claimed.result?.status)
        assertNull(TeaOrderStateReducer.claim(claimed.state, "event-1", now).result)

        val released = TeaOrderStateReducer.releaseBeforeStart(claimed.state, "event-1", now)
        assertTrue(released.result)
        assertEquals(TeaOrderStatus.PENDING, released.state.events.single().status)
        val reclaimed = TeaOrderStateReducer.claim(released.state, "event-1", now)
        val started = TeaOrderStateReducer.markGenerationStarted(reclaimed.state, "event-1", now)
        assertTrue(started.result)
        assertFalse(TeaOrderStateReducer.markGenerationStarted(started.state, "event-1", now).result)
        assertFalse(TeaOrderStateReducer.releaseBeforeStart(started.state, "event-1", now).result)
        assertEquals(TeaOrderStatus.PROCESSING, started.state.events.single().status)
    }

    @Test
    fun restartMarksInFlightWorkInterruptedWithoutReplayingIt() {
        val initial = record()
        val claimed = TeaOrderStateReducer.claim(initial.state, "event-1", now)
        val recovered = TeaOrderStateReducer.recover(claimed.state, now + 1_000)
        assertEquals(TeaOrderStatus.FAILED, recovered.events.single().status)
        assertEquals(TeaOrderFailure.INTERRUPTED, recovered.events.single().failureReason)
        assertNull(TeaOrderStateReducer.claim(recovered, "event-1", now + 1_000).result)
        val duplicate = TeaOrderStateReducer.record(recovered, candidate(at = now + 2_000), "unused-id")
        assertFalse(duplicate.result.shouldProcess)

        val freshPending = TeaOrderStateReducer.recover(initial.state, now + 1_000)
        assertEquals(TeaOrderStatus.PENDING, freshPending.events.single().status)
        val stalePending = TeaOrderStateReducer.recover(
            initial.state, now + TeaOrderStateReducer.FRESH_NOTIFICATION_MILLIS + 1
        )
        assertEquals(TeaOrderFailure.STALE_NOTIFICATION, stalePending.events.single().failureReason)
    }

    @Test
    fun retryExhaustionCannotFailAnEventWhichAServiceHasClaimed() {
        val initial = record()
        val pendingFailure = TeaOrderStateReducer.pendingFailed(
            initial.state, "event-1", TeaOrderFailure.SESSION_BUSY, now
        )
        assertTrue(pendingFailure.result)
        assertEquals(TeaOrderStatus.FAILED, pendingFailure.state.events.single().status)

        val claimed = TeaOrderStateReducer.claim(initial.state, "event-1", now)
        val afterClaim = TeaOrderStateReducer.pendingFailed(
            claimed.state, "event-1", TeaOrderFailure.SESSION_BUSY, now
        )
        assertFalse(afterClaim.result)
        assertEquals(TeaOrderStatus.PROCESSING, afterClaim.state.events.single().status)
    }

    @Test
    fun clearingRecordsCannotResurrectAnAsyncCompletionOrReplayTheOrder() {
        val initial = record()
        val claimed = TeaOrderStateReducer.claim(initial.state, "event-1", now)
        val cleared = TeaOrderStateReducer.clearHistory(claimed.state)
        assertFalse(TeaOrderStateReducer.delivered(cleared, "event-1", "conversation-1", now).result)
        assertFalse(TeaOrderStateReducer.failed(cleared, "event-1", TeaOrderFailure.CANCELLED, now).result)
        assertFalse(TeaOrderStateReducer.releaseBeforeStart(cleared, "event-1", now).result)
        assertTrue(cleared.events.isEmpty())

        val replay = TeaOrderStateReducer.record(cleared, candidate(at = now + 1_000), "event-2")
        assertEquals(TeaOrderRecordRejection.DUPLICATE, replay.result.rejection)
        assertFalse(replay.result.shouldProcess)
        assertTrue(replay.state.events.isEmpty())
    }

    @Test
    fun recentRecordsAndReceiptsAreBoundedWhileInFlightRecordIsRetained() {
        var state = record().state
        state = TeaOrderStateReducer.claim(state, "event-1", now).state
        repeat(200) { index ->
            state = TeaOrderStateReducer.record(
                state,
                candidate(key = "key-$index", at = now + index + 1).copy(
                    detection = detection(orderId = "order-$index-333333")
                ),
                "new-$index",
            ).state
        }
        assertEquals(TeaOrderStateReducer.MAX_EVENTS, state.events.size)
        assertEquals(TeaOrderStateReducer.MAX_RECEIPTS, state.receipts.size)
        assertTrue(state.events.any { it.id == "event-1" && it.status == TeaOrderStatus.PROCESSING })
        assertFalse(state.events.any { it.id == "new-100" })

        val archived = TeaOrderStateReducer.record(
            state,
            candidate(key = "changed-key", at = now + 1_000).copy(
                detection = detection(orderId = "order-100-333333")
            ),
            "unused-id",
        )
        assertEquals(TeaOrderRecordRejection.DUPLICATE, archived.result.rejection)
        assertFalse(archived.result.shouldProcess)
    }

    @Test
    fun persistedEvidenceAndIdentifiersHaveBoundsAndNoRawNotificationKey() {
        val result = TeaOrderStateReducer.record(
            TeaOrderState(),
            candidate(key = "private-notification-key").copy(
                targetAssistantName = "A".repeat(500),
                detection = detection().copy(
                    reason = "R".repeat(500),
                    matchedTeaKeywords = List(50) { "keyword-$it-" + "x".repeat(100) },
                ),
            ),
            "event-1",
        )
        val event = result.result.event!!
        assertEquals(100, event.targetAssistantName.length)
        assertEquals(160, event.reason.length)
        assertEquals(12, event.matchedTeaKeywords.size)
        assertTrue(event.matchedTeaKeywords.all { it.length <= 50 })
        assertEquals(64, event.notificationKeyDigest.length)
        assertNotEquals("private-notification-key", event.notificationKeyDigest)
    }

    private fun record(
        detection: TeaOrderDetection = detection(),
    ): TeaOrderTransition<TeaOrderRecordResult> = TeaOrderStateReducer.record(
        TeaOrderState(), candidate().copy(detection = detection), "event-1"
    )

    private fun deliveredWithoutOrderId(): TeaOrderState {
        val initial = record(detection = detection(orderId = null))
        val claimed = TeaOrderStateReducer.claim(initial.state, "event-1", now)
        return TeaOrderStateReducer.delivered(claimed.state, "event-1", "conversation-1", now).state
    }

    private fun candidate(key: String = "notification-key", at: Long = now): TeaOrderCandidate = TeaOrderCandidate(
        packageName = TeaOrderStateReducer.TAOBAO_PACKAGE,
        notificationKey = key,
        postedAtMillis = at,
        observedAtMillis = at,
        targetAssistantId = "assistant-1",
        targetAssistantName = "Assistant",
        detection = detection(),
    )

    private fun detection(
        status: TeaOrderDetectionStatus = TeaOrderDetectionStatus.CONFIRMED,
        orderId: String? = "order-111111",
        stage: TeaOrderStage = TeaOrderStage.PLACED,
    ): TeaOrderDetection = TeaOrderDetection(
        status = status,
        reason = "已匹配奶茶及闪购配送状态",
        orderId = orderId,
        matchedTeaKeywords = listOf("奶茶"),
        matchedDeliveryKeywords = listOf("闪购", "商家已接单"),
        stage = stage,
    )
}
