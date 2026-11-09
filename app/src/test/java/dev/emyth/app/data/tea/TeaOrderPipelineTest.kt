package dev.emyth.app.data.tea

import dev.emyth.app.data.datastore.TeaOrderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-component synthetic notification tests; not a claim that Taobao uses these exact messages. */
class TeaOrderPipelineTest {
    private val now = 1_700_000_000_000L
    private val config = TeaOrderSetting(enabled = true, assistantId = "assistant", enabledSinceMillis = now - 1)

    private fun candidate(text: String, time: Long = now) = TeaOrderCandidate(
        packageName = TeaOrderDetector.TAOBAO_PACKAGE, notificationKey = "order-notice",
        postedAtMillis = time, observedAtMillis = time, targetAssistantId = "assistant",
        targetAssistantName = "selected assistant",
        detection = TeaOrderDetector.detect(
            TeaOrderDetector.TAOBAO_PACKAGE, "淘宝闪购", text, null, setOf("蜜雪冰城", "奶茶"),
        ),
    )

    @Test
    fun confirmedOrderCanReachTheRecipientAndLaterDeliveryDoesNotContactAgain() {
        val input = candidate("蜜雪冰城 商家已接单 订单号:1234567890")
        assertEquals(TeaOrderDetectionStatus.CONFIRMED, input.detection.status)
        val recorded = TeaOrderStateReducer.record(TeaOrderState(), input, "event")
        assertTrue(recorded.result.shouldProcess)
        val claim = TeaOrderStateReducer.claim(recorded.state, "event", now + 1)
        val event = requireNotNull(claim.result)
        assertNull(teaOrderDeliveryFailure(event, config, setOf("assistant")))
        assertTrue(teaOrderContext(event).contains("蜜雪冰城"))
        val started = TeaOrderStateReducer.markGenerationStarted(claim.state, "event", now + 2)
        val saved = TeaOrderStateReducer.delivered(started.state, "event", "conversation", now + 3)
        assertEquals(TeaOrderStatus.DELIVERED, saved.state.events.single().status)

        val later = candidate("蜜雪冰城 骑手正在配送 订单号:1234567890", now + 45 * 60_000)
        val update = TeaOrderStateReducer.record(saved.state, later, "should-not-be-new")
        assertFalse(update.result.shouldProcess)
        assertEquals("event", update.state.events.single().id)
        assertEquals(TeaOrderStatus.DELIVERED, update.state.events.single().status)
    }

    @Test
    fun genericDeliveryWithoutTeaDetailsStaysLocalAndCannotBeClaimed() {
        val input = candidate("商家已接单 订单号:1234567890")
        assertEquals(TeaOrderDetectionStatus.UNCERTAIN, input.detection.status)
        val recorded = TeaOrderStateReducer.record(TeaOrderState(), input, "uncertain")
        assertFalse(recorded.result.shouldProcess)
        assertEquals(TeaOrderStatus.OBSERVED, recorded.state.events.single().status)
        assertNull(TeaOrderStateReducer.claim(recorded.state, "uncertain", now + 1).result)
    }

    @Test
    fun anInterruptedStartedRequestCannotBeReleasedForAutomaticReplay() {
        val recorded = TeaOrderStateReducer.record(
            TeaOrderState(), candidate("蜜雪冰城 下单成功 订单号:1234567890"), "event",
        )
        val claim = TeaOrderStateReducer.claim(recorded.state, "event", now + 1)
        assertNotNull(claim.result)
        val started = TeaOrderStateReducer.markGenerationStarted(claim.state, "event", now + 2)
        assertFalse(TeaOrderStateReducer.releaseBeforeStart(started.state, "event", now + 3).result)
        val recovered = TeaOrderStateReducer.recover(started.state, now + 4)
        assertEquals(TeaOrderFailure.INTERRUPTED, recovered.events.single().failureReason)
        assertNull(TeaOrderStateReducer.claim(recovered, "event", now + 5).result)
    }
}
