package dev.emyth.app.data.datastore

import dev.emyth.app.data.ai.prompts.BUILTIN_PROMPTS
import dev.emyth.app.data.ai.prompts.BuiltinPromptDefinition
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinPromptSettingTest {
    private val definition = BuiltinPromptDefinition(
        id = "test.rules",
        title = "Rules",
        group = "Test",
        description = "Test template",
        defaultText = "Default for {{name}}",
        variables = setOf("name"),
    )

    @Test
    fun `old settings without overrides use their original default`() {
        val settings = Json.decodeFromString<BuiltinPromptSetting>("{}")
        assertEquals("Default for Alice", settings.text(definition, mapOf("name" to "Alice")))
    }

    @Test
    fun `empty override removes the instruction instead of restoring it`() {
        val settings = BuiltinPromptSetting().withOverride(definition.id, "")
        assertTrue(settings.overrides.containsKey(definition.id))
        assertEquals("", settings.text(definition, mapOf("name" to "Alice")))
    }

    @Test
    fun `restoring one default preserves all other edits and future keys`() {
        val settings = BuiltinPromptSetting(mapOf("other" to "custom", "future.key" to "keep"))
            .withOverride(definition.id, "changed")
            .withOverride(definition.id, null)
        assertFalse(settings.overrides.containsKey(definition.id))
        assertEquals("Default for Alice", settings.text(definition, mapOf("name" to "Alice")))
        assertEquals("custom", settings.overrides["other"])
        assertEquals("keep", settings.overrides["future.key"])
    }

    @Test
    fun `overrides survive persistence including blank overrides and unicode`() {
        val original = BuiltinPromptSetting(mapOf(definition.id to "第一行\n第二行 🤔", "off" to ""))
        val restored = Json.decodeFromString<BuiltinPromptSetting>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(original.overrides[definition.id], restored.text(definition))
        assertTrue(restored.overrides.containsKey("off"))
    }

    @Test
    fun `runtime data is inserted literally and is not recursively interpreted`() {
        val value = "{{name}} and {{other}} with $ and \\"
        assertEquals("Default for $value", BuiltinPromptSetting().text(definition, mapOf("name" to value)))
    }

    @Test
    fun `unrecognized placeholders are kept and undeclared values cannot fill them`() {
        val settings = BuiltinPromptSetting().withOverride(definition.id, "{{name}} {{other}}")
        assertEquals("Alice {{other}}", settings.text(definition, mapOf("name" to "Alice", "other" to "hidden")))
    }

    @Test
    fun `sequential field edits keep earlier edits without changing the defaults`() {
        val original = BuiltinPromptSetting()
        val edited = original.withOverride("first", "one").withOverride("second", "two")
        assertTrue(original.overrides.isEmpty())
        assertEquals(mapOf("first" to "one", "second" to "two"), edited.overrides)
        assertEquals("Default for {{name}}", definition.defaultText)
    }

    @Test
    fun `catalog ids and editable metadata stay unambiguous`() {
        val catalog = BUILTIN_PROMPTS
        assertEquals(catalog.size, catalog.map { it.id }.toSet().size)
        assertTrue(catalog.all { it.id.isNotBlank() && it.title.isNotBlank() && it.description.isNotBlank() })
    }
}
