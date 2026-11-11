package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.CLAUDE_REASONING_BLOCK_ID
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

/** One parser per response: Anthropic delta indexes identify blocks only within that response. */
internal class ClaudeStreamMessageParser(private val parseContent: (JsonArray) -> UIMessage) {
    private data class ThinkingBlock(val id: String, val signature: String = "")

    private val thinkingBlocks = mutableMapOf<Int, ThinkingBlock>()

    fun parse(payload: JsonObject): UIMessage {
        val start = payload["content_block"]?.jsonObjectOrNull
        val delta = payload["delta"]?.jsonObjectOrNull
        val index = payload["index"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val kind = (start ?: delta)?.get("type")?.jsonPrimitiveOrNull?.contentOrNull
        if (start != null && kind in setOf("thinking", "redacted_thinking")) {
            thinkingBlocks[index] = ThinkingBlock(
                id = Uuid.random().toString(),
                signature = start["signature"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            )
        }
        if (kind in setOf("thinking_delta", "signature_delta")) {
            val block = thinkingBlocks.getOrPut(index) { ThinkingBlock(Uuid.random().toString()) }
            if (kind == "signature_delta") {
                thinkingBlocks[index] = block.copy(
                    signature = block.signature + delta?.get("signature")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
                )
            }
        }
        val message = parseContent(buildJsonArray {
            start?.let { add(it) }
            delta?.let { add(it) }
        })
        val block = thinkingBlocks[index] ?: return message
        return message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Reasoning) return@map part
            part.copy(metadata = buildJsonObject {
                part.metadata?.forEach { (key, value) -> put(key, value) }
                put(CLAUDE_REASONING_BLOCK_ID, block.id)
                // Emit the accumulated signature so the normal metadata replacement remains correct.
                if (block.signature.isNotEmpty()) put("signature", block.signature)
            })
        })
    }
}
