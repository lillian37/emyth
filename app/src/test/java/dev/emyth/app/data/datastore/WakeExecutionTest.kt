package dev.emyth.app.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WakeExecutionTest {
    private val utc = ZoneId.of("UTC")
    private val due = Instant.parse("2026-11-05T00:00:00Z").toEpochMilli()

    private fun setting(wake: ScheduledWake = ScheduledWake(id = "wake", triggerAtMillis = due)) =
        WakeSetting(scheduledWakes = listOf(wake))

    private fun started() = setting(requireNotNull(
        ScheduledWake(id = "wake", triggerAtMillis = due).afterAlarm(due, due + 1_000, utc)
    ))

    @Test
    fun `legacy consumed records remain unknown rather than inferred complete`() {
        val setting = Json.decodeFromString<WakeSetting>(
            """{"scheduledWakes":[{"id":"wake","triggerAtMillis":1000,"lastTriggeredAtMillis":1001}]}"""
        )
        assertNull(setting.scheduledWakes.single().execution)
        assertSame(setting, setting.withWakeExecution(
            "wake", 1000, ScheduledWakeExecutionStatus.COMPLETED, 2000
        ))
        assertSame(setting, setting.withWakeExecution(
            "wake", 1000, ScheduledWakeExecutionStatus.STARTED, 2000
        ))
    }

    @Test
    fun `execution statuses use stable snake case and round trip`() {
        for (status in ScheduledWakeExecutionStatus.values()) {
            val execution = ScheduledWakeExecution(status, due, due + 1_000, "conversation", "safe detail")
            val encoded = Json.encodeToString(execution)
            assertTrue(encoded.contains("\"status\":\"${status.value}\""))
            assertEquals(execution, Json.decodeFromString<ScheduledWakeExecution>(encoded))
        }
        assertEquals("running_tools", ScheduledWakeExecutionStatus.RUNNING_TOOLS.value)
        assertEquals("start_failed", ScheduledWakeExecutionStatus.START_FAILED.value)
    }

    @Test
    fun `alarm consumption starts a fresh daily attempt while preserving next occurrence`() {
        val previousDue = due - 86_400_000
        val wake = ScheduledWake(
            id = "wake", triggerAtMillis = due, note = "note", prompt = "prompt",
            repeat = WakeRepeat.DAILY, time = "00:00",
            lastTriggeredAtMillis = previousDue + 1_000, lastTriggeredDate = "2026-11-04",
            execution = ScheduledWakeExecution(
                ScheduledWakeExecutionStatus.COMPLETED, previousDue, previousDue + 2_000,
                "old conversation", "old detail",
            ),
        )
        val fired = requireNotNull(wake.afterAlarm(due, due + 1_000, utc))
        assertEquals(due + 86_400_000, fired.triggerAtMillis)
        assertEquals(due + 1_000, fired.lastTriggeredAtMillis)
        assertEquals("2026-11-05", fired.lastTriggeredDate)
        assertEquals(wake.note, fired.note)
        assertEquals(wake.prompt, fired.prompt)
        assertEquals(
            ScheduledWakeExecution(ScheduledWakeExecutionStatus.STARTED, due, due + 1_000),
            fired.execution,
        )
        val failed = setting(fired).withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.FAILED, due + 2_000, detail = "request failed"
        ).scheduledWakes.single()
        assertEquals(ScheduledWakeExecutionStatus.FAILED, failed.execution?.status)
        assertEquals(fired.triggerAtMillis, failed.triggerAtMillis)
        assertEquals(fired.lastTriggeredAtMillis, failed.lastTriggeredAtMillis)
        assertFalse(failed.execution?.status == ScheduledWakeExecutionStatus.COMPLETED)
    }

    @Test
    fun `old attempt and late progress cannot overwrite a newer terminal result`() {
        val current = started().withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.COMPLETED, due + 3_000, "conversation"
        )
        assertSame(current, current.withWakeExecution(
            "wake", due - 86_400_000, ScheduledWakeExecutionStatus.FAILED, due + 4_000
        ))
        assertSame(current, current.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.STREAMING, due + 4_000
        ))
        assertSame(current, current.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.INTERRUPTED, due + 4_000
        ))
        val failed = started().withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.FAILED, due + 2_000
        )
        assertSame(failed, failed.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.COMPLETED, due + 3_000
        ))
    }

    @Test
    fun `duplicate dispatch failure does not overwrite an already running attempt`() {
        val started = started()
        assertSame(started, started.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.START_FAILED, due + 2_000
        ))
        val streaming = started.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.STREAMING, due + 3_000
        )
        assertSame(streaming, streaming.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.START_FAILED, due + 4_000
        ))
        val failed = setting().withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.START_FAILED, due + 1_000, detail = "start rejected"
        )
        assertEquals(ScheduledWakeExecutionStatus.START_FAILED, failed.scheduledWakes.single().execution?.status)
        assertEquals(0L, failed.scheduledWakes.single().lastTriggeredAtMillis)
        val updated = failed.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.START_FAILED, due + 2_000, detail = "start rejected again"
        )
        assertEquals("start rejected again", updated.scheduledWakes.single().execution?.detail)
    }

    @Test
    fun `phase changes clear stale detail while retaining the attempt conversation`() {
        val preparing = started().withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.PREPARING, due + 2_000,
            conversationId = "conversation", detail = "preparing tools",
        )
        val samePhase = preparing.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.PREPARING, due + 3_000
        )
        assertEquals("preparing tools", samePhase.scheduledWakes.single().execution?.detail)
        val requesting = samePhase.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.REQUESTING, due + 4_000
        ).scheduledWakes.single().execution
        assertEquals("conversation", requesting?.conversationId)
        assertNull(requesting?.detail)
    }

    @Test
    fun `deleted plans are never recreated and unfinished callbacks cannot start new attempts`() {
        val deleted = WakeSetting()
        assertSame(deleted, deleted.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.COMPLETED, due + 1_000
        ))
        val pending = setting()
        assertSame(pending, pending.withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.STREAMING, due + 1_000
        ))
        assertSame(pending, pending.withWakeExecution(
            "wake", due + 1, ScheduledWakeExecutionStatus.START_FAILED, due + 1_000
        ))
    }

    @Test
    fun `stored diagnostics are bounded and do not contain control characters`() {
        val updated = started().withWakeExecution(
            "wake", due, ScheduledWakeExecutionStatus.FAILED, due + 2_000,
            detail = "failure\u0000\n" + "x".repeat(500),
        )
        val detail = requireNotNull(updated.scheduledWakes.single().execution?.detail)
        assertTrue(detail.length <= 240)
        assertFalse(detail.any { it.isISOControl() })
        assertEquals(updated, Json.decodeFromString<WakeSetting>(Json.encodeToString(updated)))
    }
}
