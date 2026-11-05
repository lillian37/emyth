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
import java.util.Locale

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
enum class ScheduledWakeExecutionStatus(val label: String, val isTerminal: Boolean = false) {
    @SerialName("started")
    STARTED("唤醒任务已启动"),

    @SerialName("preparing")
    PREPARING("正在准备回复"),

    @SerialName("requesting")
    REQUESTING("正在请求模型"),

    @SerialName("streaming")
    STREAMING("正在接收回复"),

    @SerialName("running_tools")
    RUNNING_TOOLS("正在执行工具"),

    @SerialName("completed")
    COMPLETED("回复已生成并保存", true),

    @SerialName("skipped")
    SKIPPED("模型选择跳过，本次未发送", true),

    @SerialName("failed")
    FAILED("生成失败", true),

    @SerialName("interrupted")
    INTERRUPTED("生成已中断", true),

    @SerialName("busy")
    BUSY("会话正忙，本次未发送", true),

    @SerialName("start_failed")
    START_FAILED("后台服务启动失败", true);

    val value: String get() = name.lowercase(Locale.ROOT)
}

@Serializable
data class ScheduledWakeExecution(
    val status: ScheduledWakeExecutionStatus,
    val scheduledAtMillis: Long,
    val updatedAtMillis: Long,
    val conversationId: String? = null,
    val detail: String? = null,
)

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
    val execution: ScheduledWakeExecution? = null,
)

@Serializable
data class WakeSetting(
    val scheduledWakes: List<ScheduledWake> = emptyList(),
)

private const val MAX_WAKE_EXECUTION_DETAIL_LENGTH = 240

/**
 * Update an existing plan's attempt without treating alarm delivery as a successful reply. A new
 * attempt must be explicitly started (or fail to start) for the currently scheduled occurrence;
 * afterAlarm authoritatively resets execution when the occurrence is actually consumed.
 *
 * Callers must pass generic diagnostics, never prompts, request bodies, credentials or model text.
 */
internal fun WakeSetting.withWakeExecution(
    id: String,
    scheduledAtMillis: Long,
    status: ScheduledWakeExecutionStatus,
    nowMillis: Long,
    conversationId: String? = null,
    detail: String? = null,
): WakeSetting {
    if (scheduledAtMillis <= 0L || nowMillis < 0L) return this
    var changed = false
    val updatedWakes = scheduledWakes.map { wake ->
        if (wake.id != id) return@map wake
        val previous = wake.execution
        val sameAttempt = previous?.scheduledAtMillis == scheduledAtMillis
        if (previous != null && scheduledAtMillis < previous.scheduledAtMillis) return@map wake
        if (sameAttempt) {
            requireNotNull(previous)
            // A failed duplicate dispatch says nothing about the service already running this attempt.
            if (status == ScheduledWakeExecutionStatus.START_FAILED && previous.status != status) return@map wake
            // A late progress/failure callback cannot replace a saved terminal result.
            if (previous.status.isTerminal && previous.status != status) return@map wake
            if (nowMillis < previous.updatedAtMillis && !status.isTerminal) return@map wake
            if (previous.status.isTerminal && nowMillis < previous.updatedAtMillis) return@map wake
        } else {
            // Legacy consumed one-time records have no known result; never invent one.
            if (previous == null && wake.repeat == WakeRepeat.ONCE && wake.lastTriggeredAtMillis != 0L) {
                return@map wake
            }
            if (scheduledAtMillis != wake.triggerAtMillis) return@map wake
            if (status != ScheduledWakeExecutionStatus.STARTED &&
                status != ScheduledWakeExecutionStatus.START_FAILED
            ) return@map wake
        }
        val execution = ScheduledWakeExecution(
            status = status,
            scheduledAtMillis = scheduledAtMillis,
            updatedAtMillis = if (sameAttempt) {
                maxOf(nowMillis, requireNotNull(previous).updatedAtMillis)
            } else {
                nowMillis
            },
            conversationId = conversationId?.takeIf { it.isNotBlank() }
                ?: previous?.conversationId?.takeIf { sameAttempt },
            detail = safeWakeExecutionDetail(detail)
                ?: previous?.takeIf { sameAttempt && it.status == status }?.detail,
        )
        if (execution == previous) wake else wake.copy(execution = execution).also { changed = true }
    }
    return if (changed) copy(scheduledWakes = updatedWakes) else this
}

private fun safeWakeExecutionDetail(value: String?): String? {
    if (value == null) return null
    return buildString {
        for (character in value) {
            if (length >= MAX_WAKE_EXECUTION_DETAIL_LENGTH) break
            append(if (character.isISOControl()) ' ' else character)
        }
    }.trim().takeIf { it.isNotBlank() }
}

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

internal fun WakeSetting.withWakeContent(
    id: String,
    note: String,
    prompt: String?,
    nowMillis: Long,
): WakeSetting {
    require(prompt == null || prompt.isNotBlank()) { "唤醒提示词不能为空，可以恢复默认提示词" }
    return copy(scheduledWakes = scheduledWakes.map { wake ->
        if (wake.id == id && wake.isPending(nowMillis)) wake.copy(note = note, prompt = prompt) else wake
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
    return copy(
        lastTriggeredAtMillis = nowMillis,
        lastTriggeredDate = today,
        execution = ScheduledWakeExecution(
            status = ScheduledWakeExecutionStatus.STARTED,
            scheduledAtMillis = scheduledAtMillis,
            updatedAtMillis = nowMillis,
        ),
    )
        .rescheduled(nowMillis, zone)
}
