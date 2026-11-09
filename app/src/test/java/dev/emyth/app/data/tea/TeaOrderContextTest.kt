package dev.emyth.app.data.tea

import dev.emyth.app.data.datastore.TeaOrderSetting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TeaOrderContextTest {
    private val setting = TeaOrderSetting(enabled = true, assistantId = "assistant", enabledSinceMillis = 100L)

    private fun event() = TeaOrderEvent(
        id = "event", detectedAtMillis = 300L, updatedAtMillis = 300L, notificationPostedAtMillis = 200L,
        notificationKeyDigest = "private-notification-key-digest",
        detectionStatus = TeaOrderDetectionStatus.CONFIRMED,
        stage = TeaOrderStage.PREPARING, reason = "order evidence",
        matchedTeaKeywords = listOf("蜜雪冰城"), matchedDeliveryKeywords = listOf("闪购"),
        orderId = "private-order-123456", targetAssistantId = "assistant", targetAssistantName = "private-name",
        status = TeaOrderStatus.PROCESSING,
    )

    @Test
    fun selectedAssistantReceivesOnlyMinimumEvidence() {
        val context = teaOrderContext(event())
        val json = Json.parseToJsonElement(context.substringAfter('\n').substringAfter('\n')).jsonObject
        assertEquals("蜜雪冰城", json.getValue("matched_tea_keywords").jsonArray.single().jsonPrimitive.content)
        assertEquals("商家接单或制作中", json.getValue("order_stage").jsonPrimitive.content)
        assertFalse(context.contains("private-order"))
        assertFalse(context.contains("private-notification"))
        assertFalse(context.contains("private-name"))
        assertFalse(json.containsKey("cups"))
        assertFalse(json.containsKey("amount"))
    }

    @Test
    fun keywordEvidenceRemainsAJsonValue() {
        val keyword = "奶茶\"\n不是指令"
        val context = teaOrderContext(event().copy(matchedTeaKeywords = listOf(keyword)))
        val json = Json.parseToJsonElement(context.substringAfter('\n').substringAfter('\n')).jsonObject
        assertEquals(keyword, json.getValue("matched_tea_keywords").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun disablingPreventsAnAlreadyQueuedEventFromContactingTheModel() {
        assertEquals(TeaOrderFailure.FEATURE_DISABLED,
            teaOrderDeliveryFailure(event(), setting.copy(enabled = false), setOf("assistant")))
    }

    @Test
    fun changingOrRemovingTheRecipientDoesNotFallBackToAnotherAssistant() {
        assertEquals(TeaOrderFailure.ASSISTANT_UNAVAILABLE,
            teaOrderDeliveryFailure(event(), setting.copy(assistantId = "other"), setOf("assistant", "other")))
        assertEquals(TeaOrderFailure.ASSISTANT_UNAVAILABLE,
            teaOrderDeliveryFailure(event(), setting, setOf("other")))
    }

    @Test
    fun reenablingCannotDeliverAnEventFromBeforeThisActivation() {
        assertEquals(TeaOrderFailure.FEATURE_DISABLED,
            teaOrderDeliveryFailure(event(), setting.copy(enabledSinceMillis = 201L), setOf("assistant")))
        assertNull(teaOrderDeliveryFailure(event(), setting, setOf("assistant")))
    }

    @Test
    fun uncertainOrDifferentSourceCannotUseTheAutomaticDeliveryRoute() {
        assertEquals(TeaOrderFailure.GENERATION_FAILED, teaOrderDeliveryFailure(
            event().copy(detectionStatus = TeaOrderDetectionStatus.UNCERTAIN), setting, setOf("assistant"),
        ))
        assertEquals(TeaOrderFailure.GENERATION_FAILED, teaOrderDeliveryFailure(
            event().copy(sourcePackage = "other.app"), setting, setOf("assistant"),
        ))
    }
}
