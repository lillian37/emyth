/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.editprotection

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same engine is exercised by JVM tests and the Android persistence wrapper. */
internal class EditProtectionEngine(
    private val read: () -> EditProtectionSnapshot,
    private val write: (EditProtectionSnapshot) -> Unit,
    private val clock: () -> EditProtectionClock,
    private val processId: String,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    fun status(assistantId: String): EditProtectionStatus = status(read(), assistantId, clock())

    fun events(assistantId: String, conversationId: String? = null): List<EditProtectionEvent> =
        read().events.filter {
            it.assistantId == assistantId &&
                (conversationId == null || it.conversationId == null || it.conversationId == conversationId)
        }

    fun lockedAssistantIds(): List<String> =
        read().assistants.filterValues { it.enabled }.keys.sorted()

    /** Backup overwrites several stores; a grant alone does not authorize replacing their whole contents. */
    suspend fun ensureBackupRestoreAllowed() = mutex.withLock {
        var snapshot = read()
        val locked = snapshot.assistants.filterValues { it.enabled }.keys
        if (locked.isEmpty()) return@withLock
        locked.forEach { assistantId ->
            snapshot = blockedWithEvent(snapshot, assistantId, null, null,
                "备份恢复已拦截，原数据未覆盖；请先在授权窗口关闭所有编辑保护后再恢复")
        }
        write(snapshot)
        throw IOException("备份恢复已拦截，请先在授权窗口关闭所有编辑保护后再恢复")
    }

    /** Only the SettingsStore deletion path may call this after its own durable write has succeeded. */
    internal suspend fun retireDeletedAssistant(assistantId: String) = mutex.withLock {
        val snapshot = read()
        val current = snapshot.assistants[assistantId] ?: return@withLock
        if (!current.enabled && current.grant == null && !current.pendingWake && current.claim == null) {
            return@withLock
        }
        val retired = current.copy(
            enabled = false,
            grant = null,
            pendingWake = false,
            pendingEventIds = emptyList(),
            lastWakeElapsed = null,
            lastWakeBootId = null,
            claim = null,
        )
        write(snapshot.withAssistant(assistantId, retired).append(
            event(assistantId, null, EditProtectionEventKind.DISABLED,
                summary = "助手已从设置中成功删除，对应编辑保护已停止；历史审计保留"),
        ))
    }

    suspend fun setEnabled(assistantId: String, enabled: Boolean, conversationId: String? = null) = mutex.withLock {
        require(assistantId.isNotBlank())
        var snapshot = read()
        val current = snapshot.assistants[assistantId] ?: AssistantEditProtection()
        if (current.enabled == enabled) return@withLock
        if (!enabled && !status(snapshot, assistantId, clock()).editingAllowed) {
            recordBlocked(snapshot, assistantId, null, conversationId, "尝试关闭编辑保护，未获授权，设置未改变")
            throw ProtectedEditDeniedException(assistantId, null)
        }
        snapshot = snapshot.withAssistant(assistantId, current.copy(enabled = enabled, grant = null))
        val event = event(
            assistantId, conversationId,
            if (enabled) EditProtectionEventKind.ENABLED else EditProtectionEventKind.DISABLED,
            summary = if (enabled) "已启用人设、会话提示词和内置记忆编辑保护" else "在授权窗口内关闭了编辑保护",
        )
        write(snapshot.append(event, wake = !enabled))
    }

    suspend fun grantFromAssistant(
        assistantId: String,
        conversationId: String? = null,
        reason: String? = null,
    ): EditProtectionStatus = mutex.withLock {
        val snapshot = read()
        val current = snapshot.assistants[assistantId] ?: AssistantEditProtection()
        val now = clock()
        // Repeated tool calls during one window do not silently extend the permission.
        if (!current.enabled || status(snapshot, assistantId, now).editingAllowed) {
            return@withLock status(snapshot, assistantId, now)
        }
        val grant = EditPermissionGrant(
            now.wallMillis, now.wallMillis + EDIT_PERMISSION_DURATION_MILLIS,
            now.elapsedMillis, now.elapsedMillis + EDIT_PERMISSION_DURATION_MILLIS, now.bootId,
        )
        val updated = snapshot.withAssistant(assistantId, current.copy(grant = grant)).append(
            event(assistantId, conversationId, EditProtectionEventKind.GRANTED,
                summary = "助手开放十五分钟编辑权限" + reason.asNote()),
        )
        write(updated)
        status(updated, assistantId, now)
    }

    suspend fun revokeFromAssistant(
        assistantId: String,
        conversationId: String? = null,
        reason: String? = null,
    ): EditProtectionStatus = mutex.withLock {
        val snapshot = read()
        val current = snapshot.assistants[assistantId] ?: AssistantEditProtection()
        if (current.grant == null) return@withLock status(snapshot, assistantId, clock())
        val updated = snapshot.withAssistant(assistantId, current.copy(grant = null)).append(
            event(assistantId, conversationId, EditProtectionEventKind.REVOKED,
                summary = "助手收回编辑权限" + reason.asNote()),
        )
        write(updated)
        status(updated, assistantId, clock())
    }

    suspend fun emergencyRecover(
        assistantId: String,
        conversationId: String? = null,
        recoverReadFailure: ((Throwable) -> EditProtectionSnapshot)? = null,
    ): EditProtectionStatus =
        mutex.withLock {
            var repaired = false
            val snapshot = try {
                read()
            } catch (failure: Exception) {
                val recovery = recoverReadFailure ?: throw failure
                recovery(failure).also { repaired = true }
            }
            val now = clock()
            val current = snapshot.assistants[assistantId] ?: AssistantEditProtection()
            val grant = EditPermissionGrant(
                now.wallMillis, now.wallMillis + EDIT_PERMISSION_DURATION_MILLIS,
                now.elapsedMillis, now.elapsedMillis + EDIT_PERMISSION_DURATION_MILLIS, now.bootId,
            )
            val updated = snapshot.withAssistant(assistantId, current.copy(grant = grant)).append(
                event(assistantId, conversationId, EditProtectionEventKind.RECOVERED,
                    summary = (if (repaired) "保护记录损坏，已按可靠记录保留原有锁并清除旧授权；" else "") +
                        "用户通过本地紧急恢复开放十五分钟编辑权限；保护未被永久关闭"),
                wake = true,
            )
            write(updated)
            status(updated, assistantId, now)
        }

    suspend fun requestHumanEdit(
        assistantId: String,
        scope: ProtectedEditScope,
        conversationId: String? = null,
    ): EditProtectionStatus = mutex.withLock {
        val snapshot = read()
        if (!status(snapshot, assistantId, clock()).editingAllowed) {
            recordBlocked(snapshot, assistantId, scope, conversationId, "用户请求编辑，尚未获授权；内容未改变")
        }
        status(read(), assistantId, clock())
    }

    suspend fun <T> mutate(
        changes: List<ProtectedEditChange>,
        actor: EditActor = EditActor.Human,
        block: suspend () -> T,
    ): T = mutex.withLock {
        if (changes.isEmpty()) return@withLock block()
        val snapshot = read()
        val now = clock()
        val denied = changes.filter { change ->
            when (actor) {
                EditActor.Human -> !status(snapshot, change.assistantId, now).editingAllowed
                is EditActor.Assistant -> actor.assistantId != change.assistantId
            }
        }
        if (denied.isNotEmpty()) {
            var blocked = snapshot
            denied.distinctBy { Triple(it.assistantId, it.scope, it.conversationId) }.forEach { change ->
                blocked = blockedWithEvent(blocked, change.assistantId, change.scope, change.conversationId,
                    "修改已拦截，内容未改变。" + change.summary.take(1_200))
            }
            write(blocked)
            throw ProtectedEditDeniedException(denied.first().assistantId, denied.first().scope)
        }
        val audited = if (actor == EditActor.Human) changes.filter {
            snapshot.assistants[it.assistantId]?.enabled == true
        } else emptyList()
        if (audited.isEmpty()) return@withLock block()

        // Persist the attempt first: a crash or a second persistence failure must not invent success.
        val attempts = audited.map { change ->
            event(change.assistantId, change.conversationId, EditProtectionEventKind.CHANGE_PENDING,
                change.scope, "授权范围内的修改正在保存，结果尚未确认。" + change.summary.take(1_200))
        }
        var pending = snapshot
        attempts.forEach { pending = pending.append(it, wake = true) }
        write(pending)
        try {
            val result = block()
            write(read().finishAttempts(attempts, EditProtectionEventKind.CHANGED,
                audited.map { "授权范围内的修改已保存。" + it.summary.take(1_200) }))
            result
        } catch (failure: Throwable) {
            // Keep an already committed CHANGED record if only later cleanup failed.
            val latest = read()
            if (attempts.any { attempt -> latest.events.any {
                    it.id == attempt.id && it.kind == EditProtectionEventKind.CHANGE_PENDING
                } }) {
                runCatching { write(latest.finishAttempts(attempts, EditProtectionEventKind.CHANGE_FAILED,
                    audited.map { "保存未能正常结束，修改结果需核对。" + it.summary.take(1_200) })) }
            }
            throw failure
        }
    }

    fun pendingAssistantIds(): List<String> {
        val snapshot = read()
        val now = clock()
        return snapshot.assistants.filterValues { it.pendingWake && it.claim == null && it.canWake(now) }.keys.toList()
    }

    fun nextWakeDelayMillis(): Long? {
        val now = clock()
        return read().assistants.values.filter { it.pendingWake && it.claim == null }.minOfOrNull { current ->
            if (current.canWake(now)) 0L else {
                (EDIT_WAKE_COOLDOWN_MILLIS - (now.elapsedMillis - current.lastWakeElapsed!!)).coerceAtLeast(0L)
            }
        }
    }

    suspend fun claimWake(assistantId: String): EditProtectionWake? = mutex.withLock {
        val snapshot = read()
        val current = snapshot.assistants[assistantId] ?: return@withLock null
        val now = clock()
        if (!current.pendingWake || current.claim != null || !current.canWake(now)) return@withLock null
        val selected = snapshot.events.filter { it.assistantId == assistantId && it.id in current.pendingEventIds }
        if (selected.isEmpty()) {
            write(snapshot.withAssistant(assistantId, current.copy(pendingWake = false, pendingEventIds = emptyList())))
            return@withLock null
        }
        val claim = EditWakeClaim(nextId(), processId, eventIds = selected.map { it.id })
        write(snapshot.withAssistant(assistantId, current.copy(
            pendingWake = false, pendingEventIds = emptyList(), claim = claim,
            lastWakeElapsed = now.elapsedMillis, lastWakeBootId = now.bootId,
        )))
        EditProtectionWake(claim.id, assistantId, selected.firstOrNull()?.conversationId, selected)
    }

    suspend fun markWakeRequestStarted(assistantId: String, wakeId: String): Boolean = mutex.withLock {
        val snapshot = read()
        val current = snapshot.assistants[assistantId] ?: return@withLock false
        val claim = current.claim ?: return@withLock false
        if (claim.id != wakeId || claim.processId != processId || claim.requestStarted) return@withLock false
        write(snapshot.withAssistant(assistantId, current.copy(claim = claim.copy(requestStarted = true))))
        true
    }

    suspend fun finishWake(assistantId: String, wakeId: String, retryBeforeStart: Boolean = false) = mutex.withLock {
        val snapshot = read()
        val current = snapshot.assistants[assistantId] ?: return@withLock
        val claim = current.claim ?: return@withLock
        if (claim.id != wakeId) return@withLock
        val retry = retryBeforeStart && !claim.requestStarted
        write(snapshot.withAssistant(assistantId, current.copy(
            claim = null,
            pendingWake = current.pendingWake || retry,
            pendingEventIds = if (retry) (current.pendingEventIds + claim.eventIds).distinct()
                else current.pendingEventIds,
        )))
    }

    suspend fun recoverAfterProcessStart() = mutex.withLock {
        val snapshot = read()
        val assistants = snapshot.assistants.mapValues { (_, current) ->
            val claim = current.claim
            if (claim == null || claim.processId == processId) current else current.copy(
                claim = null,
                pendingWake = current.pendingWake || !claim.requestStarted,
                pendingEventIds = if (claim.requestStarted) current.pendingEventIds
                    else (current.pendingEventIds + claim.eventIds).distinct(),
            )
        }
        if (assistants != snapshot.assistants) write(snapshot.copy(assistants = assistants))
    }

    private fun status(
        snapshot: EditProtectionSnapshot,
        assistantId: String,
        now: EditProtectionClock,
    ): EditProtectionStatus {
        val current = snapshot.assistants[assistantId] ?: AssistantEditProtection()
        val remaining = if (current.enabled) current.grant?.remaining(now) ?: 0L else 0L
        return EditProtectionStatus(current.enabled, !current.enabled || remaining > 0L,
            current.grant?.wallEnd?.takeIf { remaining > 0L }, remaining, current.pendingWake)
    }

    private fun recordBlocked(
        snapshot: EditProtectionSnapshot,
        assistantId: String,
        scope: ProtectedEditScope?,
        conversationId: String?,
        summary: String,
    ) = write(blockedWithEvent(snapshot, assistantId, scope, conversationId, summary))

    private fun blockedWithEvent(
        snapshot: EditProtectionSnapshot,
        assistantId: String,
        scope: ProtectedEditScope?,
        conversationId: String?,
        summary: String,
    ): EditProtectionSnapshot {
        val now = clock()
        val duplicate = snapshot.events.any {
            it.assistantId == assistantId && it.scope == scope && it.conversationId == conversationId &&
                it.kind == EditProtectionEventKind.BLOCKED && it.summary == summary &&
                now.wallMillis >= it.atMillis && now.wallMillis - it.atMillis < 30_000L
        }
        return if (duplicate) snapshot else snapshot.append(
            event(assistantId, conversationId, EditProtectionEventKind.BLOCKED, scope, summary), wake = true,
        )
    }

    private fun event(
        assistantId: String,
        conversationId: String?,
        kind: EditProtectionEventKind,
        scope: ProtectedEditScope? = null,
        summary: String,
    ) = EditProtectionEvent(nextId(), assistantId, conversationId, clock().wallMillis, kind, scope, summary.take(1_536))

    private fun EditProtectionSnapshot.withAssistant(id: String, value: AssistantEditProtection) =
        copy(assistants = assistants + (id to value))

    private fun EditProtectionSnapshot.append(
        event: EditProtectionEvent,
        wake: Boolean = false,
    ): EditProtectionSnapshot {
        val current = assistants[event.assistantId] ?: AssistantEditProtection()
        val counts = mutableMapOf<String, Int>()
        val bounded = (listOf(event) + events).filter {
            val count = (counts[it.assistantId] ?: 0) + 1
            counts[it.assistantId] = count
            count <= EDIT_EVENTS_PER_ASSISTANT
        }.take(EDIT_EVENT_LIMIT)
        return copy(
            events = bounded,
            assistants = if (!wake) assistants else assistants + (event.assistantId to current.copy(
                pendingWake = true,
                pendingEventIds = (current.pendingEventIds + event.id).takeLast(EDIT_EVENTS_PER_ASSISTANT),
            )),
        )
    }

    private fun EditProtectionSnapshot.finishAttempts(
        attempts: List<EditProtectionEvent>,
        kind: EditProtectionEventKind,
        summaries: List<String>,
    ): EditProtectionSnapshot {
        val byId = attempts.mapIndexed { index, event -> event.id to summaries[index] }.toMap()
        return copy(events = events.map {
            byId[it.id]?.let { summary -> it.copy(kind = kind, summary = summary.take(1_536)) } ?: it
        })
    }

    private fun AssistantEditProtection.canWake(now: EditProtectionClock): Boolean =
        lastWakeElapsed == null || lastWakeBootId != now.bootId || now.elapsedMillis < lastWakeElapsed ||
            now.elapsedMillis - lastWakeElapsed >= EDIT_WAKE_COOLDOWN_MILLIS

    private fun String?.asNote(): String = this?.trim()?.takeIf { it.isNotEmpty() }?.take(240)?.let { "；$it" } ?: ""
}
