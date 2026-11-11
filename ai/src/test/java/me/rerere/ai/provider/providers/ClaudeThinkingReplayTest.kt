package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.ui.CLAUDE_REASONING_BLOCK_ID
import me.rerere.ai.ui.CLAUDE_REDACTED_DATA
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replay the actual parser -> chunk merger -> serialized history -> Claude request path. */
class ClaudeThinkingReplayTest {
    private val provider = ClaudeProvider(OkHttpClient())

    private fun blocks(value: String): JsonArray = json.parseToJsonElement(value).jsonArray

    private fun parse(content: JsonArray): UIMessage {
        val method = ClaudeProvider::class.java.getDeclaredMethod("parseMessage", JsonArray::class.java)
        method.isAccessible = true
        return method.invoke(provider, content) as UIMessage
    }

    private fun append(history: List<UIMessage>, message: UIMessage, stream: Boolean = true): List<UIMessage> =
        history.handleMessageChunk(MessageChunk(
            id = "test-response",
            model = "claude-test",
            choices = listOf(UIMessageChoice(
                index = 0,
                delta = message.takeIf { stream },
                message = message.takeUnless { stream },
                finishReason = null,
            )),
        ))

    private fun stored(message: UIMessage): UIMessage =
        json.decodeFromString(UIMessage.serializer(), json.encodeToString(UIMessage.serializer(), message))

