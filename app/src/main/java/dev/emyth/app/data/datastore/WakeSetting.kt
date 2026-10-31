/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

const val DEFAULT_SCHEDULED_WAKE_PROMPT =
    "这是你之前主动设置的定时唤醒。请务必根据唤醒备注，主动给用户发一条自然、关心的消息。" +
        "不要回复 [PASS]，你设这个唤醒就是为了在这个时刻找用户。"

@Serializable
enum class WakeRepeat(val value: String) {
    @SerialName("once")
    ONCE("once"),

    @SerialName("daily")
    DAILY("daily"),
}

@Serializable
data class ScheduledWake(
    val id: String = "",
    val triggerAtMillis: Long = 0,
    val note: String = "",
    val repeat: WakeRepeat = WakeRepeat.ONCE,
    val time: String? = null,
    val lastTriggeredAtMillis: Long = 0,
    val lastTriggeredDate: String? = null,
    val prompt: String? = null,
)

@Serializable
data class WakeSetting(
    val scheduledWakes: List<ScheduledWake> = emptyList(),
)

internal fun scheduledWakePrompt(customPrompt: String?): String =
    customPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SCHEDULED_WAKE_PROMPT

internal val ScheduledWake.effectivePrompt: String
    get() = scheduledWakePrompt(prompt)

internal fun ScheduledWake.isPending(nowMillis: Long): Boolean =
    repeat == WakeRepeat.DAILY || (lastTriggeredAtMillis == 0L && triggerAtMillis > nowMillis)

internal fun WakeSetting.withWakePrompt(id: String, prompt: String?, nowMillis: Long): WakeSetting {
    require(prompt == null || prompt.isNotBlank()) { "唤醒提示词不能为空，可以恢复默认提示词" }
    return copy(scheduledWakes = scheduledWakes.map { wake ->
        if (wake.id == id && wake.isPending(nowMillis)) wake.copy(prompt = prompt) else wake
    })
}

internal fun parseDailyWakeTime(value: String): LocalTime {
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)) {
        "time 必须是 24 小时制 HH:mm，例如 00:00 或 08:30"
    }
    return LocalTime.parse(value)
}

internal fun nextDailyWakeMillis(
    time: String,
    nowMillis: Long,
    zone: ZoneId,
    lastTriggeredDate: String? = null,
): Long {
    val localTime = parseDailyWakeTime(time)
    val now = Instant.ofEpochMilli(nowMillis)
    var date = now.atZone(zone).toLocalDate()
    while (true) {
        // Resolve each calendar day in its zone, including daylight-saving transitions.
        val candidate = date.atTime(localTime).atZone(zone)
        if (candidate.toInstant().isAfter(now) && candidate.toLocalDate().toString() != lastTriggeredDate) {
            return candidate.toInstant().toEpochMilli()
        }
        date = date.plusDays(1)
    }
}

internal fun ScheduledWake.rescheduled(nowMillis: Long, zone: ZoneId): ScheduledWake = when (repeat) {
    WakeRepeat.ONCE -> this
    WakeRepeat.DAILY -> copy(
        triggerAtMillis = nextDailyWakeMillis(
            time = requireNotNull(time) { "每日唤醒缺少 time" },
            nowMillis = nowMillis,
            zone = zone,
            lastTriggeredDate = lastTriggeredDate,
        )
    )
}

internal fun ScheduledWake.afterAlarm(
    scheduledAtMillis: Long,
    nowMillis: Long,
    zone: ZoneId,
): ScheduledWake? {
    if (scheduledAtMillis <= 0 || scheduledAtMillis > nowMillis) return null
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()
    when (repeat) {
        WakeRepeat.ONCE -> {
            if (lastTriggeredAtMillis != 0L || scheduledAtMillis != triggerAtMillis) return null
        }
        WakeRepeat.DAILY -> {
            if (lastTriggeredDate == today) return null
            // Ignore duplicate/older deliveries, but allow a deliberately corrected device clock.
            if (scheduledAtMillis <= lastTriggeredAtMillis && nowMillis >= lastTriggeredAtMillis) return null
        }
    }
    return copy(lastTriggeredAtMillis = nowMillis, lastTriggeredDate = today)
        .rescheduled(nowMillis, zone)
}
