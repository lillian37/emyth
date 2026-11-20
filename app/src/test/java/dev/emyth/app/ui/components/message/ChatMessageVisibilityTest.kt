package dev.emyth.app.ui.components.message

import dev.emyth.app.data.model.MessageNode
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageVisibilityTest {
    @Test
    fun hiddenPassAndItsReasoningDoNotProduceAVisibleChatRow() {
        val hidden = MessageNode(messages = listOf(UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("existing thought"), UIMessagePart.Text("[PASS]")),
            hideFromChat = true,
        )))
        val visible = node("normal reply")

        val projection = listOf(hidden, visible).visibleChatNodes()

        assertEquals(listOf(visible.id), projection.map { it.id })
        assertSame(visible, projection.single())
        assertEquals(2, hidden.currentMessage.parts.size)
    }

    @Test
    fun literalPassWithoutTheVisibilityFlagRemainsVisible() {
        assertTrue(node("[PASS]").isVisibleInChat())
        assertTrue(node("Please explain [PASS]", role = MessageRole.USER).isVisibleInChat())
    }

    @Test
    fun theExistingSkipAndProactiveContextFiltersStillApply() {
        val legacySkip = node("[SKIP]")
        val context = node("[主动消息上下文]\ncurrent context", role = MessageRole.USER)
        val normal = node("ordinary message")

        assertEquals(listOf(normal), listOf(legacySkip, context, normal).visibleChatNodes())
    }

    @Test
    fun navigationUsesVisiblePositionsWhileRetainingTheOriginalNodeIdentity() {
        val first = node("first")
        val hidden = node("[PASS]", hidden = true)
        val legacySkip = node("[SKIP]")
        val last = node("last")
        val all = listOf(first, hidden, legacySkip, last)

        assertEquals(0, all.visibleChatNodeIndex(first.id))
        assertEquals(1, all.visibleChatNodeIndex(last.id))
        assertEquals(-1, all.visibleChatNodeIndex(hidden.id))
        assertEquals(-1, all.visibleChatNodeIndex(legacySkip.id))
        assertEquals(3, all.indexOf(last))
    }

    @Test
    fun hiddenOnlyHistoryHasNoPlaceholderRows() {
        assertTrue(listOf(node("[PASS]", hidden = true)).visibleChatNodes().isEmpty())
    }

    @Test
    fun branchNavigationRetainsStoredIndicesAcrossHiddenVariants() {
        val node = MessageNode(
            messages = listOf(
                message("[PASS]", hidden = true),
                message("first visible"),
                message("[PASS]", hidden = true),
                message("second visible"),
            ),
            selectIndex = 1,
        )

        assertEquals(listOf(1, 3), node.visibleChatBranchIndices())
        assertTrue(node.isVisibleInChat())
        val secondVisible = node.copy(selectIndex = node.visibleChatBranchIndices()[1])
        assertEquals("second visible", secondVisible.currentMessage.toText())
        assertFalse(node.copy(selectIndex = 2).isVisibleInChat())
    }

    private fun node(text: String, hidden: Boolean = false, role: MessageRole = MessageRole.ASSISTANT) =
        MessageNode(messages = listOf(message(text, hidden, role)))

    private fun message(text: String, hidden: Boolean = false, role: MessageRole = MessageRole.ASSISTANT) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        hideFromChat = hidden,
    )
}
