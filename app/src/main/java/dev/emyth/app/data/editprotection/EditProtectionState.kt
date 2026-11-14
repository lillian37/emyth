/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.editprotection

import java.io.IOException
import kotlinx.serialization.Serializable

const val EDIT_PERMISSION_DURATION_MILLIS = 15 * 60 * 1_000L
internal const val EDIT_WAKE_COOLDOWN_MILLIS = 60 * 1_000L
internal const val EDIT_EVENT_LIMIT = 128
internal const val EDIT_EVENTS_PER_ASSISTANT = 32

@Serializable
enum class ProtectedEditScope { ASSISTANT_PROMPT, CONVERSATION_PROMPT, BUILTIN_MEMORY }

data class ProtectedEditChange(
    val assistantId: String,
    val scope: ProtectedEditScope,
    val conversationId: String? = null,
    val summary: String = "",
)

sealed interface EditActor {
    data object Human : EditActor
    data class Assistant(val assistantId: String) : EditActor
}

@Serializable
enum class EditProtectionEventKind {
    ENABLED, DISABLED, GRANTED, REVOKED, RECOVERED, BLOCKED, CHANGE_PENDING, CHANGED, CHANGE_FAILED,
}

@Serializable
data class EditProtectionEvent(
    val id: String,
    val assistantId: String,
    val conversationId: String? = null,
    val atMillis: Long,
    val kind: EditProtectionEventKind,
    val scope: ProtectedEditScope? = null,
    val summary: String,
)

data class EditProtectionStatus(
    val enabled: Boolean,
    val editingAllowed: Boolean,
    val grantedUntilMillis: Long?,
    val remainingMillis: Long,
    val hasPendingWake: Boolean,
)

data class EditProtectionWake(
    val id: String,
    val assistantId: String,
    val conversationId: String?,
    val events: List<EditProtectionEvent>,
)

class ProtectedEditDeniedException(
    val assistantId: String,
    val scope: ProtectedEditScope?,
) : IllegalStateException("编辑已锁定，请先在聊天中申请十五分钟编辑权限")

/** Two clocks bound a grant; a boot identity prevents an elapsed-time reset from reviving it. */
data class EditProtectionClock(val wallMillis: Long, val elapsedMillis: Long, val bootId: String)

@Serializable
internal data class EditPermissionGrant(
    val wallStart: Long,
    val wallEnd: Long,
    val elapsedStart: Long,
    val elapsedEnd: Long,
    val bootId: String,
) {
    fun remaining(clock: EditProtectionClock): Long = if (
        clock.bootId != bootId || clock.wallMillis < wallStart || clock.elapsedMillis < elapsedStart
    ) 0L else minOf(wallEnd - clock.wallMillis, elapsedEnd - clock.elapsedMillis).coerceAtLeast(0L)
}

@Serializable
internal data class EditWakeClaim(
    val id: String,
    val processId: String,
    val requestStarted: Boolean = false,
    val eventIds: List<String> = emptyList(),
)

@Serializable
internal data class AssistantEditProtection(
    val enabled: Boolean = false,
    val grant: EditPermissionGrant? = null,
    val pendingWake: Boolean = false,
    val pendingEventIds: List<String> = emptyList(),
    val lastWakeElapsed: Long? = null,
    val lastWakeBootId: String? = null,
    val claim: EditWakeClaim? = null,
)

@Serializable
internal data class EditProtectionSnapshot(
    val assistants: Map<String, AssistantEditProtection> = emptyMap(),
    val events: List<EditProtectionEvent> = emptyList(),
)

/** Used only after explicit local recovery; unknown owners must never be silently unlocked. */
internal fun recoverProtectedOwnersSnapshot(
    knownLockedOwnerIds: Set<String>?,
    assistantId: String,
): EditProtectionSnapshot {
    if (knownLockedOwnerIds == null || assistantId !in knownLockedOwnerIds ||
        knownLockedOwnerIds.any { it.isBlank() }
    ) {
        throw IOException("无法可靠确认原有锁范围，未清除编辑保护；请保留当前数据")
    }
    return EditProtectionSnapshot(
        assistants = knownLockedOwnerIds.associateWith { AssistantEditProtection(enabled = true) }
    )
}
