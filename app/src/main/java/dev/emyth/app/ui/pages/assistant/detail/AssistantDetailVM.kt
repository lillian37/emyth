/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.emyth.app.data.datastore.Settings
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.files.FilesManager
import dev.emyth.app.data.files.SkillManager
import dev.emyth.app.data.files.SkillMetadata
import dev.emyth.app.data.model.Assistant
import dev.emyth.app.data.model.AssistantMemory
import dev.emyth.app.data.model.Avatar
import dev.emyth.app.data.model.Tag
import dev.emyth.app.data.db.entity.WorkspaceEntity
import dev.emyth.app.data.repository.MemoryRepository
import dev.emyth.app.data.repository.WorkspaceRepository
import dev.emyth.app.data.editprotection.ProtectedEditDeniedException
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)
    private val _editError = MutableStateFlow<String?>(null)
    val editError = _editError.asStateFlow()

    fun dismissEditError() {
        _editError.value = null
    }

    private fun launchEdit(onFinished: ((Boolean) -> Unit)? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                onFinished?.invoke(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProtectedEditDeniedException) {
                _editError.value = e.message ?: "编辑已被拦截，请先与助手协商授权。"
                onFinished?.invoke(false)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save assistant changes", e)
                _editError.value = "修改未能保存，请稍后重试。"
                onFinished?.invoke(false)
            }
        }
    }

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val externalMemories = settingsStore
        .settingsFlow
        .map { settings ->
            settings.externalMemories
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    fun updateExternalMemoryIds(memoryIds: Set<Uuid>) {
        viewModelScope.launch {
            val currentAssistant = assistant.value
            update(currentAssistant.copy(externalMemoryIds = memoryIds))
        }
    }

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        launchEdit {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistantTags = tags
                )
            )
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        launchEdit {
            val settings = settings.value
            val validTagIds = settings.assistantTags.map { it.id }.toSet()

            // 清理 assistant 中的无效 tag id
            val cleanedAssistants = settings.assistants.map { assistant ->
                val validTags = assistant.tags.filter { tagId ->
                    validTagIds.contains(tagId)
                }
                if (validTags.size != assistant.tags.size) {
                    assistant.copy(tags = validTags)
                } else {
                    assistant
                }
            }

            // 获取清理后的 assistant 中使用的 tag id
            val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

            // 清理未使用的 tags
            val cleanedTags = settings.assistantTags.filter { tag ->
                usedTagIds.contains(tag.id)
            }

            // 检查是否需要更新
            val needUpdateAssistants = cleanedAssistants != settings.assistants
            val needUpdateTags = cleanedTags.size != settings.assistantTags.size

            if (needUpdateAssistants || needUpdateTags) {
                settingsStore.update(
                    settings = settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                )
            }
        }
    }

    fun update(assistant: Assistant) {
        launchEdit {
            val settings = settings.value
            if (settings.init) return@launchEdit
            val previous = settings.assistants.find { it.id == assistant.id } ?: return@launchEdit
            settingsStore.update(
                settings = settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            assistant
                        } else {
                            it
                        }
                    })
            )
            // Rejected edits must not delete files belonging to the unchanged assistant.
            runCatching {
                checkAvatarDelete(old = previous, new = assistant)
                checkBackgroundDelete(old = previous, new = assistant)
            }.onFailure { Log.w(TAG, "Assistant saved; old attachment cleanup failed", it) }
        }
    }

    fun addMemory(memory: AssistantMemory, onFinished: (Boolean) -> Unit = {}) {
        launchEdit(onFinished) {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.addMemory(
                assistantId = memoryAssistantId,
                content = memory.content
            )
        }
    }

    fun updateMemory(memory: AssistantMemory, onFinished: (Boolean) -> Unit = {}) {
        launchEdit(onFinished) {
            memoryRepository.updateContent(id = memory.id, content = memory.content)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        launchEdit {
            memoryRepository.deleteMemory(id = memory.id)
        }
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
