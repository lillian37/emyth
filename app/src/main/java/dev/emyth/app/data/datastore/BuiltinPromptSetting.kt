package dev.emyth.app.data.datastore

import dev.emyth.app.data.ai.prompts.BuiltinPromptDefinition
import kotlinx.serialization.Serializable

@Serializable
data class BuiltinPromptSetting(
    val overrides: Map<String, String> = emptyMap(),
) {
    /** Missing means default; an explicitly empty value means no instruction block. */
    fun text(definition: BuiltinPromptDefinition, values: Map<String, String> = emptyMap()): String {
        val template = overrides[definition.id] ?: definition.defaultText
        return PROMPT_VARIABLE.replace(template) { match ->
            val name = match.groupValues[1]
            if (name in definition.variables) values[name] ?: match.value else match.value
        }
    }

    fun withOverride(id: String, text: String?): BuiltinPromptSetting {
        require(id.isNotBlank()) { "提示词编号不能为空" }
        return copy(overrides = overrides.toMutableMap().apply {
            if (text == null) remove(id) else put(id, text)
        })
    }
}

private val PROMPT_VARIABLE = Regex("\\{\\{([a-z][a-z0-9_]*)\\}\\}")
