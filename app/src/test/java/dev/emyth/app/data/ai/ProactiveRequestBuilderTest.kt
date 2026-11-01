package dev.emyth.app.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import dev.emyth.app.data.ai.transformers.applyTimeReminder
import dev.emyth.app.data.ai.transformers.transformMessages
import dev.emyth.app.data.ai.prompts.proactiveScheduledContext
import dev.emyth.app.data.datastore.BuiltinPromptSetting
import dev.emyth.app.data.datastore.ScheduledWake
import dev.emyth.app.data.datastore.WakeSetting
import dev.emyth.app.data.datastore.effectivePrompt
import dev.emyth.app.data.datastore.withWakeContent
import dev.emyth.app.data.model.Assistant
import dev.emyth.app.data.model.InjectionPosition
import dev.emyth.app.data.model.Lorebook
import dev.emyth.app.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveRequestBuilderTest {
    private val updatedPrompt = "新唤醒提示：只整理今天的读书笔记。"
    private val updatedSystem = "当前计划的新备注：整理读书笔记，不执行旧任务。"

    private fun text(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun datedUser(content: String, hour: Int): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(content)),
        createdAt = LocalDateTime(2026, 11, 10, hour, 0),
    )

    @Test
    fun `edited wake data reaches the request even when history mentions the old plan`() = runBlocking {
        val old = ScheduledWake(id = "wake", triggerAtMillis = 10_000, note = "旧备注", prompt = "旧提示词")
        val current = WakeSetting(listOf(old)).withWakeContent(
            id = old.id,
            note = "新备注：整理读书笔记",
            prompt = updatedPrompt,
            nowMillis = 1,
        ).scheduledWakes.single()
        val system = BuiltinPromptSetting().proactiveScheduledContext("当前设备状态", current.note)
        val request = prepareProactiveMessages(
            system,
            listOf(UIMessage.assistant("以前设过旧备注、旧提示词里的任务。")),
            datedUser(current.effectivePrompt, 15),
        ) { applyTimeReminder(it) }

        assertTrue(text(request.first()).contains(current.note))
        assertFalse(text(request.first()).contains(old.note))
        assertTrue(text(request.last()).endsWith(updatedPrompt))
        assertTrue(request.any { text(it).contains("以前设过") })
    }

    @Test
    fun `real time reminder cannot replace the current wake prompt`() = runBlocking {
        val current = datedUser(updatedPrompt, 15)
        // This is the old loss path: the first transformed item is an injected timestamp.
        val oldFirst = applyTimeReminder(listOf(current)).first()
        assertTrue(text(oldFirst).contains("<time_reminder>"))
        assertFalse(text(oldFirst).contains(updatedPrompt))

        val request = prepareProactiveMessages(updatedSystem, emptyList(), current) {
            applyTimeReminder(it)
        }

        assertEquals(MessageRole.SYSTEM, request.first().role)
        assertEquals(updatedSystem, text(request.first()))
        assertEquals(MessageRole.USER, request.last().role)
        assertTrue(text(request.last()).contains("<time_reminder>"))
        assertTrue(text(request.last()).endsWith(updatedPrompt))
    }

    @Test
    fun `real time reminder sees history and keeps the new prompt after the correct gap`() = runBlocking {
        val history = listOf(
            datedUser("上次聊的旧任务", 10),
            UIMessage.assistant("旧任务已经处理完。").copy(createdAt = LocalDateTime(2026, 11, 10, 12, 0)),
        )
        val request = prepareProactiveMessages(updatedSystem, history, datedUser(updatedPrompt, 15)) {
            applyTimeReminder(it)
        }

        assertTrue(text(request.last()).contains("3 h since last message"))
        assertTrue(text(request.last()).endsWith(updatedPrompt))
        assertEquals(4, request.size)
        assertTrue(request.any { text(it).contains("旧任务已经处理完。") })
    }

    @Test
    fun `real top of chat injection and time reminder both retain the current prompt`() = runBlocking {
        val injection = PromptInjection.ModeInjection(
            name = "顶部模式",
            enabled = true,
            position = InjectionPosition.TOP_OF_CHAT,
            content = "模式补充内容，不能代替当前唤醒指令。",
        )
        val assistant = Assistant(modeInjectionIds = setOf(injection.id))
        val request = prepareProactiveMessages(updatedSystem, emptyList(), datedUser(updatedPrompt, 15)) {
            transformMessages(applyTimeReminder(it), assistant, listOf(injection), emptyList())
        }

        assertEquals(updatedSystem, text(request.first()))
        val userText = text(request.last())
        assertEquals(MessageRole.USER, request.last().role)
        assertTrue(userText.contains(injection.content))
        assertTrue(userText.contains("<time_reminder>"))
        assertTrue(userText.endsWith(updatedPrompt))
    }

    @Test
    fun `real system injections modify the existing system without dropping the wake prompt`() = runBlocking {
        listOf(InjectionPosition.BEFORE_SYSTEM_PROMPT, InjectionPosition.AFTER_SYSTEM_PROMPT).forEach { position ->
            val injection = PromptInjection.ModeInjection(
                name = "系统模式",
                enabled = true,
                position = position,
                content = "系统模式补充",
            )
            val assistant = Assistant(modeInjectionIds = setOf(injection.id))
            val request = prepareProactiveMessages(updatedSystem, emptyList(), datedUser(updatedPrompt, 15)) {
                transformMessages(applyTimeReminder(it), assistant, listOf(injection), emptyList())
            }

            assertEquals(1, request.count { it.role == MessageRole.SYSTEM })
            assertTrue(text(request.first()).contains(updatedSystem))
            assertTrue(text(request.first()).contains(injection.content))
            assertEquals(MessageRole.USER, request.last().role)
            assertTrue(text(request.last()).endsWith(updatedPrompt))
        }
    }

    @Test
    fun `real lorebook matching can add context while preserving new note and new prompt`() = runBlocking {
        val entry = PromptInjection.RegexInjection(
            name = "读书条目",
            enabled = true,
            keywords = listOf("读书笔记"),
            position = InjectionPosition.TOP_OF_CHAT,
            content = "世界书补充：使用当前计划的备注。",
        )
        val lorebook = Lorebook(name = "读书世界书", enabled = true, entries = listOf(entry))
        val assistant = Assistant(lorebookIds = setOf(lorebook.id))
        val request = prepareProactiveMessages(updatedSystem, emptyList(), datedUser(updatedPrompt, 15)) {
            transformMessages(applyTimeReminder(it), assistant, emptyList(), listOf(lorebook))
        }

        assertTrue(text(request.first()).contains(updatedSystem))
        assertTrue(text(request.last()).contains(entry.content))
        assertTrue(text(request.last()).endsWith(updatedPrompt))
    }
}
