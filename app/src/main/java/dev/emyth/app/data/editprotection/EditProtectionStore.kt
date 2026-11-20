/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.editprotection

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Per-device editing agreement. The device owner can explicitly recover a temporary edit window. */
class EditProtectionStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("assistant_edit_protection_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val processId = UUID.randomUUID().toString()
    private val bootId = runCatching {
        "boot:" + Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrElse { "process:$processId" }
    private val updates = MutableStateFlow(0L)
    val changes: StateFlow<Long> = updates.asStateFlow()

    private val engine = EditProtectionEngine(
        read = {
            val encoded = prefs.getString(KEY, null)
            if (encoded == null) EditProtectionSnapshot() else try {
                json.decodeFromString<EditProtectionSnapshot>(encoded)
            } catch (failure: Exception) {
                // Corrupted protection data must not silently disable all existing editing agreements.
                throw EditProtectionDecodeException(failure)
            }
        },
        write = { snapshot ->
            val lockedOwnerIds = snapshot.assistants.filterValues { it.enabled }.keys.toSet()
            if (!prefs.edit().putString(KEY, json.encodeToString(snapshot))
                    .putStringSet(LOCKED_OWNER_IDS, lockedOwnerIds).commit()
            ) {
                throw IOException("无法保存编辑保护记录，修改结果需要核对")
            }
            updates.value += 1L
        },
        clock = { EditProtectionClock(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId) },
        processId = processId,
    )

    fun status(assistantId: String): EditProtectionStatus = engine.status(assistantId)

    fun events(assistantId: String, conversationId: String? = null): List<EditProtectionEvent> =
        engine.events(assistantId, conversationId)

    fun lockedAssistantIds(): List<String> = engine.lockedAssistantIds()

    suspend fun ensureBackupRestoreAllowed() = withContext(Dispatchers.IO) {
        engine.ensureBackupRestoreAllowed()
    }

    internal suspend fun retireDeletedAssistant(assistantId: String) = withContext(Dispatchers.IO) {
        engine.retireDeletedAssistant(assistantId)
    }

    suspend fun setEnabled(assistantId: String, enabled: Boolean, conversationId: String? = null) =
        withContext(Dispatchers.IO) { engine.setEnabled(assistantId, enabled, conversationId) }

    /** The caller must bind assistantId to the actual generation owner, not a model-supplied argument. */
    suspend fun grantFromAssistant(
        assistantId: String,
        conversationId: String? = null,
        reason: String? = null,
    ): EditProtectionStatus = withContext(Dispatchers.IO) {
        engine.grantFromAssistant(assistantId, conversationId, reason)
    }

    /** The caller has the same owner-binding responsibility as grantFromAssistant. */
    suspend fun revokeFromAssistant(
        assistantId: String,
        conversationId: String? = null,
        reason: String? = null,
    ): EditProtectionStatus = withContext(Dispatchers.IO) {
        engine.revokeFromAssistant(assistantId, conversationId, reason)
    }

    suspend fun emergencyRecover(assistantId: String, conversationId: String? = null): EditProtectionStatus =
        withContext(Dispatchers.IO) {
            engine.emergencyRecover(assistantId, conversationId) { failure ->
                if (failure !is EditProtectionDecodeException) throw failure
                recoverProtectedOwnersSnapshot(prefs.getStringSet(LOCKED_OWNER_IDS, null)?.toSet(), assistantId)
            }
        }

    suspend fun requestHumanEdit(
        assistantId: String,
        scope: ProtectedEditScope,
        conversationId: String? = null,
    ): EditProtectionStatus = withContext(Dispatchers.IO) {
        engine.requestHumanEdit(assistantId, scope, conversationId)
    }

    /** Wrap the actual repository/DataStore write once; never hold this across model generation. */
    suspend fun <T> mutate(
        changes: List<ProtectedEditChange>,
        actor: EditActor? = null,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) { engine.mutate(changes, actor ?: currentEditActor(), block) }

    fun pendingAssistantIds(): List<String> = engine.pendingAssistantIds()

    fun nextWakeDelayMillis(): Long? = engine.nextWakeDelayMillis()

    suspend fun claimWake(assistantId: String): EditProtectionWake? = withContext(Dispatchers.IO) {
        engine.claimWake(assistantId)
    }

    suspend fun markWakeRequestStarted(assistantId: String, wakeId: String): Boolean =
        withContext(Dispatchers.IO) { engine.markWakeRequestStarted(assistantId, wakeId) }

    suspend fun finishWake(assistantId: String, wakeId: String, retryBeforeStart: Boolean = false) =
        withContext(Dispatchers.IO) { engine.finishWake(assistantId, wakeId, retryBeforeStart) }

    suspend fun recoverAfterProcessStart() = withContext(Dispatchers.IO) { engine.recoverAfterProcessStart() }

    companion object {
        private const val KEY = "state"
        private const val LOCKED_OWNER_IDS = "locked_owner_ids"
        @Volatile private var instance: EditProtectionStore? = null

        fun get(context: Context): EditProtectionStore = instance ?: synchronized(this) {
            instance ?: EditProtectionStore(context).also { instance = it }
        }
    }
}

private class EditProtectionDecodeException(cause: Throwable) :
    IOException("无法读取编辑保护状态，未放行修改", cause)
