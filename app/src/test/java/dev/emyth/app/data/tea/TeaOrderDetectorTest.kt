package dev.emyth.app.data.tea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Constructed examples exercise rules; these are not captured Taobao notification formats. */
class TeaOrderDetectorTest {
    private val keywords = setOf("奶茶", "蜜雪冰城", "茶百道", "霸王茶姬", "喜茶")

    @Test
    fun explicitFlashTeaOrderPaymentProvidesEvidenceWithoutInferringDrinks() {
        val result = detect("淘宝闪购", "蜜雪冰城：您的订单支付成功。订单号：123456789012345678")

        assertEquals(TeaOrderDetectionStatus.CONFIRMED, result.status)
        assertEquals(TeaOrderStage.PLACED, result.stage)
        assertEquals("123456789012345678", result.orderId)
        assertEquals(listOf("蜜雪冰城"), result.matchedTeaKeywords)
        assertEquals(listOf("淘宝闪购", "闪购"), result.matchedDeliveryKeywords)
        assertTrue(result.reason.contains("不代表已经饮用"))
    }

    @Test
    fun explicitFoodDeliveryProgressCanConfirmWithoutAnOrderId() {
        val result = detect("茶百道", "外卖订单正在配送，骑手即将到达")

        assertEquals(TeaOrderDetectionStatus.CONFIRMED, result.status)
        assertEquals(TeaOrderStage.DELIVERING, result.stage)
        assertNull(result.orderId)
    }

    @Test
    fun preparationAndDeliveryAreProgressOfTheSameOrder() {
        val preparing = detect("淘宝闪购", "喜茶商家已接单，订单号: 1234567890")
        val delivering = detect("淘宝闪购", "喜茶骑手已取餐，订单号: 1234567890")
        val delivered = detect("淘宝闪购", "喜茶订单已送达，订单号: 1234567890")

        assertEquals(TeaOrderDetectionStatus.CONFIRMED, preparing.status)
        assertEquals(TeaOrderDetectionStatus.CONFIRMED, delivering.status)
        assertEquals(TeaOrderDetectionStatus.CONFIRMED, delivered.status)
        assertEquals(TeaOrderStage.PREPARING, preparing.stage)
        assertEquals(TeaOrderStage.DELIVERING, delivering.stage)
        assertEquals(TeaOrderStage.DELIVERED, delivered.stage)
        assertEquals(preparing.orderId, delivering.orderId)
        assertEquals(delivering.orderId, delivered.orderId)
        assertEquals(preparing.matchedTeaKeywords, delivered.matchedTeaKeywords)
    }

    @Test
    fun couponsAdsAndPurchaseInstructionsDoNotConfirmAnOrder() {
        listOf(
            "蜜雪冰城优惠券，订单支付成功即可使用",
            "喜茶限时福利，下单成功领红包",
            "奶茶广告：外卖订单正在配送也能领券",
            "茶百道下单成功后可在闪购查看订单",
            "如果霸王茶姬订单支付成功，请打开淘宝闪购",
        ).forEach { body ->
            assertEquals(body, TeaOrderDetectionStatus.IGNORED, detect("淘宝闪购", body).status)
        }
    }

    @Test
    fun ordinaryTaobaoMerchandiseAndLogisticsAreIgnoredEvenWithTeaWords() {
        listOf(
            "奶茶色衣服订单支付成功",
            "奶茶杯订单已确认",
            "奶茶粉订单已支付，物流已更新",
            "蜜雪冰城奶茶挂件订单已支付",
        ).forEach { body ->
            assertEquals(body, TeaOrderDetectionStatus.IGNORED, detect("淘宝闪购", body).status)
        }
    }

    @Test
    fun genericPaymentAndOpeningTaobaoAreNeverOrderProof() {
        listOf("付款成功", "支付成功", "打开淘宝", "订单号:1234567890 付款成功").forEach { body ->
            val result = detect("淘宝闪购 蜜雪冰城", body)

            assertEquals(body, TeaOrderDetectionStatus.UNCERTAIN, result.status)
            assertEquals(TeaOrderStage.UNKNOWN, result.stage)
        }
        assertEquals(TeaOrderDetectionStatus.IGNORED, detect("淘宝", "付款成功，打开淘宝").status)
    }

    @Test
    fun theExactTaobaoAppIsRequired() {
        listOf("com.taobao.taobao.fake", "com.taobao.taobao.debug", "com.eg.android.AlipayGphone", "").forEach {
            val result = TeaOrderDetector.detect(it, "淘宝闪购", "喜茶订单已支付", null, keywords)

            assertEquals(TeaOrderDetectionStatus.IGNORED, result.status)
            assertNull(result.orderId)
            assertTrue(result.matchedTeaKeywords.isEmpty())
        }
    }

    @Test
    fun incompleteOrderDetailsRemainUncertain() {
        val missingTea = detect("淘宝闪购", "商家已接单")
        val missingFlash = detect("淘宝", "蜜雪冰城订单已支付")
        val missingProgress = detect("淘宝闪购", "茶百道")

        assertEquals(TeaOrderDetectionStatus.UNCERTAIN, missingTea.status)
        assertTrue(missingTea.matchedTeaKeywords.isEmpty())
        assertEquals(TeaOrderDetectionStatus.UNCERTAIN, missingFlash.status)
        assertTrue(missingFlash.matchedDeliveryKeywords.isEmpty())
        assertEquals(TeaOrderDetectionStatus.UNCERTAIN, missingProgress.status)
        assertEquals(TeaOrderStage.UNKNOWN, missingProgress.stage)
    }

