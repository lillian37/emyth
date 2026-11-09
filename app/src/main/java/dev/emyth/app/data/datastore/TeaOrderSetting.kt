/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.datastore

import kotlinx.serialization.Serializable

const val MAX_TEA_ORDER_KEYWORDS = 50
const val MIN_TEA_ORDER_KEYWORD_LENGTH = 2
const val MAX_TEA_ORDER_KEYWORD_LENGTH = 50

val DEFAULT_TEA_ORDER_KEYWORDS = listOf(
    "奶茶", "轻乳茶", "珍珠奶茶", "喜茶", "奈雪", "茶百道", "古茗", "蜜雪冰城",
    "霸王茶姬", "茶颜悦色", "沪上阿姨", "益禾堂", "书亦烧仙草", "甜啦啦", "一点点", "CoCo",
)

const val DEFAULT_TEA_ORDER_PROMPT =
    "收到了一条与用户淘宝闪购茶饮订单有关的通知证据。请结合聊天上下文主动联系用户，" +
        "可以关心这次茶饮的口味或选择。通知只反映订单信息，不代表用户实际喝了多少；" +
        "没有出现在证据中的细节请不要当成事实。"

@Serializable
data class TeaOrderSetting(
    val enabled: Boolean = false,
    val assistantId: String? = null,
    val keywords: List<String> = DEFAULT_TEA_ORDER_KEYWORDS,
    val prompt: String = DEFAULT_TEA_ORDER_PROMPT,
    val enabledSinceMillis: Long = 0L,
) {
    fun normalized(validAssistantIds: Set<String>? = null): TeaOrderSetting {
        val selectedId = assistantId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedKeywords = keywords.asSequence()
            .map { word -> word.filterNot { it.isISOControl() }.trim().take(MAX_TEA_ORDER_KEYWORD_LENGTH) }
            .filter { it.length >= MIN_TEA_ORDER_KEYWORD_LENGTH }
            .distinctBy { it.lowercase() }
            .take(MAX_TEA_ORDER_KEYWORDS)
            .toList()
        val validAssistant = selectedId != null && (validAssistantIds == null || selectedId in validAssistantIds)
        return copy(
            enabled = enabled && validAssistant && normalizedKeywords.isNotEmpty(),
            assistantId = selectedId,
            keywords = normalizedKeywords,
            enabledSinceMillis = enabledSinceMillis.coerceAtLeast(0L),
        )
    }

    fun withActivationTime(previous: TeaOrderSetting, nowMillis: Long): TeaOrderSetting = copy(
        enabledSinceMillis = if (
            enabled && (!previous.enabled || assistantId != previous.assistantId || previous.enabledSinceMillis <= 0L)
        ) {
            nowMillis.coerceAtLeast(1L)
        } else {
            previous.enabledSinceMillis.coerceAtLeast(0L)
        },
    )
}

val TeaOrderSetting.effectivePrompt: String
    get() = prompt.trim().ifBlank { DEFAULT_TEA_ORDER_PROMPT }
