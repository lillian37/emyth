package dev.emyth.app.data.ai

import dev.emyth.app.data.model.Conversation
import dev.emyth.app.data.model.MessageNode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ProactiveGenerationStateTest {
    private val startedAt = Instant.parse("2026-11-03T00:00:00Z")
    private val endedAt = Instant.parse("2026-11-03T00:01:00Z")
    private val laterAt = Instant.parse("2026-11-03T00:05:00Z")

    @Test
    fun normalCompletionClosesOnlyPendingReasoningAndRetainsPartialContent() {
        val metadata = JsonObject(mapOf("signature" to JsonPrimitive("opaque provider value")))
        val completed = UIMessagePart.Reasoning("already done", startedAt, startedAt, metadata)
        val pending = UIMessagePart.Reasoning("partial thought", startedAt, null, metadata)
        val text = UIMessagePart.Text("partial answer")
        val tool = pendingTool()
        val original = assistant(listOf(completed, pending, text, tool))

        val updated = original.finalizeProactiveGeneration(endedAt)

        assertEquals(original.id, updated.id)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(endedAt.toLocalDateTime(TimeZone.currentSystemDefault()), updated.finishedAt)
        assertSame(completed, updated.parts[0])
        val finalReasoning = updated.parts[1] as UIMessagePart.Reasoning
        assertEquals(pending.reasoning, finalReasoning.reasoning)
        assertEquals(pending.createdAt, finalReasoning.createdAt)
        assertEquals(endedAt, finalReasoning.finishedAt)
        assertSame(metadata, finalReasoning.metadata)
        assertNull(finalReasoning.proactiveInterruptionReason())
        assertSame(text, updated.parts[2])
        assertSame(tool, updated.parts[3])
        assertNull(pending.finishedAt)
    }

    @Test
    fun interruptionPreservesProviderMetadataWithoutChangingEarlierCompletedReasoning() {
        val metadata = JsonObject(mapOf("provider" to JsonObject(mapOf("id" to JsonPrimitive("keep")))))
        val completed = UIMessagePart.Reasoning("old thought", startedAt, startedAt, metadata)
        val pending = UIMessagePart.Reasoning("unsent continuation", startedAt, null, metadata)

        val updated = assistant(listOf(completed, pending)).finalizeProactiveGeneration(endedAt, "连接已中断")

        assertSame(completed, updated.parts[0])
        val interrupted = updated.parts[1] as UIMessagePart.Reasoning
        assertEquals("连接已中断", interrupted.proactiveInterruptionReason())
        assertEquals(metadata["provider"], interrupted.metadata?.get("provider"))
        assertEquals(endedAt, interrupted.finishedAt)
        assertEquals(pending.reasoning, interrupted.reasoning)
        assertFalse(metadata.containsKey(PROACTIVE_INTERRUPTION_METADATA_KEY))
        assertNull(completed.proactiveInterruptionReason())
    }

    @Test
    fun finalizationIsIdempotentAndDoesNotMoveRecordedCompletionTimes() {
        val recordedEnd = startedAt.toLocalDateTime(TimeZone.currentSystemDefault())
        val original = assistant(listOf(UIMessagePart.Reasoning("partial", startedAt, null)))
            .copy(finishedAt = recordedEnd)

        val finalized = original.finalizeProactiveGeneration(endedAt, "第一次中断")
        val repeated = finalized.finalizeProactiveGeneration(laterAt, "后来的重复清理")

        assertSame(finalized, repeated)
        assertEquals(recordedEnd, repeated.finishedAt)
        assertEquals(endedAt, (repeated.parts.single() as UIMessagePart.Reasoning).finishedAt)
        assertEquals("第一次中断", (repeated.parts.single() as UIMessagePart.Reasoning).proactiveInterruptionReason())
    }

    @Test
    fun unfinishedToolResultExplicitlyKeepsSideEffectsUnknownAndEscapesTheReason() {
        val reason = "连接\"中断\"\n路径 C:\\notes，不能假设远端操作未执行。"
        val metadata = JsonObject(mapOf("trace" to JsonPrimitive("original")))
        val pending = pendingTool().copy(approvalState = ToolApprovalState.Pending, metadata = metadata)
        val finished = pendingTool().copy(
            toolCallId = "done",
            output = listOf(UIMessagePart.Text("{\"ok\":true}")),
        )

        val updated = assistant(listOf(pending, finished)).finalizeProactiveGeneration(endedAt, reason)

        val interrupted = updated.parts[0] as UIMessagePart.Tool
        val result = Json.parseToJsonElement((interrupted.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("interrupted", result.getValue("status").jsonPrimitive.content)
        assertEquals("unknown", result.getValue("execution_state").jsonPrimitive.content)
        assertEquals(reason, result.getValue("error").jsonPrimitive.content)
        assertFalse(result.containsKey("success"))
        assertTrue(interrupted.isExecuted)
        assertFalse(interrupted.isPending)
        assertFalse(interrupted.canResumeExecution)
        assertEquals(pending.toolCallId, interrupted.toolCallId)
        assertEquals(pending.toolName, interrupted.toolName)
        assertEquals(pending.input, interrupted.input)
        assertEquals(metadata["trace"], interrupted.metadata?.get("trace"))
        assertFalse(metadata.containsKey(PROACTIVE_INTERRUPTION_METADATA_KEY))
        assertSame(finished, updated.parts[1])
        assertSame(updated, updated.finalizeProactiveGeneration(laterAt, "repeated"))
    }

    @Test
    fun blankInterruptionStillHasAnExplicitMarkerAndMalformedMetadataIsIgnored() {
        val updated = assistant(listOf(UIMessagePart.Reasoning("partial", startedAt, null)))
            .finalizeProactiveGeneration(endedAt, " ")
        assertTrue((updated.parts.single() as UIMessagePart.Reasoning).proactiveInterruptionReason()!!.isNotBlank())
        val malformed = UIMessagePart.Reasoning(
            "completed",
            metadata = JsonObject(mapOf(PROACTIVE_INTERRUPTION_METADATA_KEY to JsonObject(emptyMap()))),
        )
        assertNull(malformed.proactiveInterruptionReason())
    }

    @Test
    fun ownedCleanupPreservesNodeIdsOrderBranchesAndANewerActiveReply() {
        val owned = assistant(listOf(UIMessagePart.Reasoning("old stream", startedAt, null)))
        val newer = assistant(listOf(UIMessagePart.Reasoning("new live stream", endedAt, null)))
        val branch = MessageNode(messages = listOf(owned, newer), selectIndex = 1, isFavorite = true)
        val user = UIMessage.user("new question")
        val userNode = MessageNode.of(user)
        val conversation = Conversation(
            assistantId = Uuid.random(),
            title = "Keep this title",
            messageNodes = listOf(branch, userNode),
        )

        val updated = conversation.finalizeOwnedProactiveMessages(setOf(owned.id), laterAt, "后台请求中断")

        assertEquals(conversation.id, updated.id)
        assertEquals(conversation.title, updated.title)
        assertEquals(conversation.createAt, updated.createAt)
        assertEquals(conversation.updateAt, updated.updateAt)
        assertEquals(conversation.messageNodes.map { it.id }, updated.messageNodes.map { it.id })
        assertEquals(1, updated.messageNodes[0].selectIndex)
        assertTrue(updated.messageNodes[0].isFavorite)
        assertSame(newer, updated.messageNodes[0].messages[1])
        assertNull((newer.parts.single() as UIMessagePart.Reasoning).finishedAt)
        assertSame(userNode, updated.messageNodes[1])
        assertEquals("后台请求中断", (updated.messageNodes[0].messages[0].parts.single() as UIMessagePart.Reasoning)
            .proactiveInterruptionReason())
    }

    @Test
    fun missingOrDeletedOwnedMessagesAreNeverAppendedAgain() {
        val active = assistant(listOf(UIMessagePart.Reasoning("new reply", endedAt, null)))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(active)))

        assertSame(conversation, conversation.finalizeOwnedProactiveMessages(emptySet(), laterAt, "stopped"))
        assertSame(conversation, conversation.finalizeOwnedProactiveMessages(setOf(Uuid.random()), laterAt, "stopped"))
        assertSame(active, conversation.messageNodes.single().messages.single())
    }

    @Test
    fun cleanupNeverReclassifiesAUserMessageAsCompletedGeneration() {
        val user = UIMessage.user("user content")
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(user)))

        assertSame(user, user.finalizeProactiveGeneration(endedAt, "stopped"))
        assertSame(conversation, conversation.finalizeOwnedProactiveMessages(setOf(user.id), endedAt, "stopped"))
        assertNull(user.finishedAt)
    }

    @Test
    fun openingAnActivelyGeneratingConversationDoesNotCloseItsReasoning() {
        val message = assistant(listOf(UIMessagePart.Reasoning("live thought", startedAt, null)))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(message)))

        assertSame(conversation, conversation.recoverOrphanedReasoning(laterAt, hasActiveGeneration = true))
        assertNull((message.parts.single() as UIMessagePart.Reasoning).finishedAt)
    }

    @Test
    fun recoveryStopsOrphanedReasoningWithoutTreatingItsPartialContentAsANormalCompletion() {
        val partial = UIMessagePart.Reasoning("preserve this incomplete thought", startedAt, null)
        val message = assistant(listOf(partial, UIMessagePart.Text("partial answer")))
        val completed = assistant(listOf(UIMessagePart.Reasoning("finished thought", startedAt, endedAt)))
            .copy(finishedAt = endedAt.toLocalDateTime(TimeZone.currentSystemDefault()))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(message), MessageNode.of(completed)),
        )

        val recovered = conversation.recoverOrphanedReasoning(laterAt, hasActiveGeneration = false)

        val recoveredMessage = recovered.messageNodes[0].messages.single()
        val interrupted = recoveredMessage.parts[0] as UIMessagePart.Reasoning
        assertEquals(partial.reasoning, interrupted.reasoning)
        assertEquals(partial.createdAt, interrupted.createdAt)
        assertEquals(laterAt, interrupted.finishedAt)
        assertTrue(interrupted.proactiveInterruptionReason()!!.isNotBlank())
        assertSame(message.parts[1], recoveredMessage.parts[1])
        assertSame(completed, recovered.messageNodes[1].messages.single())
        assertSame(recovered, recovered.recoverOrphanedReasoning(laterAt, hasActiveGeneration = false))
    }

    @Test
    fun pendingApprovalWithoutUnfinishedReasoningRemainsActionableAfterRecovery() {
        val pending = pendingTool().copy(approvalState = ToolApprovalState.Pending)
        val message = assistant(listOf(pending)).copy(
            finishedAt = endedAt.toLocalDateTime(TimeZone.currentSystemDefault()),
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(message)))

        assertSame(conversation, conversation.recoverOrphanedReasoning(laterAt, hasActiveGeneration = false))
        assertTrue(pending.isPending)
        assertFalse(pending.isExecuted)
    }

    private fun assistant(parts: List<UIMessagePart>): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts,
        createdAt = startedAt.toLocalDateTime(TimeZone.currentSystemDefault()),
    )

    private fun pendingTool(): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "pending",
        toolName = "ssh_exec_saved",
        input = "{\"command\":\"write diary\"}",
    )
}
