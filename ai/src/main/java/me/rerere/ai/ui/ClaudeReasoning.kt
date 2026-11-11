package me.rerere.ai.ui

import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/** Local protocol bookkeeping; these keys must never be sent as Claude content-block fields. */
internal const val CLAUDE_REASONING_BLOCK_ID = "claude_reasoning_block_id"
internal const val CLAUDE_REDACTED_DATA = "claude_redacted_data"

/** Encrypted protocol data is retained for replay without becoming a visible empty thinking card. */
fun UIMessagePart.Reasoning.isClaudeRedactedThinking(): Boolean = metadata?.containsKey(CLAUDE_REDACTED_DATA) == true

internal fun UIMessagePart.Reasoning.canAppendReasoning(next: UIMessagePart.Reasoning): Boolean {
    val blockId = metadata?.get(CLAUDE_REASONING_BLOCK_ID)?.jsonPrimitiveOrNull?.contentOrNull
    val nextBlockId = next.metadata?.get(CLAUDE_REASONING_BLOCK_ID)?.jsonPrimitiveOrNull?.contentOrNull
    // Other providers retain their existing text-delta merge behavior. Claude signatures belong to one block only.
    if (blockId == null && nextBlockId == null) return true
    return blockId != null && blockId == nextBlockId &&
        metadata?.get(CLAUDE_REDACTED_DATA) == null && next.metadata?.get(CLAUDE_REDACTED_DATA) == null
}

internal fun UIMessagePart.Reasoning.hasClaudeReplayData(): Boolean =
    !metadata?.get("signature")?.jsonPrimitiveOrNull?.contentOrNull.isNullOrEmpty() ||
        !metadata?.get(CLAUDE_REDACTED_DATA)?.jsonPrimitiveOrNull?.contentOrNull.isNullOrEmpty()
