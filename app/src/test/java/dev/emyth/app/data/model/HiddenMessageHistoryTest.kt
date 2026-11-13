package dev.emyth.app.data.model

import dev.emyth.app.data.db.entity.MessageNodeEntity
import dev.emyth.app.utils.JsonInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HiddenMessageHistoryTest {
    private val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001")

    @Test
    fun oldMessagesWithoutTheFlagRemainVisible() {
        val original = message("ordinary reply")
        val legacy = JsonObject(JsonInstant.encodeToJsonElement(UIMessage.serializer(), original).jsonObject -
            "hideFromChat")

        val decoded = JsonInstant.decodeFromString<UIMessage>(legacy.toString())

        assertFalse(decoded.hideFromChat)
        assertEquals(original, decoded)
    }

    @Test
    fun roomMessagesJsonRoundTripRetainsHiddenPassReasoningSignaturesAndUsage() {
        val pass = message("[PASS]").copy(
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "Consider whether to contact the user now.",
                    metadata = JsonObject(mapOf("signature" to JsonPrimitive("signed-thinking-block"))),
                ),
                UIMessagePart.Reasoning(
                    reasoning = "A second returned reasoning block.",
                    metadata = JsonObject(mapOf("data" to JsonPrimitive("encrypted-thinking-payload"))),
                ),
                UIMessagePart.Text("[PASS]"),
            ),
            usage = TokenUsage(promptTokens = 120, completionTokens = 25, cachedTokens = 100, totalTokens = 145),
            hideFromChat = true,
        )
        val node = MessageNode(messages = listOf(pass))
        val row = MessageNodeEntity(
            id = node.id.toString(),
            conversationId = Uuid.random().toString(),
            nodeIndex = 0,
            messages = JsonInstant.encodeToString(node.messages),
            selectIndex = node.selectIndex,
        )

        val restored = MessageNode(
            id = Uuid.parse(row.id),
            messages = JsonInstant.decodeFromString<List<UIMessage>>(row.messages),
            selectIndex = row.selectIndex,
        )
        val conversation = conversation(listOf(restored))

        assertTrue(restored.hideFromChat)
        assertEquals(node, restored)
        assertEquals(pass, conversation.currentMessages.single())
        assertEquals(pass.parts, conversation.currentMessages.single().parts)
        assertEquals(pass.usage, conversation.currentMessages.single().usage)
    }

    @Test
    fun hidingAPassChangesOnlyTheExistingSelectedBranchPresentation() {
        val user = message("hello", role = MessageRole.USER)
        val ordinary = message("normal alternative")
        val pass = message("[PASS]")
        val branch = MessageNode(messages = listOf(ordinary, pass), selectIndex = 1)
        val original = conversation(listOf(user.toMessageNode(), branch))

        val hidden = original.hideMessageFromChat(pass.id)

        assertEquals(original.id, hidden.id)
        assertEquals(original.messageNodes.map { it.id }, hidden.messageNodes.map { it.id })
        assertEquals(branch.selectIndex, hidden.messageNodes.last().selectIndex)
        assertSame(user, hidden.currentMessages.first())
        assertTrue(hidden.messageNodes.last().hideFromChat)
        assertEquals(pass.copy(hideFromChat = true), hidden.currentMessages.last())
        assertSame(ordinary, hidden.messageNodes.last().messages.first())
        assertFalse(original.messageNodes.last().hideFromChat)
    }

    @Test
    fun selectingAVisibleAlternativeDoesNotInheritTheHiddenBranchFlag() {
        val ordinary = message("normal alternative")
        val pass = message("[PASS]").copy(hideFromChat = true)
        val node = MessageNode(messages = listOf(ordinary, pass), selectIndex = 1)

        assertTrue(node.hideFromChat)
        assertFalse(node.copy(selectIndex = 0).hideFromChat)
        assertEquals(ordinary, node.copy(selectIndex = 0).currentMessage)
    }

    @Test
    fun aMissingOrDeletedMessageIsNotReinsertedByALateHideCallback() {
        val existing = message("still here")
        val original = conversation(listOf(existing.toMessageNode()))
        val deletedId = Uuid.random()

        assertSame(original, original.hideMessageFromChat(deletedId))
        assertEquals(listOf(existing), original.currentMessages)
    }

    @Test
    fun repeatingTheSameHideIsIdempotent() {
        val pass = message("[PASS]").copy(hideFromChat = true)
        val original = conversation(listOf(pass.toMessageNode()))

        assertSame(original, original.hideMessageFromChat(pass.id))
    }

    @Test
    fun hiddenWakeRepliesDoNotBecomeTheLastVisibleContact() {
        val user = message("I will be busy", MessageRole.USER).copy(
            createdAt = LocalDateTime.parse("2026-11-13T08:44:00")
        )
        val pass = message("[PASS]").copy(
            createdAt = LocalDateTime.parse("2026-11-13T10:44:00"),
            hideFromChat = true,
        )
        val conversation = conversation(listOf(user.toMessageNode(), pass.toMessageNode()))

        assertSame(user, conversation.lastVisibleMessage)
        assertEquals(listOf(user, pass), conversation.currentMessages)
    }

    @Test
    fun aConversationWithOnlyHiddenMessagesHasNoVisibleContact() {
        val pass = message("[PASS]").copy(hideFromChat = true)
        val conversation = conversation(listOf(pass.toMessageNode()))

        assertNull(conversation.lastVisibleMessage)
        assertEquals(listOf(pass), conversation.currentMessages)
    }

    @Test
    fun passTextAloneDoesNotAutomaticallyHideOrdinaryHistory() {
        val ordinary = message("[PASS]")

        assertFalse(ordinary.hideFromChat)
        assertFalse(ordinary.toMessageNode().hideFromChat)
    }

    private fun message(text: String, role: MessageRole = MessageRole.ASSISTANT) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun conversation(nodes: List<MessageNode>) = Conversation(
        assistantId = assistantId,
        messageNodes = nodes,
    )
}
