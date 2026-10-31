package dev.emyth.app.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WakePromptTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
    private val due = Instant.parse("2026-10-31T12:30:00Z").toEpochMilli()
    private fun daily(id: String = "daily") = ScheduledWake(
        id = id,
        triggerAtMillis = due,
        note = "AI 自己设置的提醒原因",
        repeat = WakeRepeat.DAILY,
        time = "12:30",
        lastTriggeredAtMillis = now - 86_400_000,
        lastTriggeredDate = "2026-10-30",
    )

    @Test
    fun `old wake records keep the original default instruction`() {
        val wake = Json.decodeFromString<ScheduledWake>(
            """{"id":"old","triggerAtMillis":1893456000000,"note":"AI note"}"""
        )
        assertNull(wake.prompt)
        assertEquals(DEFAULT_SCHEDULED_WAKE_PROMPT, wake.effectivePrompt)
        assertEquals("AI note", wake.note)
    }

    @Test
    fun `the default text is unchanged for existing schedules and legacy deliveries`() {
        val previousInstruction =
            "这是你之前主动设置的定时唤醒。请务必根据唤醒备注，主动给用户发一条自然、关心的消息。" +
                "不要回复 [PASS]，你设这个唤醒就是为了在这个时刻找用户。"
        assertEquals(previousInstruction, scheduledWakePrompt(null))
        assertEquals(previousInstruction, scheduledWakePrompt(" \n"))
    }

    @Test
    fun `editing a prompt preserves the AI note and all scheduling state`() {
        val original = daily()
        val edited = WakeSetting(listOf(original)).withWakePrompt(original.id, "自然地来找我聊天", now)
            .scheduledWakes.single()
        assertEquals("自然地来找我聊天", edited.effectivePrompt)
        assertEquals(original.id, edited.id)
        assertEquals(original.note, edited.note)
        assertEquals(original.triggerAtMillis, edited.triggerAtMillis)
        assertEquals(original.repeat, edited.repeat)
        assertEquals(original.time, edited.time)
        assertEquals(original.lastTriggeredAtMillis, edited.lastTriggeredAtMillis)
        assertEquals(original.lastTriggeredDate, edited.lastTriggeredDate)
    }

    @Test
    fun `editing one plan does not affect other plans or create new plans`() {
        val first = daily("first")
        val second = ScheduledWake(id = "second", triggerAtMillis = due, note = "another note")
        val updated = WakeSetting(listOf(first, second)).withWakePrompt("first", "my words", now)
        assertEquals(2, updated.scheduledWakes.size)
        assertEquals(second, updated.scheduledWakes[1])
    }

    @Test
    fun `saving an editor opened before AI cancellation cannot recreate the plan`() {
        val current = WakeSetting()
        assertEquals(current, current.withWakePrompt("cancelled-id", "unsaved draft", now))
    }

    @Test
    fun `completed one-time plans cannot be revived through prompt editing`() {
        val finished = ScheduledWake(id = "finished", triggerAtMillis = due, lastTriggeredAtMillis = now)
        val current = WakeSetting(listOf(finished))
        assertEquals(current, current.withWakePrompt(finished.id, "new text", now))
    }

    @Test
    fun `past one-time plans cannot be revived through prompt editing`() {
        val past = ScheduledWake(id = "past", triggerAtMillis = now - 1)
        val current = WakeSetting(listOf(past))
        assertEquals(current, current.withWakePrompt(past.id, "new text", now))
    }

    @Test
    fun `future one-time prompts can be edited without changing their timestamp`() {
        val original = ScheduledWake(id = "once", triggerAtMillis = due, note = "one-time reason")
        val edited = WakeSetting(listOf(original)).withWakePrompt(original.id, "gentle reminder", now)
            .scheduledWakes.single()
        assertEquals(WakeRepeat.ONCE, edited.repeat)
        assertEquals(due, edited.triggerAtMillis)
        assertEquals("gentle reminder", edited.effectivePrompt)
    }

    @Test
    fun `reset removes only the override`() {
        val original = daily().copy(prompt = "custom words")
        val reset = WakeSetting(listOf(original)).withWakePrompt(original.id, null, now).scheduledWakes.single()
        assertNull(reset.prompt)
        assertEquals(DEFAULT_SCHEDULED_WAKE_PROMPT, reset.effectivePrompt)
        assertEquals(original.note, reset.note)
        assertEquals(original.triggerAtMillis, reset.triggerAtMillis)
    }

    @Test
    fun `blank prompt edits are rejected`() {
        val original = WakeSetting(listOf(daily()))
        assertTrue(runCatching { original.withWakePrompt("daily", " \t\n", now) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a custom prompt survives persistence and daily rearming`() {
        val original = daily().copy(prompt = "第一行\n第二行 💤")
        val restored = Json.decodeFromString<ScheduledWake>(Json.encodeToString(original))
        val next = requireNotNull(restored.afterAlarm(due, due + 1_000, ZoneId.of("UTC")))
        assertEquals(original.prompt, next.prompt)
        assertEquals(original.effectivePrompt, next.effectivePrompt)
        assertEquals(original.note, next.note)
        assertEquals(Instant.parse("2026-11-01T12:30:00Z").toEpochMilli(), next.triggerAtMillis)
    }
}
