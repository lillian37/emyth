package dev.emyth.app.data.ai.prompts

import dev.emyth.app.data.datastore.BuiltinPromptSetting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveBuiltinPromptsTest {
    // Frozen from source 67fd584, before these blocks became editable.
    private val legacyContextRules = """
        请根据以上上下文，以自然、关心、有趣的方式主动给用户发一条消息。

        重要规则：
        - 绝对不要复述上一轮的对话内容，要发新的话题或新的关心
        - 如果上一轮已经说过类似的话，这次换一个完全不同的角度
        - 不要提及你是在定时发消息，要像自然想起对方一样
        - 绝对不要提及任何数据来源、工具使用、传感器数据、位置服务、应用使用统计等技术细节
        - 不要说"根据xxx"、"我注意到xxx数据"之类暴露信息来源的话
        - 直接以朋友聊天的语气开口，就像你突然想到了什么想跟对方说
        - 不要使用任何XML标签、思考标记或特殊格式，只输出纯文本的消息内容
        - 这是主动发消息：上一轮对话已经彻底结束，不要复盘上一轮，不要纠结该不该说话
        - 你唯一要做的是：先调用查岗工具（屏幕使用时长、今日通知、当前前台应用），看用户此刻在干什么，然后据此主动发一条全新的、自然的消息
        - 不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话
    """.trimIndent()

    private val legacyTimerTriggerRules = """
        ## 主动消息触发（定时触发）
        距离用户上次回复已过去 37 分钟。
        这是定时触发的主动消息，不是设备事件触发。
        上一轮对话已经彻底结束，不要复盘上一轮，不要纠结该不该说话。
        直接查岗看用户在干什么，然后主动发一条全新的、自然的消息。
        绝对不要复述上一轮的对话内容，要发新的话题或新的关心。
        不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话。
        如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。
        [JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。
    """.trimIndent()

    private val legacyDeviceTriggerRules = """
        ## ⚠️ 当前触发原因：用户手机动向（设备事件触发）
        你是因为检测到用户的手机操作动向（切换应用/亮屏锁屏/回桌面）而被触发的。
        请特别注意：这是设备事件触发，不是定时主动消息。根据用户的手机操作动向来决定是否发消息。
        绝对不要复述上一轮的对话内容，要发新的话题或新的关心。
        请根据用户的动向，自然地决定是否主动发一条消息。距离用户上次回复已过去 37 分钟。
        如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。
        [JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。
    """.trimIndent()

    private val legacyDeviceContextRules = """
        请根据以上用户动向，以自然、关心、有趣的方式决定是否主动给用户发一条消息。
        如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。
        重要规则：
        - 不要提及你是在因为设备事件发消息，要像自然想起对方一样
        - 绝对不要提及任何数据来源、工具使用、传感器数据、应用使用统计等技术细节
        - 不要说"根据xxx"、"我注意到xxx数据"之类暴露信息来源的话
        - 直接以朋友聊天的语气开口，就像你突然想到了什么想跟对方说
        - 不要使用任何XML标签、思考标记或特殊格式，只输出纯文本的消息内容
        - 不要调用任何工具或函数，只输出纯文本回复
        - 不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话
    """.trimIndent()

    @Test
    fun `defaults preserve every legacy instruction block`() {
        val setting = BuiltinPromptSetting()
        val values = mapOf("idle_minutes" to "37", "note" to "提醒我喝水")
        val expected = listOf(
            ProactiveBuiltinPrompts.contextRules to legacyContextRules,
            ProactiveBuiltinPrompts.timerTriggerRules to legacyTimerTriggerRules,
            ProactiveBuiltinPrompts.deviceTriggerRules to legacyDeviceTriggerRules,
            ProactiveBuiltinPrompts.deviceContextRules to legacyDeviceContextRules,
            ProactiveBuiltinPrompts.timerUser to
                "请根据以上上下文决定是否发消息。没什么好说的就回复 [PASS] 即可，不要强行找话题。",
            ProactiveBuiltinPrompts.deviceUser to
                "请根据以上用户动向决定是否发消息。没什么好说的就回复 [PASS]。",
            ProactiveBuiltinPrompts.scheduledNote to
                "[计划唤醒] 这是你之前设置的定时唤醒，唤醒备注：提醒我喝水。请据此决定是否主动给用户发消息，或调用工具。",
        )
        expected.forEach { (definition, text) ->
            assertEquals(definition.id, text, setting.text(definition, values))
        }
    }

    @Test
    fun `default context assembly keeps its original separators`() {
        val actual = buildString {
            append("动态上下文\n")
            appendProactivePrompt(
                BuiltinPromptSetting().text(ProactiveBuiltinPrompts.contextRules),
                blankLinesBefore = 1,
            )
        }
        assertEquals("动态上下文\n\n$legacyContextRules\n", actual)
    }

    @Test
    fun `device event data and default rules keep their original order`() {
        val data = "[设备事件触发]\n以下是过去30秒内用户的手机操作动向：\n  - [12:34:56] 亮屏\n"
        val actual = BuiltinPromptSetting().proactiveDeviceContext(data)
        assertEquals("$data\n$legacyDeviceContextRules\n", actual)
    }

    @Test
    fun `clearing system blocks retains dynamic data without default rules`() {
        val definitions = listOf(
            ProactiveBuiltinPrompts.contextRules,
            ProactiveBuiltinPrompts.timerTriggerRules,
            ProactiveBuiltinPrompts.deviceTriggerRules,
            ProactiveBuiltinPrompts.deviceContextRules,
        )
        val setting = BuiltinPromptSetting(definitions.associate { it.id to "" })
        val actual = buildString {
            append("人设和动态数据\n")
            definitions.forEach { appendProactivePrompt(setting.text(it), blankLinesBefore = 2) }
        }
        assertEquals("人设和动态数据\n", actual)
        assertEquals("动态设备数据\n", setting.proactiveDeviceContext("动态设备数据\n"))
    }

    @Test
    fun `custom user messages replace the defaults for their own trigger`() {
        val setting = BuiltinPromptSetting(mapOf(
            ProactiveBuiltinPrompts.timerUser.id to "定时事件，请按自己的想法处理。",
            ProactiveBuiltinPrompts.deviceUser.id to "设备事件，仅供参考。",
        ))
        assertEquals("定时事件，请按自己的想法处理。", setting.proactiveUserText(false))
        assertEquals("设备事件，仅供参考。", setting.proactiveUserText(true))
    }

    @Test
    fun `clearing user messages leaves only neutral event markers`() {
        val setting = BuiltinPromptSetting(mapOf(
            ProactiveBuiltinPrompts.timerUser.id to "",
            ProactiveBuiltinPrompts.deviceUser.id to "",
        ))
        assertEquals("[主动消息触发]", setting.proactiveUserText(false))
        assertEquals("[设备事件触发]", setting.proactiveUserText(true))
    }

    @Test
    fun `custom trigger templates receive idle minutes without old instructions`() {
        val definitions = listOf(
            ProactiveBuiltinPrompts.timerTriggerRules,
            ProactiveBuiltinPrompts.deviceTriggerRules,
        )
        val setting = BuiltinPromptSetting(definitions.associate { it.id to "已等待 {{idle_minutes}} 分钟。" })
        definitions.forEach {
            assertEquals("已等待 83 分钟。", setting.text(it, mapOf("idle_minutes" to "83")))
        }
    }

    @Test
    fun `scheduled note default preserves the legacy wrapper and original note`() {
        val note = "自己写的备注 {{note}}\n第二行"
        val actual = BuiltinPromptSetting().proactiveScheduledContext("动态上下文\n", note)
        val expected = "动态上下文\n\n[计划唤醒] 这是你之前设置的定时唤醒，唤醒备注：$note。" +
            "请据此决定是否主动给用户发消息，或调用工具。"
        assertEquals(expected, actual)
        assertFalse(actual.contains("[当前计划唤醒备注]"))
    }

    @Test
    fun `empty notes add nothing even when an instruction override exists`() {
        val defaults = BuiltinPromptSetting()
        val custom = BuiltinPromptSetting(mapOf(ProactiveBuiltinPrompts.scheduledNote.id to "自定义说明"))
        listOf(null, "", " \n").forEach {
            assertEquals("动态上下文\n", defaults.proactiveScheduledContext("动态上下文\n", it))
            assertEquals("动态上下文\n", custom.proactiveScheduledContext("动态上下文\n", it))
        }
    }

    @Test
    fun `clearing the explanation retains the current note as data without restoring instructions`() {
        val cleared = BuiltinPromptSetting(mapOf(ProactiveBuiltinPrompts.scheduledNote.id to ""))
        val actual = cleared.proactiveScheduledContext("动态上下文\n", "新版有效备注")
        assertTrue(actual.startsWith("动态上下文\n\n[当前计划唤醒备注]\n"))
        assertEquals("新版有效备注", dataNote(actual))
        assertFalse(actual.contains("请据此决定"))
        assertFalse(actual.contains("这是你之前设置"))
    }

    @Test
    fun `an explanation without the note variable retains both the custom text and current data`() {
        val setting = BuiltinPromptSetting(mapOf(
            ProactiveBuiltinPrompts.scheduledNote.id to "按自己的想法处理。",
        ))
        val actual = setting.proactiveScheduledContext("数据", "新版任务")
        assertTrue(actual.startsWith("数据\n按自己的想法处理。\n[当前计划唤醒备注]\n"))
        assertEquals("新版任务", dataNote(actual))
        assertFalse(actual.contains("请据此决定"))
    }

    @Test
    fun `JSON data preserves Chinese newlines quotes braces and literal variable text`() {
        val note = "中文第一行\n第二行 \"引号\" 与反斜杠 \\ {{note}} {\"key\":\"value\"}\t😀"
        val setting = BuiltinPromptSetting(mapOf(ProactiveBuiltinPrompts.scheduledNote.id to ""))
        val actual = setting.proactiveScheduledContext("数据", note)
        assertEquals(note, dataNote(actual))
    }

    @Test
    fun `only the exact declared note variable replaces the separate data block`() {
        listOf("已经提到新版备注", "{{ note }}", "{{other}}").forEach { template ->
            val setting = BuiltinPromptSetting(mapOf(ProactiveBuiltinPrompts.scheduledNote.id to template))
            val actual = setting.proactiveScheduledContext("数据", "新版备注")
            assertTrue(actual.contains("\n$template\n[当前计划唤醒备注]\n"))
            assertEquals("新版备注", dataNote(actual))
        }
    }

    @Test
    fun `custom scheduled note replaces its complete fixed explanation`() {
        val setting = BuiltinPromptSetting(mapOf(
            ProactiveBuiltinPrompts.scheduledNote.id to "计划备注：{{note}}",
        ))
        assertEquals("数据\n计划备注：喝水", setting.proactiveScheduledContext("数据", "喝水"))
    }

    private fun dataNote(context: String): String =
        Json.parseToJsonElement(context.substringAfter("[当前计划唤醒备注]\n"))
            .jsonObject.getValue("note").jsonPrimitive.content
}