    @Test
    fun chatAndCancelledOrdersDoNotConfirmAPurchase() {
        assertEquals(
            TeaOrderDetectionStatus.IGNORED,
            detect("淘宝闪购客服消息", "喜茶订单已支付，请核对").status,
        )
        assertEquals(
            TeaOrderDetectionStatus.IGNORED,
            detect("淘宝闪购", "喜茶给您发来消息：订单已支付").status,
        )
        assertEquals(
            TeaOrderDetectionStatus.IGNORED,
            detect("淘宝闪购", "喜茶订单已支付，订单已取消").status,
        )
        assertEquals(
            TeaOrderDetectionStatus.IGNORED,
            detect("淘宝闪购", "喜茶订单已提交，等待付款，待付款").status,
        )
    }

    @Test
    fun unicodeWhitespaceAndFullWidthOrderIdsAreNormalized() {
        val result = detect(
            "淘宝闪购\u00a0\u2003蜜雪冰城",
            "您的订单支付成功\n订单号：１２３４５６７８９０",
        )

        assertEquals(TeaOrderDetectionStatus.CONFIRMED, result.status)
        assertEquals("1234567890", result.orderId)
        assertEquals(listOf("蜜雪冰城"), result.matchedTeaKeywords)
    }

    @Test
    fun evidenceIsDeterministicAndDoesNotRetainCustomerOrNotificationText() {
        val result = TeaOrderDetector.detect(
            TeaOrderDetector.TAOBAO_PACKAGE,
            "淘宝闪购",
            "喜茶奶茶订单已支付，收货人张三，电话13800138000，地址某路123号",
            null,
            linkedSetOf(" 喜茶 ", "奶茶", "喜茶", " ", "茶", "x".repeat(TeaOrderDetector.MAX_KEYWORD_LENGTH + 1)),
        )

        assertEquals(listOf("喜茶", "奶茶"), result.matchedTeaKeywords)
        assertNull(result.orderId)
        assertFalse(result.toString().contains("13800138000"))
        assertFalse(result.toString().contains("某路"))
    }

    @Test
    fun keywordsAtTheConfiguredLengthLimitMatchAndLongerOnesAreIgnored() {
        val keyword = "茶".repeat(TeaOrderDetector.MAX_KEYWORD_LENGTH)
        val accepted = TeaOrderDetector.detect(
            TeaOrderDetector.TAOBAO_PACKAGE, "淘宝闪购", "${keyword}订单已支付", null, setOf(keyword),
        )
        val ignored = TeaOrderDetector.detect(
            TeaOrderDetector.TAOBAO_PACKAGE, "淘宝闪购", "${keyword}茶订单已支付", null, setOf("${keyword}茶"),
        )

        assertEquals(TeaOrderDetectionStatus.CONFIRMED, accepted.status)
        assertEquals(listOf(keyword), accepted.matchedTeaKeywords)
        assertEquals(TeaOrderDetectionStatus.UNCERTAIN, ignored.status)
        assertTrue(ignored.matchedTeaKeywords.isEmpty())
    }

    @Test
    fun bigTextCanSupplyDetailsButTheLatestRecognizedStageWins() {
        val result = TeaOrderDetector.detect(
            TeaOrderDetector.TAOBAO_PACKAGE,
            "淘宝闪购",
            "喜茶订单支付成功",
            "喜茶骑手已取餐，订单配送完成。订单号: AB-12345678",
            keywords,
        )

        assertEquals(TeaOrderDetectionStatus.CONFIRMED, result.status)
        assertEquals(TeaOrderStage.DELIVERED, result.stage)
        assertEquals("ab-12345678", result.orderId)
    }

    @Test
    fun oversizedTextIsBoundedAndCannotConfirmATruncatedMessage() {
        val result = detect(
            "淘宝闪购",
            "喜茶订单已支付" + "x".repeat(TeaOrderDetector.MAX_FIELD_LENGTH) + "领券",
        )

        assertEquals(TeaOrderDetectionStatus.UNCERTAIN, result.status)
        assertTrue(result.reason.contains("通知过长"))
        assertTrue(result.toString().length < 500)
    }

    @Test
    fun excessiveConfigurationIsBoundedAndCannotConfirm() {
        val result = TeaOrderDetector.detect(
            TeaOrderDetector.TAOBAO_PACKAGE,
            "淘宝闪购",
            "喜茶订单已支付",
            null,
            (0..TeaOrderDetector.MAX_KEYWORDS).map { "关键词$it" }.toSet(),
        )

        assertEquals(TeaOrderDetectionStatus.UNCERTAIN, result.status)
        assertTrue(result.matchedTeaKeywords.isEmpty())
    }

    @Test
    fun emptyNotificationsAndUnknownSourcesHaveNoEvidence() {
        val result = TeaOrderDetector.detect(TeaOrderDetector.TAOBAO_PACKAGE, null, "\u2003", null, keywords)

        assertEquals(TeaOrderDetectionStatus.IGNORED, result.status)
        assertTrue(result.matchedTeaKeywords.isEmpty())
        assertTrue(result.matchedDeliveryKeywords.isEmpty())
        assertEquals(TeaOrderStage.UNKNOWN, result.stage)
    }

    @Test
    fun invalidOrOverlongOrderIdsAreNotInventedOrClipped() {
        listOf("unknown", "12345", "1".repeat(41), "******1234").forEach { id ->
            val result = detect("淘宝闪购", "喜茶订单已支付，订单号: $id")

            assertEquals(TeaOrderDetectionStatus.CONFIRMED, result.status)
            assertNull(id, result.orderId)
        }
    }

    private fun detect(title: String, text: String) = TeaOrderDetector.detect(
        packageName = TeaOrderDetector.TAOBAO_PACKAGE,
        title = title,
        text = text,
        bigText = null,
        teaKeywords = keywords,
    )
}
