/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai

import dev.emyth.app.data.model.Conversation
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val PROACTIVE_INTERRUPTION_METADATA_KEY = "emyth_proactive_interruption"

/** Close one request's persisted activity without removing any partial model output. */
fun UIMessage.finalizeProactiveGeneration(at: Instant, interruption: String? = null): UIMessage {
    if (role != MessageRole.ASSISTANT) return this
    val reason = interruption?.ifBlank { "后台请求已中断" }
    var changed = finishedAt == null
    val finalizedParts = parts.map { part ->
        when {
            part is UIMessagePart.Reasoning && part.finishedAt == null -> {
                changed = true
                part.copy(
                    finishedAt = at,
                    metadata = if (reason != null) part.metadata.withInterruption(reason) else part.metadata,
                )
            }

            part is UIMessagePart.Tool && !part.isExecuted && reason != null -> {
                changed = true
                val result = JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("interrupted"),
                        "execution_state" to JsonPrimitive("unknown"),
                        "error" to JsonPrimitive(reason),
                    )
                )
                part.copy(
                    output = listOf(UIMessagePart.Text(result.toString())),
                    // An interrupted request cannot keep an actionable approval button. This does
                    // not claim the user denied it; nonempty output prevents execution/resumption.
                    approvalState = if (part.approvalState is ToolApprovalState.Pending) {
                        ToolApprovalState.Auto
                    } else {
                        part.approvalState
                    },
                    metadata = part.metadata.withInterruption(reason),
                )
            }

            else -> part
        }
    }
    if (!changed) return this
    return copy(
        parts = finalizedParts,
        finishedAt = finishedAt ?: at.toLocalDateTime(TimeZone.currentSystemDefault()),
    )
}

/** Operate on the latest conversation snapshot; never reinsert deleted or replace newer messages. */
fun Conversation.finalizeOwnedProactiveMessages(
    messageIds: Set<Uuid>,
    at: Instant,
    interruption: String? = null,
): Conversation {
    if (messageIds.isEmpty()) return this
    var changed = false
    val finalizedNodes = messageNodes.map { node ->
        var nodeChanged = false
        val finalizedMessages = node.messages.map { message ->
            if (message.id !in messageIds) {
                message
            } else {
                message.finalizeProactiveGeneration(at, interruption).also {
                    if (it !== message) {
                        nodeChanged = true
                        changed = true
                    }
                }
            }
        }
        if (nodeChanged) node.copy(messages = finalizedMessages) else node
    }
    return if (changed) copy(messageNodes = finalizedNodes) else this
}

/** A persisted unfinished thought cannot still be live after its owning request has disappeared. */
fun Conversation.recoverOrphanedReasoning(at: Instant, hasActiveGeneration: Boolean): Conversation {
    if (hasActiveGeneration) return this
    val orphanedMessageIds = messageNodes.asSequence()
        .flatMap { it.messages.asSequence() }
        .filter { message ->
            message.role == MessageRole.ASSISTANT && message.parts.any { part ->
                part is UIMessagePart.Reasoning && part.finishedAt == null
            }
        }
        .map { it.id }
        .toSet()
    return finalizeOwnedProactiveMessages(
        messageIds = orphanedMessageIds,
        at = at,
        interruption = "原请求已不在运行，已保留中断前的内容",
    )
}

/** The UI may show this marker without inventing an elapsed duration after process recovery. */
fun UIMessagePart.Reasoning.proactiveInterruptionReason(): String? =
    (metadata?.get(PROACTIVE_INTERRUPTION_METADATA_KEY) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

private fun JsonObject?.withInterruption(reason: String): JsonObject = JsonObject(
    orEmpty() + (PROACTIVE_INTERRUPTION_METADATA_KEY to JsonPrimitive(reason))
)
