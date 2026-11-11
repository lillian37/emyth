package dev.emyth.app.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageCotTest {
    private fun redacted(reasoning: String = "") = UIMessagePart.Reasoning(
        reasoning = reasoning,
        metadata = buildJsonObject { put("claude_redacted_data", "opaque-protocol-data") },
    )

    @Test
    fun encryptedThinkingAloneDoesNotCreateAnEmptyVisibleCard() {
        val blocks = listOf<UIMessagePart>(redacted(), redacted()).groupMessageParts()

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun hidingEncryptedBlocksKeepsVisibleReasoningAndToolsInTheirExistingOrder() {
        val first = UIMessagePart.Reasoning("Before tool")
        val tool = UIMessagePart.Tool("call-1", "lookup", "{}")
        val last = UIMessagePart.Reasoning("After tool")
        val text = UIMessagePart.Text("Answer")
        val blocks = listOf(first, redacted(), tool, redacted(), last, text).groupMessageParts()

        assertEquals(listOf(
            ThinkingStep.ReasoningStep(first), ThinkingStep.ToolStep(tool), ThinkingStep.ReasoningStep(last),
        ), (blocks[0] as MessagePartBlock.ThinkingBlock).steps)
        assertEquals(text, (blocks[1] as MessagePartBlock.ContentBlock).part)
        assertEquals(2, blocks.size)
    }

    @Test
    fun normalEmptyThinkingAndNonemptyReasoningKeepTheirExistingVisibility() {
        val normalEmpty = UIMessagePart.Reasoning("")
        val visible = redacted("Visible reasoning")
        val block = listOf(normalEmpty, visible).groupMessageParts().single() as MessagePartBlock.ThinkingBlock

        assertEquals(listOf(ThinkingStep.ReasoningStep(normalEmpty), ThinkingStep.ReasoningStep(visible)), block.steps)
    }
}
