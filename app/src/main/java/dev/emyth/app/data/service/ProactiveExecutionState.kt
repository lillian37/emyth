/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import kotlinx.serialization.Serializable

internal const val PROACTIVE_EXECUTION_HISTORY_LIMIT = 8

@Serializable
internal enum class ProactiveExecutionStatus {
    PREPARING,
    REQUESTING,
    STREAMING,
    RUNNING_TOOLS,
    SKIPPED,
    BUSY,
    VOICE_CALL,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    START_FAILED;

    val isTerminal: Boolean
        get() = when (this) {
            PREPARING, REQUESTING, STREAMING, RUNNING_TOOLS -> false
            else -> true
        }
}

/** Local lifecycle metadata; details must not contain model text, prompts, or credentials. */
@Serializable
internal data class ProactiveExecution(
    val id: String,
    val scheduledAt: Long? = null,
    val startedAt: Long,
    val updatedAt: Long,
    val status: ProactiveExecutionStatus,
    val conversationId: String? = null,
    val detail: String? = null,
)

internal fun List<ProactiveExecution>.updateProactiveExecution(
    id: String,
    status: ProactiveExecutionStatus,
    now: Long,
    conversationId: String? = null,
    detail: String? = null,
): List<ProactiveExecution> {
    val index = indexOfFirst { it.id == id }
    if (index < 0) return this
    val current = this[index]
    if (current.status.isTerminal && status != current.status) return this
    if (!current.status.isTerminal && !status.isTerminal && now < current.updatedAt) return this

    val updated = current.copy(
        status = status,
        updatedAt = maxOf(current.updatedAt, now),
        conversationId = conversationId ?: current.conversationId,
        detail = detail ?: current.detail,
    )
    return mapIndexed { itemIndex, item -> if (itemIndex == index) updated else item }
        .take(PROACTIVE_EXECUTION_HISTORY_LIMIT)
}

/** A new process cannot confirm completion of an earlier process's unfinished requests. */
internal fun List<ProactiveExecution>.recoverUnfinishedProactiveExecutions(now: Long): List<ProactiveExecution> {
    if (none { !it.status.isTerminal }) return this
    return map { execution ->
        if (execution.status.isTerminal) execution else execution.copy(
            status = ProactiveExecutionStatus.INTERRUPTED,
            updatedAt = maxOf(execution.updatedAt, now),
            detail = "上次进程结束前未记录最终结果，无法确认本轮已完成",
        )
    }
}
