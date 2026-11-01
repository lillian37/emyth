/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.canResumeToolExecution

/** Prepare one complete request; input transformers may prepend or insert several messages. */
internal suspend fun prepareProactiveMessages(
    systemPrompt: String,
    historyMessages: List<UIMessage>,
    userMessage: UIMessage,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): List<UIMessage> {
    val input = buildList {
        add(UIMessage.system(systemPrompt))
        addAll(filterInvalidProactiveToolMessages(historyMessages))
        add(userMessage)
    }
    val transformed = transform(input)
    return mergeProactiveMessages(filterInvalidProactiveToolMessages(transformed))
}

/** Keep resumable approvals; discard only messages whose unfinished tools cannot resume. */
internal fun filterInvalidProactiveToolMessages(messages: List<UIMessage>): List<UIMessage> =
    messages.filterNot { message ->
        val tools = message.getTools()
        val hasPendingTools = tools.any { !it.isExecuted }
        hasPendingTools && tools.none { !it.isExecuted && it.approvalState.canResumeToolExecution() }
    }

/** Preserve every part when providers require neighbouring messages to have different roles. */
internal fun mergeProactiveMessages(messages: List<UIMessage>): List<UIMessage> =
    messages.fold(emptyList()) { result, message ->
        val previous = result.lastOrNull()
        if (previous != null && previous.role == message.role) {
            result.dropLast(1) + previous.copy(parts = previous.parts + message.parts)
        } else {
            result + message
        }
    }
