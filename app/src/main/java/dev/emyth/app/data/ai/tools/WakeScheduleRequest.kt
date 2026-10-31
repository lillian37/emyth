/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai.tools

import dev.emyth.app.data.datastore.ScheduledWake
import dev.emyth.app.data.datastore.WakeRepeat
import dev.emyth.app.data.datastore.nextDailyWakeMillis
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId

internal fun parseWakeScheduleRequest(
    params: JsonObject,
    id: String,
    nowMillis: Long,
    zone: ZoneId,
): ScheduledWake {
    val repeatValue = params["repeat"]?.jsonPrimitive?.contentOrNull ?: WakeRepeat.ONCE.value
    val repeat = WakeRepeat.entries.firstOrNull { it.value == repeatValue }
        ?: throw IllegalArgumentException("repeat 只支持 once 或 daily")
    val note = params["note"]?.jsonPrimitive?.contentOrNull ?: ""
    return when (repeat) {
        WakeRepeat.ONCE -> {
            val triggerAt = params["trigger_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            require(triggerAt != null && triggerAt > nowMillis) {
                "一次性唤醒需要未来的 trigger_at 毫秒时间戳；每日唤醒请使用 repeat=daily 和 time=HH:mm"
            }
            ScheduledWake(id = id, triggerAtMillis = triggerAt, note = note)
        }
        WakeRepeat.DAILY -> {
            val time = params["time"]?.jsonPrimitive?.contentOrNull
            require(!time.isNullOrBlank()) { "repeat=daily 时必须提供 time，例如 00:00" }
            ScheduledWake(
                id = id,
                triggerAtMillis = nextDailyWakeMillis(time, nowMillis, zone),
                note = note,
                repeat = repeat,
                time = time,
            )
        }
    }
}
