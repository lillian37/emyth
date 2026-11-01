/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai.tools

import android.content.Context
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.datastore.WakeRepeat
import dev.emyth.app.data.datastore.effectivePrompt
import dev.emyth.app.data.datastore.isPending
import dev.emyth.app.data.datastore.rescheduled
import dev.emyth.app.data.service.ScheduledWakeScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.koin.java.KoinJavaComponent.getKoin
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

fun createScheduleWakeTool(context: Context): Tool = Tool(
    name = "schedule_wake",
    description = "设置自主唤醒。一次性：填写未来的 trigger_at 毫秒时间戳，repeat 可省略或填 once。" +
        "每天固定时间：填写 repeat=daily 和 time（24 小时制 HH:mm，例如 00:00），不需要 trigger_at。" +
        "每日计划按手机当地时间运行，触发后自动安排下一天，直到调用 cancel_wake 取消。" +
        "到点系统注入唤醒备注，可根据备注主动发消息或调用工具。",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            putJsonObject("repeat") {
                put("type", "string")
                put("enum", buildJsonArray { add("once"); add("daily") })
                put("description", "重复方式。省略或 once 为一次性；daily 为每天固定时间。")
            }
            putJsonObject("trigger_at") {
                put("type", "integer")
                put("description", "一次性模式必填：未来的毫秒时间戳。每日模式不使用此参数。")
            }
            putJsonObject("time") {
                put("type", "string")
                put("description", "daily 模式必填：手机当地的每天触发时间，24 小时制 HH:mm，例如 00:00、08:30。")
            }
            putJsonObject("note") {
                put("type", "string")
                put("description", "唤醒备注，例如提醒她睡觉或早上叫她起床。可选。")
            }
        })
    },
    execute = { args ->
        try {
            val zone = ZoneId.systemDefault()
            val wake = parseWakeScheduleRequest(
                params = args.jsonObject,
                id = UUID.randomUUID().toString(),
                nowMillis = System.currentTimeMillis(),
                zone = zone,
            )
            val exact = ScheduledWakeScheduler(context, getKoin().get<SettingsStore>()).add(wake)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("wake_id", wake.id)
                put("trigger_at", wake.triggerAtMillis)
                put("repeat", wake.repeat.value)
                wake.time?.let { put("time", it) }
                put("time_zone", zone.id)
                put("next_trigger_local", Instant.ofEpochMilli(wake.triggerAtMillis).atZone(zone).toString())
                put("note", wake.note)
                put("exact", exact)
                if (!exact) put("warning", "未获得系统精确闹钟权限，实际唤醒可能延后")
                put("result", if (wake.repeat == WakeRepeat.DAILY) {
                    "已设置每天 ${wake.time} 自主唤醒，会持续重复，直到取消"
                } else {
                    "已设置一次性自主唤醒"
                })
            }.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "设置唤醒失败")
            }.toString()))
        }
    }
)

fun createListWakesTool(context: Context): Tool = Tool(
    name = "list_wakes",
    description = "列出自主唤醒计划，包括重复方式、每日时刻、下次时间，以及当前的唤醒备注和提示词。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { _ ->
        try {
            val store = getKoin().get<SettingsStore>()
            val wakes = store.settingsFlowRaw.first().wakeSetting.scheduledWakes
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val arr = buildJsonArray {
                wakes.forEach { stored ->
                    val wake = stored.rescheduled(now, zone)
                    add(buildJsonObject {
                        put("id", wake.id)
                        put("trigger_at", wake.triggerAtMillis)
                        put("repeat", wake.repeat.value)
                        wake.time?.let { put("time", it) }
                        put("time_zone", zone.id)
                        put("note", wake.note)
                        put("wake_prompt", wake.effectivePrompt)
                        put("prompt_customized", !wake.prompt.isNullOrBlank())
                        put("pending", wake.isPending(now))
                        if (wake.lastTriggeredAtMillis > 0L) put("last_triggered_at", wake.lastTriggeredAtMillis)
                    })
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("count", wakes.size)
                put("wakes", arr)
            }.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "读取唤醒失败")
            }.toString()))
        }
    }
)

fun createCancelWakeTool(context: Context): Tool = Tool(
    name = "cancel_wake",
    description = "取消一次性或每日重复的自主唤醒计划，同时撤销系统闹钟。按 id 或备注匹配。",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            putJsonObject("id") {
                put("type", "string")
                put("description", "要取消的唤醒 id，来自 list_wakes 或 schedule_wake 的 wake_id。")
            }
            putJsonObject("note") {
                put("type", "string")
                put("description", "按备注模糊匹配，取消所有备注包含该文本的计划。提供 id 时优先按 id 取消。")
            }
        })
    },
    execute = { args ->
        try {
            val params = args.jsonObject
            val id = params["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val note = params["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            require(id != null || note != null) { "请提供要取消的 id 或非空备注 note" }
            val (cancelled, remaining) = ScheduledWakeScheduler(context, getKoin().get<SettingsStore>())
                .cancel(id, note)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("cancelled", cancelled)
                put("remaining", remaining)
            }.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "取消唤醒失败")
            }.toString()))
        }
    }
)