    private fun request(message: UIMessage): JsonArray {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages", List::class.java, Boolean::class.javaPrimitiveType, ClaudePromptCacheTtl::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            provider, listOf(UIMessage.user("first"), stored(message), UIMessage.user("second")),
            false, ClaudePromptCacheTtl.FIVE_MINUTES,
        ) as JsonArray
    }

    private fun assistantBlocks(message: UIMessage): List<JsonObject> = request(message)
        .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        .flatMap { it.jsonObject.getValue("content").jsonArray.map { block -> block.jsonObject } }

    private fun stream(vararg events: String): UIMessage {
        val parser = ClaudeStreamMessageParser(::parse)
        var history = listOf(UIMessage.user("first"))
        events.forEach { event ->
            history = append(history, parser.parse(json.parseToJsonElement(event).jsonObject))
        }
        return stored(history.last())
    }

    @Test
    fun completeAdjacentThinkingBlocksKeepTheirOwnTextAndSignatures() {
        val original = blocks("""[
            {"type":"thinking","thinking":"Thought A","signature":"sig-A"},
            {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
            {"type":"text","text":"answer"}
        ]""")
        val message = append(listOf(UIMessage.user("first")), parse(original), stream = false).last()

        assertEquals(original.toList(), assistantBlocks(message))
        assertEquals(2, message.parts.filterIsInstance<UIMessagePart.Reasoning>().size)
    }

    @Test
    fun anEmptyThinkingStartDoesNotIntroduceAVisiblePlaceholder() {
        val parser = ClaudeStreamMessageParser(::parse)
        val message = parser.parse(json.parseToJsonElement(
            """{"index":0,"content_block":{"type":"thinking","thinking":""}}""",
        ).jsonObject)

        assertTrue(message.parts.isEmpty())
    }

    @Test
    fun streamedSignatureFragmentsAccumulateWithinEachThinkingBlock() {
        val message = stream(
            """{"index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"index":0,"delta":{"type":"thinking_delta","thinking":"Thought "}}""",
            """{"index":0,"delta":{"type":"thinking_delta","thinking":"A"}}""",
            """{"index":0,"delta":{"type":"signature_delta","signature":"sig-"}}""",
            """{"index":0,"delta":{"type":"signature_delta","signature":"A"}}""",
            """{"index":0}""",
            """{"index":1,"content_block":{"type":"thinking","thinking":"Thought B"}}""",
            """{"index":1,"delta":{"type":"signature_delta","signature":"sig-B"}}""",
            """{"index":2,"content_block":{"type":"text","text":"answer"}}""",
        )

        assertEquals(blocks("""[
            {"type":"thinking","thinking":"Thought A","signature":"sig-A"},
            {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
            {"type":"text","text":"answer"}
        ]""").toList(), assistantBlocks(message))
    }

    @Test
    fun aNewResponseCannotMergeWithThePreviousResponsesSameBlockIndex() {
        var history = listOf(UIMessage.user("first"))
        listOf("A", "B").forEach { value ->
            val parser = ClaudeStreamMessageParser(::parse)
            val payload = json.parseToJsonElement(
                """{"index":0,"content_block":{"type":"thinking","thinking":"$value","signature":"sig-$value"}}""",
            ).jsonObject
            history = append(history, parser.parse(payload))
        }

        assertEquals(blocks("""[
            {"type":"thinking","thinking":"A","signature":"sig-A"},
            {"type":"thinking","thinking":"B","signature":"sig-B"}
        ]""").toList(), assistantBlocks(history.last()))
    }

    @Test
    fun nonStreamingRedactedThinkingAloneSurvivesStorageAndUploadFiltering() {
        val original = blocks("""[{"type":"redacted_thinking","data":"opaque-data"}]""")
        val message = append(listOf(UIMessage.user("first")), parse(original), stream = false).last()

        assertTrue(stored(message).isValidToUpload())
        assertEquals(original.toList(), assistantBlocks(message))
    }

    @Test
    fun streamedRedactedThinkingKeepsItsEncryptedDataAndBlockOrder() {
        val message = stream(
            """{"index":0,"content_block":{"type":"redacted_thinking","data":"opaque-A"}}""",
            """{"index":1,"content_block":{"type":"redacted_thinking","data":"opaque-B"}}""",
            """{"index":2,"content_block":{"type":"text","text":"answer"}}""",
        )

        assertEquals(blocks("""[
            {"type":"redacted_thinking","data":"opaque-A"},
            {"type":"redacted_thinking","data":"opaque-B"},
            {"type":"text","text":"answer"}
        ]""").toList(), assistantBlocks(message))
        assertTrue(message.parts.filterIsInstance<UIMessagePart.Reasoning>().all { it.reasoning.isEmpty() })
    }

    @Test
    fun legacySignatureOnlyMetadataIsStillUploadedWithoutLocalBookkeeping() {
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "",
            metadata = buildJsonObject { put("signature", "legacy-sig") },
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning))

        assertTrue(message.isValidToUpload())
        assertEquals(blocks("""[
            {"type":"thinking","thinking":"","signature":"legacy-sig"}
        ]""").toList(), assistantBlocks(message))
    }

    @Test
    fun emptyGenericReasoningStillDoesNotMakeAMessageUploadable() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("")))

        assertFalse(message.isValidToUpload())
    }

    @Test
    fun ordinaryProvidersKeepTheirExistingReasoningDeltaMergeBehavior() {
        var history = listOf(UIMessage.user("first"))
        listOf("Thought ", "continues").forEach { text ->
            history = append(history, UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(text)),
            ))
        }

        assertEquals(listOf("Thought continues"), history.last().parts
            .filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning })
    }

    @Test
    fun internalBlockMarkersAndOtherMetadataAreNeverSentToClaude() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("Thought", metadata = buildJsonObject {
                put(CLAUDE_REASONING_BLOCK_ID, "local-id")
                put("signature", "sig")
                put("private_test_marker", "local-only")
            })),
        )

        assertEquals(blocks("""[{"type":"thinking","thinking":"Thought","signature":"sig"}]""")
            .toList(), assistantBlocks(message))
        assertFalse(request(message).toString().contains("local-only"))
        assertFalse(request(message).toString().contains(CLAUDE_REASONING_BLOCK_ID))
    }

    @Test
    fun thinkingToolUseAndToolResultKeepTheirProtocolBoundaries() {
        val initial = blocks("""[
            {"type":"thinking","thinking":"Before tool","signature":"sig-before"},
            {"type":"tool_use","id":"call-1","name":"lookup","input":{"query":"value"}}
        ]""")
        var message = append(listOf(UIMessage.user("first")), parse(initial), stream = false).last()
        message = message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(output = listOf(UIMessagePart.Text("result"))) else part
        })
        message = (message + MessageChunk(
            id = "follow-up", model = "claude-test",
            choices = listOf(UIMessageChoice(0, null, parse(blocks("""[
                {"type":"redacted_thinking","data":"opaque-after"},
                {"type":"text","text":"final answer"}
            ]""")), "end_turn")),
        ))
        val messages = request(message).map { it.jsonObject }

        assertEquals(listOf("user", "assistant", "user", "assistant", "user"),
            messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(initial, messages[1].getValue("content"))
        assertEquals("call-1", messages[2].getValue("content").jsonArray.single()
            .jsonObject.getValue("tool_use_id").jsonPrimitive.content)
        assertEquals(blocks("""[
            {"type":"redacted_thinking","data":"opaque-after"},
            {"type":"text","text":"final answer"}
        ]"""), messages[3].getValue("content"))
    }

    @Test
    fun streamingToolInputFragmentsStillBuildTheOriginalToolCall() {
        val message = stream(
            """{"index":0,"content_block":{"type":"thinking","thinking":"Search","signature":"sig"}}""",
            """{"index":1,"content_block":{"type":"tool_use","id":"call-1","name":"lookup","input":{}}}""",
            """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}""",
            """{"index":1,"delta":{"type":"input_json_delta","partial_json":"\"value\"}"}}""",
        )

        val tool = message.getTools().single()
        assertEquals("call-1", tool.toolCallId)
        assertEquals("lookup", tool.toolName)
        assertEquals(json.parseToJsonElement("""{"query":"value"}"""), tool.inputAsJson())
        val thinking = message.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("sig", thinking.metadata?.get("signature")?.jsonPrimitive?.content)
        assertFalse(thinking.metadata?.containsKey(CLAUDE_REDACTED_DATA) == true)
    }
}
