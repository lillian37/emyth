/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai.prompts

import dev.emyth.app.data.datastore.BuiltinPromptSetting

object ProactiveBuiltinPrompts {
    val contextRules = BuiltinPromptDefinition(
        id = "proactive.context_rules",
        title = "主动消息上下文规则",
        group = "主动消息与设备事件",
        description = "定时主动消息和计划唤醒附加的系统规则；留空不附加。",
        defaultText = """
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
        """.trimIndent(),
    )

    val timerTriggerRules = BuiltinPromptDefinition(
        id = "proactive.timer_trigger_rules",
        title = "定时触发系统说明",
        group = "主动消息与设备事件",
        description = "定时主动消息和计划唤醒的系统说明；{{idle_minutes}} 是距离上次回复的分钟数。",
        defaultText = """
            ## 主动消息触发（定时触发）
            距离用户上次回复已过去 {{idle_minutes}} 分钟。
            这是定时触发的主动消息，不是设备事件触发。
            上一轮对话已经彻底结束，不要复盘上一轮，不要纠结该不该说话。
            直接查岗看用户在干什么，然后主动发一条全新的、自然的消息。
            绝对不要复述上一轮的对话内容，要发新的话题或新的关心。
            不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话。
            如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。
            [JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。
        """.trimIndent(),
        variables = setOf("idle_minutes"),
    )

    val deviceTriggerRules = BuiltinPromptDefinition(
        id = "proactive.device_trigger_rules",
        title = "设备事件系统说明",
        group = "主动消息与设备事件",
        description = "设备事件触发的系统说明；{{idle_minutes}} 是距离上次回复的分钟数。",
        defaultText = """
            ## ⚠️ 当前触发原因：用户手机动向（设备事件触发）
            你是因为检测到用户的手机操作动向（切换应用/亮屏锁屏/回桌面）而被触发的。
            请特别注意：这是设备事件触发，不是定时主动消息。根据用户的手机操作动向来决定是否发消息。
            绝对不要复述上一轮的对话内容，要发新的话题或新的关心。
            请根据用户的动向，自然地决定是否主动发一条消息。距离用户上次回复已过去 {{idle_minutes}} 分钟。
            如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。
            [JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。
        """.trimIndent(),
        variables = setOf("idle_minutes"),
    )

    val deviceContextRules = BuiltinPromptDefinition(
        id = "proactive.device_context_rules",
        title = "设备动向处理规则",
        group = "主动消息与设备事件",
        description = "附在设备动向数据后的系统规则；留空不附加，动态事件数据仍会提供。",
        defaultText = """
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
        """.trimIndent(),
    )

    val timerUser = BuiltinPromptDefinition(
        id = "proactive.timer_user",
        title = "定时主动消息指令",
        group = "主动消息与设备事件",
        description = "普通定时主动消息的合成用户指令；留空时只传递中性的触发标记。",
        defaultText = "请根据以上上下文决定是否发消息。没什么好说的就回复 [PASS] 即可，不要强行找话题。",
    )

    val deviceUser = BuiltinPromptDefinition(
        id = "proactive.device_user",
        title = "设备事件用户指令",
        group = "主动消息与设备事件",
        description = "设备事件触发的合成用户指令；留空时只传递中性的触发标记。",
        defaultText = "请根据以上用户动向决定是否发消息。没什么好说的就回复 [PASS]。",
    )

    val scheduledNote = BuiltinPromptDefinition(
        id = "proactive.scheduled_note",
        title = "计划唤醒备注说明",
        group = "主动消息与设备事件",
        description = "{{note}} 替换当前计划的唤醒备注；留空不附加此段。",
        defaultText = "[计划唤醒] 这是你之前设置的定时唤醒，唤醒备注：{{note}}。请据此决定是否主动给用户发消息，或调用工具。",
        variables = setOf("note"),
    )

    val entries = listOf(
        contextRules,
        timerTriggerRules,
        deviceTriggerRules,
        deviceContextRules,
        timerUser,
        deviceUser,
        scheduledNote,
    )
}

internal fun StringBuilder.appendProactivePrompt(prompt: String, blankLinesBefore: Int = 0) {
    if (prompt.isBlank()) return
    repeat(blankLinesBefore) { appendLine() }
    appendLine(prompt)
}

internal fun BuiltinPromptSetting.proactiveUserText(isFromDeviceEvent: Boolean): String =
    text(if (isFromDeviceEvent) ProactiveBuiltinPrompts.deviceUser else ProactiveBuiltinPrompts.timerUser)
        .ifBlank { if (isFromDeviceEvent) "[设备事件触发]" else "[主动消息触发]" }

internal fun BuiltinPromptSetting.proactiveDeviceContext(data: String): String = buildString {
    append(data)
    appendProactivePrompt(text(ProactiveBuiltinPrompts.deviceContextRules), blankLinesBefore = 1)
}

internal fun BuiltinPromptSetting.proactiveScheduledContext(base: String, note: String?): String {
    if (note.isNullOrBlank()) return base
    val rendered = text(ProactiveBuiltinPrompts.scheduledNote, mapOf("note" to note))
    return if (rendered.isBlank()) base else "$base\n$rendered"
}
