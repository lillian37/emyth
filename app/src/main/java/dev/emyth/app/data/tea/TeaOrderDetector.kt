package dev.emyth.app.data.tea

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class TeaOrderDetectionStatus {
    CONFIRMED,
    UNCERTAIN,
    IGNORED,
}

@Serializable
enum class TeaOrderStage {
    UNKNOWN,
    PLACED,
    PREPARING,
    DELIVERING,
    DELIVERED,
}

/** Evidence about an order notification, never a claim about consumption or the number of drinks. */
data class TeaOrderDetection(
    val status: TeaOrderDetectionStatus,
    val reason: String,
    val orderId: String? = null,
    val matchedTeaKeywords: List<String> = emptyList(),
    val matchedDeliveryKeywords: List<String> = emptyList(),
    val stage: TeaOrderStage = TeaOrderStage.UNKNOWN,
)

/**
 * Conservative rules for notifications from the Taobao app's flash/food-delivery flow.
 *
 * These rules are intentionally independent of Android. Notification formats have not been verified against real
 * samples. Keywords are supplied by settings; raw notification text is not included in the returned evidence.
 * Transport deduplication belongs to the caller, using the order ID when available and notification identity otherwise.
 */
object TeaOrderDetector {
    const val TAOBAO_PACKAGE = "com.taobao.taobao"
    const val MAX_FIELD_LENGTH = 2_048
    const val MAX_KEYWORDS = 64
    const val MAX_KEYWORD_LENGTH = 50

    private val whitespace = Regex("[\\s\\p{Z}\\p{Cc}\\p{Cf}]+")
    private val orderIdPattern = Regex(
        "(?:订单(?:编号|号码|号)|order\\s*(?:id|no\\.?))\\s*[:#]?\\s*" +
            "([a-z0-9][a-z0-9-]{5,39})(?![a-z0-9-])"
    )
    private val deliveryKeywords = listOf("淘宝闪购", "闪购", "外卖", "骑手", "取餐", "备餐", "餐品")
    private val promotionKeywords = listOf(
        "优惠券", "领券", "红包", "广告", "推荐", "限时", "抢购", "立即下单", "快来下单",
        "去下单", "下单可", "下单即", "满减", "新人专享", "活动", "福利", "新品上市",
        "下单成功后", "支付成功后",
        "付款成功后", "教程", "攻略", "示例", "如果", "假如", "模拟通知"
    )
    private val nonDrinkKeywords = listOf(
        "奶茶色", "奶茶杯", "奶茶粉", "奶茶原料", "奶茶机", "奶茶设备", "奶茶吸管",
        "奶茶包装", "奶茶贴纸", "奶茶挂件", "奶茶钥匙扣", "奶茶模型", "奶茶玩具",
        "奶茶礼券", "奶茶券", "茶饮券",
        "奶茶兑换券", "快递", "包裹", "物流", "已发货", "已揽收", "驿站"
    )
    private val chatTitles = listOf("客服消息", "聊天消息", "旺旺", "淘友消息", "商家消息")
    private val chatPhrases = listOf("给您发来消息", "给你发来消息", "发来一条消息")
    private val cancelledKeywords = listOf(
        "已取消", "取消订单", "取消成功", "退款", "待付款", "未付款", "未支付", "支付失败", "下单失败"
    )
    private val stagePatterns = listOf(
        TeaOrderStage.DELIVERED to Regex("(?:订单|餐品|外卖|奶茶|骑手)(?:已送达|配送完成)"),
        TeaOrderStage.DELIVERING to Regex(
            "(?:骑手\\s*(?:已接单|已取餐|(?:已)?开始配送|正在配送|配送中|已出发|即将到达)|" +
                "(?:订单|餐品|外卖)\\s*(?:(?:已)?开始配送|正在配送|配送中))"
        ),
        TeaOrderStage.PREPARING to Regex(
            "(?:(?:商家|门店|店铺)(?:已接单|正在备餐|备餐中|正在制作|制作中)|" +
                "订单已接单|(?:奶茶|饮品|餐品)(?:正在制作|制作中))"
        ),
        TeaOrderStage.PLACED to Regex(
            "(?:订单(?:已支付|支付成功|付款成功|已付款|已提交|已确认|已下单|创建成功)|" +
                "下单成功|已成功下单)"
        ),
    )

