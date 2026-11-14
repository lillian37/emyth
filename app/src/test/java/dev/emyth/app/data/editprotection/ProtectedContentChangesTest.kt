package dev.emyth.app.data.editprotection

import dev.emyth.app.data.model.Assistant
import dev.emyth.app.data.repository.MemoryRepository
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtectedContentChangesTest {
    private val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val assistant = Assistant(id = id, systemPrompt = "original persona")

    @Test
    fun ordinaryModelAndTemperatureEditsDoNotCountAsProtectedChanges() {
        val changed = assistant.copy(temperature = 0.4f, chatModelId = Uuid.random())

        assertTrue(assistantProtectedChanges(listOf(assistant), listOf(changed)).isEmpty())
    }

    @Test
    fun deletingAnAssistantIsStillARemovalOfItsProtectedPersona() {
        val changes = assistantProtectedChanges(listOf(assistant), emptyList())

        assertEquals(listOf(id.toString()), changes.map { it.assistantId })
        assertEquals(ProtectedEditScope.ASSISTANT_PROMPT, changes.single().scope)
        assertTrue(changes.single().summary.contains("original persona"))
    }

    @Test
    fun importingTheSameLockedIdentityCannotAvoidThePersonaChangeCheck() {
        val changes = assistantProtectedChanges(emptyList(), listOf(assistant))

        assertEquals(id.toString(), changes.single().assistantId)
        assertEquals(ProtectedEditScope.ASSISTANT_PROMPT, changes.single().scope)
    }

    @Test
    fun memoryAndConversationPromptSwitchesAreProtectedButOtherSettingsRemainIndependent() {
        val current = assistant.copy(
            useGlobalMemory = !assistant.useGlobalMemory,
            allowConversationSystemPrompt = !assistant.allowConversationSystemPrompt,
        )

        assertEquals(
            setOf(ProtectedEditScope.BUILTIN_MEMORY, ProtectedEditScope.CONVERSATION_PROMPT),
            assistantProtectedChanges(listOf(assistant), listOf(current)).map { it.scope }.toSet(),
        )
    }

    @Test
    fun disablingBuiltinMemoryStillRequiresEditingPermission() {
        val changes = assistantProtectedChanges(
            listOf(assistant), listOf(assistant.copy(enableMemory = !assistant.enableMemory)),
        )
        assertEquals(ProtectedEditScope.BUILTIN_MEMORY, changes.single().scope)
    }

    @Test
    fun movingANonemptyConversationPromptRequiresBothOwners() {
        val old = ProtectedConversationPrompt("first", "chat", "persona")
        val next = old.copy(assistantId = "second")

        assertEquals(setOf("first", "second"), conversationProtectedChanges(old, next)
            .map { it.assistantId }.toSet())
        assertTrue(conversationProtectedChanges(old, next).all { it.conversationId == "chat" })
    }

    @Test
    fun creatingAndClearingConversationPromptsAreBothEdits() {
        val prompt = ProtectedConversationPrompt("assistant", "chat", "custom persona")

        assertEquals("assistant", conversationProtectedChanges(null, prompt).single().assistantId)
        assertEquals("assistant", conversationProtectedChanges(prompt, null).single().assistantId)
    }

    @Test
    fun routineConversationSavesAndEmptyPromptNormalizationDoNotWakeTheAssistant() {
        val prompt = ProtectedConversationPrompt("assistant", "chat", null)

        assertTrue(conversationProtectedChanges(prompt, prompt.copy(prompt = "")).isEmpty())
        assertTrue(conversationProtectedChanges(prompt, prompt).isEmpty())
        assertTrue(conversationProtectedChanges(null, prompt).isEmpty())
    }

    @Test
    fun boundedSummaryIncludesALateChangedRegionRatherThanOnlyAnIdenticalPrefix() {
        val prefix = "unchanged ".repeat(300)
        val summary = protectedTextDifference("persona", prefix + "OLD VALUE", prefix + "NEW VALUE")

        assertTrue(summary.contains("OLD VALUE"))
        assertTrue(summary.contains("NEW VALUE"))
        assertTrue(summary.length < 850)
        assertTrue(protectedTextDifference("memory", "x".repeat(10_000), "y".repeat(10_000)).length < 850)
    }

    @Test
    fun humanGlobalMemoryEditsCheckEveryAssistantUsingThatScope() {
        val first = assistant.copy(useGlobalMemory = true)
        val second = assistant.copy(id = Uuid.random(), useGlobalMemory = true)
        val isolated = assistant.copy(id = Uuid.random(), useGlobalMemory = false)

        assertEquals(
            setOf(first.id.toString(), second.id.toString()),
            builtinMemoryEditTargets(MemoryRepository.GLOBAL_MEMORY_ID, listOf(first, second, isolated)).toSet(),
        )
        assertEquals(listOf(id.toString()), builtinMemoryEditTargets(id.toString(), emptyList()))
    }

    @Test
    fun modelMemoryWritesValidateTheRealActorAndStoredRecordOwner() {
        val actor = assistant.copy(enableMemory = true)
        requireAssistantMemoryOwner(actor, id.toString(), id.toString(), id.toString())
        val globalActor = actor.copy(useGlobalMemory = true)
        requireAssistantMemoryOwner(
            globalActor, id.toString(), MemoryRepository.GLOBAL_MEMORY_ID, MemoryRepository.GLOBAL_MEMORY_ID,
        )

        listOf<() -> Unit>(
            { requireAssistantMemoryOwner(actor, id.toString(), id.toString(), "another-assistant") },
            { requireAssistantMemoryOwner(actor.copy(enableMemory = false), id.toString(), id.toString()) },
            { requireAssistantMemoryOwner(globalActor, id.toString(), id.toString()) },
            { requireAssistantMemoryOwner(null, id.toString(), id.toString()) },
        ).forEach { attempt ->
            try {
                attempt()
                fail("An invalid memory owner or scope must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: neither a guessed memory id nor a stale configuration authorizes a write.
            }
        }
    }
}
