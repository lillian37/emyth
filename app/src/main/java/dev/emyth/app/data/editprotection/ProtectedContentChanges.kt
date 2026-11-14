/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.editprotection

import dev.emyth.app.data.model.Assistant
import dev.emyth.app.data.repository.MemoryRepository

/** Shared built-in memories belong to every assistant currently configured to read that scope. */
internal fun builtinMemoryEditTargets(owner: String, assistants: List<Assistant>): List<String> =
    if (owner == MemoryRepository.GLOBAL_MEMORY_ID) {
        assistants.filter { it.useGlobalMemory }.map { it.id.toString() }.distinct()
    } else listOf(owner)

internal fun requireAssistantMemoryOwner(
    assistant: Assistant?,
    actorId: String,
    expectedOwner: String,
    actualRecordOwner: String? = null,
) {
    require(assistant != null && assistant.id.toString() == actorId) { "记忆工具所属助手已不存在或身份不匹配" }
    require(assistant.enableMemory) { "此助手的内置记忆已关闭" }
    val currentOwner = if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else actorId
    require(currentOwner == expectedOwner) { "助手的内置记忆范围已改变，请重新开始本轮请求" }
    require(actualRecordOwner == null || actualRecordOwner == expectedOwner) {
        "不能修改另一助手或不同记忆范围的记录"
    }
}

/** Inspect only the protected fields; routine model/settings changes must stay usable. */
internal fun assistantProtectedChanges(
    before: List<Assistant>,
    after: List<Assistant>,
): List<ProtectedEditChange> = buildList {
    val oldById = before.associateBy { it.id }
    val newById = after.associateBy { it.id }
    before.forEach { old ->
        val current = newById[old.id]
        val id = old.id.toString()
        if (current == null || old.systemPrompt != current.systemPrompt) {
            add(ProtectedEditChange(
                id, ProtectedEditScope.ASSISTANT_PROMPT,
                summary = protectedTextDifference(
                    if (current == null) "删除助手及其人设" else "助手人设",
                    old.systemPrompt, current?.systemPrompt,
                ),
            ))
        }
        if (current != null && old.useGlobalMemory != current.useGlobalMemory) {
            add(ProtectedEditChange(
                id, ProtectedEditScope.BUILTIN_MEMORY,
                summary = "内置记忆范围：${if (old.useGlobalMemory) "全局" else "助手独立"}" +
                    " → ${if (current.useGlobalMemory) "全局" else "助手独立"}",
            ))
        }
        if (current != null && old.enableMemory != current.enableMemory) {
            add(ProtectedEditChange(
                id, ProtectedEditScope.BUILTIN_MEMORY,
                summary = "内置记忆生效设置：${old.enableMemory} → ${current.enableMemory}",
            ))
        }
        if (current != null && old.allowConversationSystemPrompt != current.allowConversationSystemPrompt) {
            add(ProtectedEditChange(
                id, ProtectedEditScope.CONVERSATION_PROMPT,
                summary = "会话提示词生效设置：${old.allowConversationSystemPrompt}" +
                    " → ${current.allowConversationSystemPrompt}",
            ))
        }
    }
    after.filter { it.id !in oldById && it.systemPrompt.isNotBlank() }.forEach { added ->
        add(ProtectedEditChange(
            added.id.toString(), ProtectedEditScope.ASSISTANT_PROMPT,
            summary = protectedTextDifference("创建或导入助手人设", null, added.systemPrompt),
        ))
    }
}

internal data class ProtectedConversationPrompt(
    val assistantId: String,
    val conversationId: String,
    val prompt: String?,
)

/** Moving a nonempty prompt between assistants must not become an edit-lock bypass. */
internal fun conversationProtectedChanges(
    before: ProtectedConversationPrompt?,
    after: ProtectedConversationPrompt?,
): List<ProtectedEditChange> {
    if (before?.assistantId == after?.assistantId && before?.prompt.orEmpty() == after?.prompt.orEmpty()) {
        return emptyList()
    }
    val targets = buildList {
        if (before != null && !before.prompt.isNullOrEmpty()) add(before)
        if (after != null && !after.prompt.isNullOrEmpty()) add(after)
    }.distinctBy { it.assistantId }
    return targets.map { target ->
        ProtectedEditChange(
            target.assistantId,
            ProtectedEditScope.CONVERSATION_PROMPT,
            target.conversationId,
            protectedTextDifference("会话提示词", before?.prompt, after?.prompt) +
                if (before != null && after != null && before.assistantId != after.assistantId) {
                    "\n所属助手：${before.assistantId} → ${after.assistantId}"
                } else "",
        )
    }
}

/** Include the changed region, rather than truncating both values before a late edit is visible. */
internal fun protectedTextDifference(label: String, before: String?, after: String?): String {
    val old = before.orEmpty()
    val current = after.orEmpty()
    var prefix = 0
    while (prefix < minOf(old.length, current.length) && old[prefix] == current[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(old.length, current.length) - prefix &&
        old[old.lastIndex - suffix] == current[current.lastIndex - suffix]
    ) suffix++
    fun excerpt(value: String): String {
        if (value.isEmpty()) return "（空）"
        val start = (prefix - 40).coerceAtLeast(0)
        val end = (value.length - suffix + 40).coerceAtMost(value.length)
        val region = value.substring(start.coerceAtMost(end), end)
        val bounded = if (region.length <= 360) region else region.take(280) + "…" + region.takeLast(60)
        return (if (start > 0) "…" else "") + bounded + if (end < value.length) "…" else ""
    }
    return "$label\n修改前（${old.length}字）：${excerpt(old)}\n修改后（${current.length}字）：${excerpt(current)}"
}