    fun detect(
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?,
        teaKeywords: Set<String>,
    ): TeaOrderDetection {
        if (packageName != TAOBAO_PACKAGE) return ignored("通知不来自淘宝")
        val fields = listOf(title, text, bigText).map(::normalize)
        val content = fields.joinToString(" ") { it.text }.trim()
        if (content.isEmpty()) return ignored("通知内容为空")
        if (teaKeywords.size > MAX_KEYWORDS) {
            return TeaOrderDetection(TeaOrderDetectionStatus.UNCERTAIN, "关键词数量过多，无法完整确认")
        }
        if (chatTitles.any(fields.first().text::contains) || chatPhrases.any(content::contains)) {
            return ignored("聊天或客服内容，不确认下单")
        }
        if (promotionKeywords.any(content::contains)) return ignored("优惠或推广内容，不确认下单")
        if (nonDrinkKeywords.any(content::contains)) {
            return ignored("商品或物流内容，不确认现制茶饮下单")
        }
        if (cancelledKeywords.any(content::contains)) {
            return ignored("取消或退款信息，不确认有效茶饮订单")
        }

        val matchedTea = teaKeywords.asSequence()
            .filter { it.length <= MAX_KEYWORD_LENGTH }
            .map { normalize(it).text }
            .filter { it.length in 2..MAX_KEYWORD_LENGTH }
            .distinct()
            .filter(content::contains)
            .sorted()
            .toList()
        val matchedDelivery = deliveryKeywords.filter(content::contains).sorted()
        val stage = stagePatterns.firstOrNull { it.second.containsMatchIn(content) }?.first ?: TeaOrderStage.UNKNOWN
        if (matchedTea.isEmpty() && (matchedDelivery.isEmpty() || stage == TeaOrderStage.UNKNOWN)) {
            return ignored("没有发现茶饮或闪购订单线索")
        }
        val orderId = orderIdPattern.findAll(content)
            .map { it.groupValues[1] }
            .firstOrNull { id -> id.any(Char::isDigit) }
        val isTruncated = fields.any { it.truncated }
        val isConfirmed = !isTruncated && matchedTea.isNotEmpty() &&
            matchedDelivery.isNotEmpty() && stage != TeaOrderStage.UNKNOWN
        val reason = when {
            isTruncated -> "通知过长，无法完整确认订单内容"
            isConfirmed -> "检测到淘宝内闪购茶饮订单进展；不代表已经饮用"
            matchedTea.isEmpty() -> "检测到闪购订单进展，但无法确认是茶饮"
            matchedDelivery.isEmpty() -> "包含茶饮信息，但无法确认来自闪购外卖"
            else -> "包含茶饮与闪购信息，但缺少明确订单进展"
        }
        return TeaOrderDetection(
            status = if (isConfirmed) TeaOrderDetectionStatus.CONFIRMED else TeaOrderDetectionStatus.UNCERTAIN,
            reason = reason,
            orderId = orderId,
            matchedTeaKeywords = matchedTea,
            matchedDeliveryKeywords = matchedDelivery,
            stage = stage,
        )
    }

    private fun ignored(reason: String) = TeaOrderDetection(TeaOrderDetectionStatus.IGNORED, reason)

    private fun normalize(value: String?): NormalizedField {
        if (value.isNullOrEmpty()) return NormalizedField("", false)
        val normalized = Normalizer.normalize(value.take(MAX_FIELD_LENGTH), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return NormalizedField(
            text = whitespace.replace(normalized.take(MAX_FIELD_LENGTH), " ").trim(),
            truncated = value.length > MAX_FIELD_LENGTH || normalized.length > MAX_FIELD_LENGTH,
        )
    }

    private data class NormalizedField(val text: String, val truncated: Boolean)
}
