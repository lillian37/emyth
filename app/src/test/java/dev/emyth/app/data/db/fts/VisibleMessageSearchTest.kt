package dev.emyth.app.data.db.fts

import dev.emyth.app.data.db.entity.MessageNodeEntity
import dev.emyth.app.utils.JsonInstant
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleMessageSearchTest {
    @Test
    fun hiddenVariantsDoNotAppearAsSearchSnippets() {
        val visible = UIMessage.assistant("Visible reply")
        val skipped = UIMessage.assistant("[PASS]").copy(hideFromChat = true)
        val node = node("node", listOf(visible, skipped), selected = 0)
        val hits = listOf(hit(node, skipped), hit(node, visible))

        assertEquals(listOf(hits.last()), filterVisibleMessageSearchResults(hits, listOf(node)))
    }

    @Test
    fun aCurrentlyHiddenNodeCannotProduceAnUnreachableSearchResult() {
        val visible = UIMessage.assistant("Older branch")
        val skipped = UIMessage.assistant("[PASS]").copy(hideFromChat = true)
        val node = node("node", listOf(visible, skipped), selected = 1)

        assertTrue(filterVisibleMessageSearchResults(listOf(hit(node, visible)), listOf(node)).isEmpty())
    }

    @Test
    fun normalSearchRankingIsPreservedWithoutChangingTheStoredMessages() {
        val first = UIMessage.assistant("First")
        val second = UIMessage.assistant("Second")
        val nodeA = node("A", listOf(first))
        val nodeB = node("B", listOf(second))
        val hits = listOf(hit(nodeB, second), hit(nodeA, first))
        val encoded = nodeA.messages

        assertEquals(hits, filterVisibleMessageSearchResults(hits, listOf(nodeA, nodeB)))
        assertEquals(encoded, nodeA.messages)
    }

    @Test
    fun legacyMessagesWithoutThePresentationFlagRemainSearchable() {
        val message = UIMessage.assistant("Legacy reply")
        val original = node("node", listOf(message))
        val legacyMessages = JsonInstant.parseToJsonElement(original.messages).jsonArray.map { encoded ->
            JsonObject(encoded.jsonObject.filterKeys { it != "hideFromChat" })
        }
        val legacy = original.copy(messages = JsonArray(legacyMessages).toString())
        val hits = listOf(hit(legacy, message))

        assertEquals(hits, filterVisibleMessageSearchResults(hits, listOf(legacy)))
    }

    @Test
    fun staleOrMismatchedSearchIndexEntriesDoNotRevealUnresolvedSnippets() {
        val message = UIMessage.assistant("Reply")
        val node = node("node", listOf(message))
        val missing = hit(node, message).copy(nodeId = "deleted")
        val wrongConversation = hit(node, message).copy(conversationId = "another conversation")

        assertTrue(filterVisibleMessageSearchResults(listOf(missing, wrongConversation), listOf(node)).isEmpty())
    }

    @Test
    fun anUnreadableNodeDoesNotBreakVisibleResultsFromOtherNodes() {
        val message = UIMessage.assistant("Reply")
        val valid = node("valid", listOf(message))
        val broken = node("broken", listOf(message)).copy(messages = "not valid JSON")
        val validHit = hit(valid, message)

        assertEquals(
            listOf(validHit),
            filterVisibleMessageSearchResults(listOf(hit(broken, message), validHit), listOf(broken, valid)),
        )
    }

    private fun node(id: String, messages: List<UIMessage>, selected: Int = 0) = MessageNodeEntity(
        id = id,
        conversationId = "conversation",
        nodeIndex = 0,
        messages = JsonInstant.encodeToString(messages),
        selectIndex = selected,
    )

    private fun hit(node: MessageNodeEntity, message: UIMessage) = MessageSearchResult(
        nodeId = node.id,
        messageId = message.id.toString(),
        conversationId = node.conversationId,
        title = "Title",
        updateAt = Instant.EPOCH,
        snippet = "[reply]",
    )
}
