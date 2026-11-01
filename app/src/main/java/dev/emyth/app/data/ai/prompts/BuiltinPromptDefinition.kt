package dev.emyth.app.data.ai.prompts

/** An editable instruction block; runtime data and feature switches are kept separately. */
data class BuiltinPromptDefinition(
    val id: String,
    val title: String,
    val group: String,
    val description: String,
    val defaultText: String,
    val variables: Set<String> = emptySet(),
)

val BUILTIN_PROMPTS: List<BuiltinPromptDefinition>
    get() = ChatBuiltinPrompts.entries + ProactiveBuiltinPrompts.entries
