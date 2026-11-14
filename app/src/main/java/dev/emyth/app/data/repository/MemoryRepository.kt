/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.emyth.app.data.db.dao.MemoryDAO
import dev.emyth.app.data.db.entity.MemoryEntity
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.editprotection.EditActor
import dev.emyth.app.data.editprotection.EditProtectionStore
import dev.emyth.app.data.editprotection.ProtectedEditChange
import dev.emyth.app.data.editprotection.ProtectedEditScope
import dev.emyth.app.data.editprotection.protectedTextDifference
import dev.emyth.app.data.editprotection.builtinMemoryEditTargets
import dev.emyth.app.data.editprotection.requireAssistantMemoryOwner
import dev.emyth.app.data.model.AssistantMemory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val settingsStore: SettingsStore,
    context: Context,
) {
    private val editProtection = EditProtectionStore.get(context)
    private val mutationMutex = Mutex()

    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.filterNot { it.isSummaryMemory() }
                    .map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .filterNot { it.isSummaryMemory() }
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.filterNot { it.isSummaryMemory() }
                    .map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .filterNot { it.isSummaryMemory() }
            .map { AssistantMemory(it.id, it.content) }
    }

    private fun MemoryEntity.isSummaryMemory(): Boolean {
        return content.startsWith("[daily_summary]") ||
            content.startsWith("[phase_summary]") ||
            content.startsWith("[auto_summary]")
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) = mutationMutex.withLock {
        val existing = memoryDAO.getMemoriesOfAssistant(assistantId)
        if (existing.isEmpty()) return@withLock
        val summary = protectedTextDifference(
            "删除 ${existing.size} 条内置记忆",
            existing.take(3).joinToString("\n") { "#${it.id}: ${it.content}" },
            null,
        )
        protectedHumanMutation(assistantId, summary) {
            memoryDAO.deleteMemoriesOfAssistant(assistantId)
        }
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory = mutationMutex.withLock {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        if (old.content == content) return@withLock AssistantMemory(old.id, old.content)
        protectedHumanMutation(
            old.assistantId, protectedTextDifference("修改内置记忆 #$id", old.content, content),
        ) { update(old, content) }
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory = mutationMutex.withLock {
        protectedHumanMutation(
            assistantId, protectedTextDifference("新增内置记忆", null, content),
        ) { insert(assistantId, content) }
    }

    suspend fun deleteMemory(id: Int) = mutationMutex.withLock {
        val old = memoryDAO.getMemoryById(id) ?: return@withLock
        protectedHumanMutation(
            old.assistantId, protectedTextDifference("删除内置记忆 #$id", old.content, null),
        ) { memoryDAO.deleteMemory(id) }
    }

    /** Bound by the real model invocation, never by a tool JSON origin or a supplied record owner. */
    suspend fun addMemoryFromAssistant(actorId: String, expectedOwner: String, content: String): AssistantMemory =
        mutationMutex.withLock {
            requireAssistantScope(actorId, expectedOwner)
            editProtection.mutate(assistantChanges(actorId), EditActor.Assistant(actorId)) {
                requireAssistantScope(actorId, expectedOwner)
                insert(expectedOwner, content)
            }
        }

    suspend fun updateContentFromAssistant(
        actorId: String,
        expectedOwner: String,
        id: Int,
        content: String,
    ): AssistantMemory = mutationMutex.withLock {
        requireAssistantScope(actorId, expectedOwner)
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        requireAssistantScope(actorId, expectedOwner, old.assistantId)
        if (old.content == content) return@withLock AssistantMemory(old.id, old.content)
        editProtection.mutate(assistantChanges(actorId), EditActor.Assistant(actorId)) {
            requireAssistantScope(actorId, expectedOwner, old.assistantId)
            update(old, content)
        }
    }

    suspend fun deleteMemoryFromAssistant(actorId: String, expectedOwner: String, id: Int) = mutationMutex.withLock {
        requireAssistantScope(actorId, expectedOwner)
        val old = memoryDAO.getMemoryById(id) ?: return@withLock
        requireAssistantScope(actorId, expectedOwner, old.assistantId)
        editProtection.mutate(assistantChanges(actorId), EditActor.Assistant(actorId)) {
            requireAssistantScope(actorId, expectedOwner, old.assistantId)
            memoryDAO.deleteMemory(id)
        }
    }

    private suspend fun <T> protectedHumanMutation(owner: String, summary: String, block: suspend () -> T): T {
        val changes = humanChanges(owner, summary)
        return editProtection.mutate(changes) {
            // A scope switch may have finished while this operation waited for the permission lock.
            val checkedTargets = changes.mapTo(mutableSetOf()) { it.assistantId }
            check(humanChanges(owner, summary).all { it.assistantId in checkedTargets }) {
                "共享记忆的所属范围已改变，请重新保存；本次未修改内容"
            }
            block()
        }
    }

    private suspend fun humanChanges(owner: String, summary: String): List<ProtectedEditChange> {
        val targets = builtinMemoryEditTargets(owner, settingsStore.settingsFlowRaw.first().assistants)
        return targets.distinct().map {
            ProtectedEditChange(it, ProtectedEditScope.BUILTIN_MEMORY, summary = summary)
        }
    }

    private fun assistantChanges(actorId: String) = listOf(
        ProtectedEditChange(actorId, ProtectedEditScope.BUILTIN_MEMORY)
    )

    private suspend fun requireAssistantScope(
        actorId: String,
        expectedOwner: String,
        actualRecordOwner: String? = null,
    ) {
        val assistant = settingsStore.settingsFlowRaw.first().assistants.find { it.id.toString() == actorId }
        requireAssistantMemoryOwner(assistant, actorId, expectedOwner, actualRecordOwner)
    }

    private suspend fun insert(owner: String, content: String): AssistantMemory = AssistantMemory(
        id = memoryDAO.insertMemory(MemoryEntity(assistantId = owner, content = content)).toInt(),
        content = content,
    )

    private suspend fun update(old: MemoryEntity, content: String): AssistantMemory {
        memoryDAO.updateMemory(old.copy(content = content))
        return AssistantMemory(old.id, content)
    }
}

