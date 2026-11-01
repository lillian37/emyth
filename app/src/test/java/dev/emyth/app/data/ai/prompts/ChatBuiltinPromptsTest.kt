package dev.emyth.app.data.ai.prompts

import dev.emyth.app.data.datastore.BuiltinPromptSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBuiltinPromptsTest {
    @Test
    fun `default plugin overview preserves the previous instructions and plugin names`() {
        val names = "日记、相册"
        val previous = "你当前装载了以下插件提供的工具（完整工具列表和参数见 tools 定义）：$names。" +
            "不要只在用户明确点名某个工具时才使用——只要对话场景与某个工具的用途相关，就应该主动考虑调用它，而不是被动等待用户指示。" +
            "部分工具绑定的是持续性的人设/系统状态（例如经营、社交、记录类），更需要你自己记得在合适的时机调用，而不是等用户提醒。"

        assertEquals(
            previous,
            BuiltinPromptSetting().text(ChatBuiltinPrompts.pluginOverview, mapOf("plugin_names" to names)),
        )
    }

    @Test
    fun `custom plugin overview replaces the original guidance`() {
        val setting = BuiltinPromptSetting(
            overrides = mapOf(ChatBuiltinPrompts.pluginOverview.id to "已加载：{{plugin_names}}。"),
        )

        assertEquals(
            "已加载：日记。",
            setting.text(ChatBuiltinPrompts.pluginOverview, mapOf("plugin_names" to "日记")),
        )
    }

    @Test
    fun `each editable chat instruction can be explicitly cleared`() {
        val setting = BuiltinPromptSetting(
            overrides = ChatBuiltinPrompts.entries.associate { it.id to "" },
        )

        ChatBuiltinPrompts.entries.forEach { definition ->
            assertEquals("Clearing ${definition.id}", "", setting.text(definition))
        }
    }
}
