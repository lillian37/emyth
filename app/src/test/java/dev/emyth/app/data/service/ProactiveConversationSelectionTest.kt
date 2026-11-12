package dev.emyth.app.data.service

import dev.emyth.app.data.model.Conversation
import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProactiveConversationSelectionTest {
    private val assistant = Uuid.parse("00000000-0000-0000-0000-000000000001")

    @Test
    fun anOlderPinnedChatDoesNotDisplaceTheMostRecentlyUpdatedChat() {
        val oldPinned = conversation(created = 100, updated = 200).copy(isPinned = true)
        val latest = conversation(created = 300, updated = 400)

        assertEquals(latest, listOf(oldPinned, latest).latestProactiveConversation(assistant))
    }

    @Test
    fun returningToAnOlderChatSelectsItsNewActivityRatherThanTheNewestCreatedChat() {
        val activeOlder = conversation(created = 100, updated = 500)
        val newerCreated = conversation(created = 300, updated = 400).copy(isPinned = true)

        assertEquals(activeOlder, listOf(newerCreated, activeOlder).latestProactiveConversation(assistant))
    }

    @Test
    fun conversationsOfAnotherAssistantCannotReceiveTheReply() {
        val target = conversation(created = 100, updated = 200)
        val other = conversation(created = 300, updated = 400).copy(
            assistantId = Uuid.parse("00000000-0000-0000-0000-000000000002")
        )

        assertEquals(target, listOf(other, target).latestProactiveConversation(assistant))
    }

    @Test
    fun absentTargetChatsReturnNullSoTheCallerCanCreateANewChat() {
        assertNull(emptyList<Conversation>().latestProactiveConversation(assistant))
    }

    private fun conversation(created: Long, updated: Long) = Conversation(
        assistantId = assistant,
        messageNodes = emptyList(),
        createAt = Instant.ofEpochMilli(created),
        updateAt = Instant.ofEpochMilli(updated),
    )
}
