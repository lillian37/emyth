/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.pages.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import dev.emyth.app.R
import dev.emyth.app.data.datastore.Settings
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.editprotection.ProtectedEditDeniedException
import dev.emyth.app.data.datastore.getCurrentChatModel
import dev.emyth.app.data.files.FilesManager
import dev.emyth.app.data.model.Assistant
import dev.emyth.app.data.model.Avatar
import dev.emyth.app.data.model.Conversation
import dev.emyth.app.data.model.MessageNode
import dev.emyth.app.data.model.NodeFavoriteTarget
import dev.emyth.app.data.repository.ConversationRepository
import dev.emyth.app.data.repository.FavoriteRepository
import dev.emyth.app.service.ChatError
import dev.emyth.app.service.ChatService
import dev.emyth.app.ui.hooks.writeStringPreference
import dev.emyth.app.ui.hooks.ChatInputState
import dev.emyth.app.utils.UiState
import dev.emyth.app.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部
    var editProtectionError by mutableStateOf<String?>(null)
        private set

    fun dismissEditProtectionError() {
        editProtectionError = null
    }

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            try {
                val oldSettings = settings.value
                settingsStore.update(newSettings)
                runCatching { checkUserAvatarDelete(oldSettings, newSettings) }
                    .onFailure { Log.w(TAG, "Settings saved; old avatar cleanup failed", it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProtectedEditDeniedException) {
                editProtectionError = e.message ?: "修改已被拦截，请先获得助手授权。"
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save settings", e)
                editProtectionError = "设置未能保存，请稍后重试。"
            }
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                conversationRepo.deleteConversation(conversation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProtectedEditDeniedException) {
                editProtectionError = e.message ?: "会话删除已被拦截，请先获得助手授权。"
            } catch (e: Exception) {
                Log.e(TAG, "Unable to delete conversation", e)
                editProtectionError = "会话未能删除，请稍后重试。"
            }
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            try {
                // Folder membership belongs to an assistant and must be cleared after a move.
                if (conversation.id == _conversationId) {
                    val session = chatService.getOrCreateSession(_conversationId)
                    session.saveMutex.withLock {
                        val current = chatService.getConversationFlow(_conversationId).value
                        val updated = current.copy(assistantId = targetAssistantId, folderId = null)
                        if (conversationRepo.existsConversationById(_conversationId)) {
                            conversationRepo.updateConversation(updated)
                        } else {
                            conversationRepo.insertConversation(updated)
                        }
                        chatService.updateConversationState(_conversationId) {
                            it.copy(assistantId = targetAssistantId, folderId = null)
                        }
                    }
                    settingsStore.updateAssistant(targetAssistantId)
                } else {
                    val current = conversationRepo.getConversationById(conversation.id) ?: return@launch
                    conversationRepo.updateConversation(
                        current.copy(assistantId = targetAssistantId, folderId = null)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProtectedEditDeniedException) {
                editProtectionError = e.message ?: "会话移动已被拦截，请先获得助手授权。"
            } catch (e: Exception) {
                Log.e(TAG, "Unable to move conversation", e)
                editProtectionError = "会话未能完成移动，请检查当前归属后重试。"
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    /** Commit the protected prompt before exposing it to the in-memory model context. */
    fun updateConversationSystemPrompt(prompt: String?) {
        viewModelScope.launch {
            try {
                val session = chatService.getOrCreateSession(_conversationId)
                session.saveMutex.withLock {
                    val current = chatService.getConversationFlow(_conversationId).value
                    if (current.customSystemPrompt == prompt) return@withLock
                    val updated = current.copy(customSystemPrompt = prompt)
                    if (conversationRepo.existsConversationById(_conversationId)) {
                        conversationRepo.updateConversation(updated)
                    } else {
                        conversationRepo.insertConversation(updated)
                    }
                    chatService.updateConversationState(_conversationId) {
                        it.copy(customSystemPrompt = prompt)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProtectedEditDeniedException) {
                editProtectionError = e.message ?: "会话提示词修改已被拦截，请先获得助手授权。"
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save conversation prompt", e)
                editProtectionError = "会话提示词未能保存，请稍后重试。"
            }
        }
    }

    /** 删除某条消息的思考链（reasoning），省 token，保留正文和工具记录 */
    fun clearMessageThinking(message: UIMessage) {
        viewModelScope.launch {
            val conv = conversation.value
            val newMessages = conv.currentMessages.map { msg ->
                if (msg.id == message.id) {
                    msg.copy(parts = msg.parts.filterNot { it is UIMessagePart.Reasoning })
                } else msg
            }
            chatService.updateConversationState(_conversationId) { conv.updateCurrentMessages(newMessages) }
        }
    }

    /** 删除某条消息的工具调用记录（tool/tool_call/tool_result），省 token，保留正文和思考链 */
    fun clearMessageToolRecords(message: UIMessage) {
        viewModelScope.launch {
            val conv = conversation.value
            val newMessages = conv.currentMessages.map { msg ->
                if (msg.id == message.id) {
                    msg.copy(parts = msg.parts.filterNot {
                        it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult
                    })
                } else msg
            }
            chatService.updateConversationState(_conversationId) { conv.updateCurrentMessages(newMessages) }
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
