/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.db.fts

import dev.emyth.app.data.db.entity.MessageNodeEntity
import dev.emyth.app.utils.JsonInstant
import me.rerere.ai.ui.UIMessage

/** UI-only projection. The underlying FTS index remains complete for model tools. */
internal fun filterVisibleMessageSearchResults(
    results: List<MessageSearchResult>,
    nodes: List<MessageNodeEntity>,
): List<MessageSearchResult> {
    val visibleByNode = nodes.associate { node ->
        val messages = runCatching { JsonInstant.decodeFromString<List<UIMessage>>(node.messages) }
            .getOrDefault(emptyList())
        val selected = messages.getOrNull(node.selectIndex)
        val ids = if (selected != null && !selected.hideFromChat) {
            messages.filterNot { it.hideFromChat }.mapTo(mutableSetOf()) { it.id.toString() }
        } else {
            emptySet()
        }
        node.id to (node.conversationId to ids)
    }
    return results.filter { result ->
        val visible = visibleByNode[result.nodeId]
        visible != null && visible.first == result.conversationId && result.messageId in visible.second
    }
}
