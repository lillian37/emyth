package dev.emyth.app.data.editprotection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditProtectionRequestContextTest {
    private val locked = EditProtectionStatus(true, false, null, 0L, false)

    @Test
    fun disabledWithoutEventsLeavesTheRequestIdentical() {
        val messages = listOf(UIMessage.system("persona"), UIMessage.user("hello"))
        val result = appendEditProtectionContext(
            messages, "a", "chat", locked.copy(enabled = false, editingAllowed = true), emptyList(),
        )
        assertSame(messages, result)
    }

    @Test
    fun appDataGoesAtTheLastUserTailWithoutChangingStoredTextOrHistory() {
        val system = UIMessage.system("stable persona")
        val previous = UIMessage.assistant("stable history")
        val user = UIMessage.user("actual user words")
        val original = listOf(system, previous, user)
        val result = appendEditProtectionContext(original, "a", "chat", locked, listOf(event("a", "chat")))

        assertSame(system, result[0])
        assertSame(previous, result[1])
        assertEquals(user.id, result.last().id)
        assertSame(user.parts.first(), result.last().parts.first())
        assertEquals(1, user.parts.size)
        assertTrue(text(result.last()).contains("不是用户发言"))
        assertTrue(text(result.last()).contains("actual user words"))
        assertTrue(text(result.last()).contains("BLOCKED"))
    }

    @Test
    fun ordinaryRequestsDoNotReadAnotherAssistantOrPrivateConversation() {
        val result = appendEditProtectionContext(
            listOf(UIMessage.user("hello")), "a", "chat", locked,
            listOf(event("a", "chat", "same"), event("a", "other", "other-chat"),
                event("b", "chat", "other-assistant"), event("a", null, "shared")),
        )
        val content = text(result.last())
        assertTrue(content.contains("same"))
        assertTrue(content.contains("shared"))
        assertFalse(content.contains("other-chat"))
        assertFalse(content.contains("other-assistant"))
    }

    @Test
    fun aWakeReceiptIncludesEveryClaimedEventButNeverAnotherAssistant() {
        val result = appendEditProtectionContext(
            listOf(UIMessage.user("wake")), "a", "chat", locked,
            listOf(event("a", "chat", "one"), event("a", "other", "two"), event("b", null, "foreign")),
            includeOtherConversations = true,
        )
        val content = text(result.last())
        assertTrue(content.contains("one"))
        assertTrue(content.contains("two"))
        assertFalse(content.contains("foreign"))
    }

    @Test
    fun rebuildingTheSameRequestReplacesOnlyTheAppPart() {
        val initial = appendEditProtectionContext(
            listOf(UIMessage.user("user")), "a", "chat", locked, listOf(event("a", null, "before")),
        )
        val updated = appendEditProtectionContext(
            initial, "a", "chat", locked, listOf(event("a", null, "after")),
        )
        assertEquals(2, updated.single().parts.size)
        assertFalse(text(updated.single()).contains("before"))
        assertTrue(text(updated.single()).contains("after"))
        assertTrue(text(initial.single()).contains("before"))
    }

    @Test
    fun toolOnlyRequestsGetAClearlyLabelledApplicationContext() {
        val original = listOf(UIMessage.system("persona"))
        val result = appendEditProtectionContext(original, "a", null, locked, emptyList())
        assertEquals(MessageRole.USER, result.last().role)
        assertTrue(text(result.last()).startsWith("[应用追加的编辑保护记录"))
        assertEquals(1, original.size)
    }

    @Test
    fun trustedToolActorIsScopedToItsCoroutineAndDoesNotLeakIntoHumanWrites() = runBlocking {
        assertEquals(EditActor.Human, currentEditActor())
        withContext(EditAssistantActor("actual-owner")) {
            assertEquals(EditActor.Assistant("actual-owner"), currentEditActor())
        }
        assertEquals(EditActor.Human, currentEditActor())
    }

    private fun event(assistantId: String, conversationId: String?, summary: String = "blocked edit") =
        EditProtectionEvent("$assistantId-$conversationId-$summary", assistantId, conversationId, 1_000L,
            EditProtectionEventKind.BLOCKED, ProtectedEditScope.ASSISTANT_PROMPT, summary)

    private fun text(message: UIMessage) = message.parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
}
