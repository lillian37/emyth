/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai.tools

import android.content.Context
import dev.emyth.app.data.editprotection.EditProtectionStatus
import dev.emyth.app.data.editprotection.EditProtectionStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val EDIT_PERMISSION_REASON_LIMIT = 240

/** Only chat callers with an actual target can expose this authority-bearing tool. */
internal fun createAssistantEditPermissionTool(
    context: Context,
    callerAssistantId: String,
    callerConversationId: String?,
): Tool? {
    if (callerAssistantId.isBlank() || callerConversationId.isNullOrBlank()) return null
    val store = EditProtectionStore.get(context)
    if (!store.status(callerAssistantId).enabled) return null

    return createAssistantEditPermissionTool(
        callerAssistantId = callerAssistantId,
        callerConversationId = callerConversationId,
        onStatus = { assistantId -> store.status(assistantId).toToolResult("status", assistantId) },
        onGrant = { assistantId, conversationId, reason ->
            store.grantFromAssistant(assistantId, conversationId, reason).toToolResult("grant", assistantId)
        },
        onRevoke = { assistantId, conversationId, reason ->
            store.revokeFromAssistant(assistantId, conversationId, reason).toToolResult("revoke", assistantId)
        },
    )
}

private fun EditProtectionStatus.toToolResult(action: String, assistantId: String): JsonObject = buildJsonObject {
    val success = when (action) {
        "grant" -> enabled && editingAllowed && grantedUntilMillis != null && remainingMillis > 0L
        "revoke" -> enabled && !editingAllowed
        else -> true
    }
    put("success", success)
    put("action", action)
    put("assistant_id", assistantId)
    put("protection_enabled", enabled)
    put("editing_allowed", editingAllowed)
    put("remaining_seconds", remainingMillis / 1_000L)
    put("has_pending_wake", hasPendingWake)
    grantedUntilMillis?.let {
        put("expires_at_ms", it)
        put("expires_at", java.time.Instant.ofEpochMilli(it).toString())
    }
    put("scope", buildJsonArray {
        add("assistant_system_prompt")
        add("conversation_system_prompt")
        add("builtin_memory")
    })
    if (!success) put("error", "当前未形成所请求的编辑权限状态；请按返回的锁状态说明结果")
}

/** The caller identity is supplied by the chat pipeline, never by model-generated arguments. */
internal fun createAssistantEditPermissionTool(
    callerAssistantId: String,
    callerConversationId: String,
    onStatus: suspend (assistantId: String) -> JsonObject,
    onGrant: suspend (assistantId: String, conversationId: String, reason: String?) -> JsonObject,
    onRevoke: suspend (assistantId: String, conversationId: String, reason: String?) -> JsonObject,
): Tool {
    require(callerAssistantId.isNotBlank()) { "Permission tools need an actual calling assistant" }
    require(callerConversationId.isNotBlank()) { "Permission tools need an actual calling conversation" }

    return Tool(
        name = "assistant_edit_permission",
        description = """
            管理当前助手的人类编辑权限，范围仅为助手人设、会话提示词和软件内置记忆。
            action=status：查看编辑锁及授权状态，不改变权限。
            action=grant：与用户商量同意后，开放固定十五分钟的编辑权限；以实际工具返回为准。
            action=revoke：提前收回当前编辑权限。
            grant/revoke 可附 reason，作为一句简短的授权说明，最多 240 字符。
            目标助手和对话由应用绑定。不要传 assistant_id、conversation_id、duration 或其他参数。
            此工具不修改人设或记忆内容，也不改变助手自身记忆工具的权限。
            仅在真实工具返回授权成功后说明权限已开放，单独发出同意文字不会解锁。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("status")
                            add("grant")
                            add("revoke")
                        })
                        put("description", "status 查看；grant 授予固定十五分钟；revoke 提前收回")
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("maxLength", EDIT_PERMISSION_REASON_LIMIT)
                        put("description", "grant/revoke 的可选简短说明")
                    })
                },
                required = listOf("action"),
            )
        },
        // The owner explicitly requested assistant-controlled grants; other tool approvals are unchanged.
        needsApproval = false,
        execute = execute@{ input ->
            val params = input as? JsonObject ?: return@execute permissionToolError("参数必须为对象")
            if (params.keys.any { it != "action" && it != "reason" }) {
                return@execute permissionToolError("只支持 action 和 reason；目标身份与十五分钟时长由应用确定")
            }
            val action = (params["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@execute permissionToolError("action 必须为字符串")
            val reasonValue = params["reason"]
            if (reasonValue != null && reasonValue !== JsonNull &&
                (reasonValue !is JsonPrimitive || !reasonValue.isString)
            ) {
                return@execute permissionToolError("reason 必须为简短字符串")
            }
            val reason = (reasonValue as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.trim()?.takeIf { it.isNotEmpty() }
            if (reason != null && reason.length > EDIT_PERMISSION_REASON_LIMIT) {
                return@execute permissionToolError("reason 最多 $EDIT_PERMISSION_REASON_LIMIT 字符")
            }

            val result = when (action) {
                "status" -> {
                    if (reason != null) return@execute permissionToolError("status 不需要 reason")
                    onStatus(callerAssistantId)
                }
                "grant" -> onGrant(callerAssistantId, callerConversationId, reason)
                "revoke" -> onRevoke(callerAssistantId, callerConversationId, reason)
                else -> return@execute permissionToolError("action 仅支持 status、grant 或 revoke")
            }
            listOf(UIMessagePart.Text(result.toString()))
        },
    )
}

private fun permissionToolError(message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("success", false)
        put("error", message)
    }.toString())
)
