/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.components.message

import dev.emyth.app.data.model.MessageNode
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole

/** Chat presentation only. Model history continues to use Conversation.currentMessages. */
internal fun MessageNode.isVisibleInChat(): Boolean {
    if (hideFromChat) return false
    val message = currentMessage
    val text = message.toText().trim()
    return !(message.role == MessageRole.ASSISTANT && text == "[SKIP]") &&
        !(message.role == MessageRole.USER && text.contains("[主动消息上下文]"))
}

internal fun List<MessageNode>.visibleChatNodes(): List<MessageNode> = filter { it.isVisibleInChat() }

/** Translate a persisted node identity to the index used by the filtered LazyColumn. */
internal fun List<MessageNode>.visibleChatNodeIndex(nodeId: Uuid): Int =
    visibleChatNodes().indexOfFirst { it.id == nodeId }

/** Keep the actual branch indices so changing a visible branch updates the right stored message. */
internal fun MessageNode.visibleChatBranchIndices(): List<Int> =
    messages.indices.filter { !messages[it].hideFromChat }
