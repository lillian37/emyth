/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import dev.emyth.app.data.model.Conversation
import kotlin.uuid.Uuid

/** List pinning controls navigation order, not which chat should receive a background reply. */
internal fun Iterable<Conversation>.latestProactiveConversation(assistantId: Uuid): Conversation? =
    filter { it.assistantId == assistantId }.maxWithOrNull(
        compareBy<Conversation> { it.updateAt }.thenBy { it.createAt }.thenBy { it.id.toString() }
    )
