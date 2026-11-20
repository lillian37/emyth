package dev.emyth.app.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEditPermissionToolTest {
    @Test
    fun theDeclaredContractHasNoTargetOrDurationArgument() {
        val tool = Backend().tool()
        val schema = tool.parameters() as InputSchema.Obj

        assertEquals(setOf("action", "reason"), schema.properties.keys)
        assertEquals(
            listOf("status", "grant", "revoke"),
            schema.properties.getValue("action").jsonObject.getValue("enum").jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertEquals(listOf("action"), schema.required)
        assertFalse(tool.needsApproval)
    }

    @Test
    fun readingStatusNeverGrantsOrRevokesPermission() = runBlocking {
        val backend = Backend()

        val result = result(backend.tool().execute(arguments("status")))

        assertEquals(listOf(Call("status", "actual-assistant", null, null)), backend.calls)
        assertTrue(result.getValue("success").jsonPrimitive.boolean)
        assertEquals("status", result.getValue("action").jsonPrimitive.content)
    }

    @Test
    fun aGrantUsesTheActualCallerAndOneNormalizedNote() = runBlocking {
        val backend = Backend()

        val result = result(backend.tool().execute(arguments("grant", "  Adjust the preferred name.  ")))

        assertEquals(
            listOf(Call("grant", "actual-assistant", "actual-conversation", "Adjust the preferred name.")),
            backend.calls,
        )
        assertEquals("grant", result.getValue("action").jsonPrimitive.content)
    }

    @Test
    fun revokingPermissionIsBoundToTheSameActualCaller() = runBlocking {
        val backend = Backend()

        backend.tool().execute(arguments("revoke", "Editing is finished."))

        assertEquals(
            listOf(Call("revoke", "actual-assistant", "actual-conversation", "Editing is finished.")),
            backend.calls,
        )
    }

    @Test
    fun modelArgumentsCannotRetargetTheGrantOrExtendItsDuration() = runBlocking {
        val backend = Backend()
        val tool = backend.tool()
        val forbidden = listOf(
            "assistant_id" to JsonPrimitive("another-assistant"),
            "conversation_id" to JsonPrimitive("another-conversation"),
            "duration" to JsonPrimitive(999_999),
            "enabled" to JsonPrimitive(true),
        )

        forbidden.forEach { (key, value) ->
            val response = result(tool.execute(JsonObject(arguments("grant") + (key to value))))
            assertFalse(response.getValue("success").jsonPrimitive.boolean)
        }

        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun invalidInputCannotReachAnyPermissionCallback() = runBlocking {
        val backend = Backend()
        val tool = backend.tool()
        val invalid = listOf(
            JsonPrimitive("grant"),
            buildJsonObject {},
            arguments("enable"),
            arguments("status", "Do not grant anything."),
            buildJsonObject { put("action", true) },
            buildJsonObject { put("action", "grant"); put("reason", false) },
            arguments("grant", "x".repeat(241)),
        )

        invalid.forEach { input ->
            assertFalse(result(tool.execute(input)).getValue("success").jsonPrimitive.boolean)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun anAnonymousCallerCannotCreateAnAuthorityBearingTool() {
        val backend = Backend()

        assertTrue(runCatching { backend.tool(assistantId = "") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { backend.tool(conversationId = " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun aFailedPermissionWriteCannotBeReportedAsSuccess() = runBlocking {
        val failure = IllegalStateException("permission write failed")
        val tool = createAssistantEditPermissionTool(
            callerAssistantId = "actual-assistant",
            callerConversationId = "actual-conversation",
            onStatus = { buildJsonObject { put("success", true) } },
            onGrant = { _, _, _ -> throw failure },
            onRevoke = { _, _, _ -> buildJsonObject { put("success", true) } },
        )

        assertSame(failure, runCatching { tool.execute(arguments("grant")) }.exceptionOrNull())
    }

    private fun arguments(action: String, reason: String? = null): JsonObject = buildJsonObject {
        put("action", action)
        reason?.let { put("reason", it) }
    }

    private fun result(parts: List<UIMessagePart>): JsonObject =
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private data class Call(
        val action: String,
        val assistantId: String,
        val conversationId: String?,
        val reason: String?,
    )

    private class Backend {
        val calls = mutableListOf<Call>()

        fun tool(assistantId: String = "actual-assistant", conversationId: String = "actual-conversation") =
            createAssistantEditPermissionTool(
                callerAssistantId = assistantId,
                callerConversationId = conversationId,
                onStatus = { id -> response(Call("status", id, null, null)) },
                onGrant = { id, chat, reason -> response(Call("grant", id, chat, reason)) },
                onRevoke = { id, chat, reason -> response(Call("revoke", id, chat, reason)) },
            )

        private fun response(call: Call): JsonObject {
            calls.add(call)
            return buildJsonObject {
                put("success", true)
                put("action", call.action)
            }
        }
    }
}
