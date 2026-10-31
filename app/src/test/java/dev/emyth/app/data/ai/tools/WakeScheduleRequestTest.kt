package dev.emyth.app.data.ai.tools

import dev.emyth.app.data.datastore.WakeRepeat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WakeScheduleRequestTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private fun parse(value: String) =
        parseWakeScheduleRequest(Json.parseToJsonElement(value).jsonObject, "id", now, utc)

    @Test
    fun `legacy timestamp-only calls remain one-time wakes`() {
        val wake = parse("""{"trigger_at":${now + 60_000},"note":"old call"}""")
        assertEquals(WakeRepeat.ONCE, wake.repeat)
        assertEquals(now + 60_000, wake.triggerAtMillis)
        assertEquals("old call", wake.note)
        assertNull(wake.time)
    }

    @Test
    fun `daily calls need no timestamp`() {
        val wake = parse("""{"repeat":"daily","time":"00:00","note":"sleep"}""")
        assertEquals(WakeRepeat.DAILY, wake.repeat)
        assertEquals("00:00", wake.time)
        assertEquals("sleep", wake.note)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), wake.triggerAtMillis)
    }

    @Test
    fun `the selected daily mode uses its clock time even if an old timestamp is included`() {
        val wake = parse("""{"repeat":"daily","time":"13:30","trigger_at":1}""")
        assertEquals(Instant.parse("2026-10-31T13:30:00Z").toEpochMilli(), wake.triggerAtMillis)
    }

    @Test
    fun `explicit once mode remains supported`() {
        val wake = parse("""{"repeat":"once","trigger_at":${now + 1}}""")
        assertEquals(WakeRepeat.ONCE, wake.repeat)
    }

    @Test
    fun `invalid and missing one-time timestamps are rejected`() {
        listOf("{}", """{"trigger_at":$now}""", """{"trigger_at":1}""",
            """{"trigger_at":"invalid"}""", """{"trigger_at":9999999999999999999999}""")
            .forEach { value ->
                assertTrue(
                    "Expected rejection: $value",
                    runCatching { parse(value) }.exceptionOrNull() is IllegalArgumentException,
                )
            }
    }

    @Test
    fun `daily requires a valid zero-padded 24-hour clock time`() {
        listOf("", "9:00", "24:00", "00:60", "-1:00", "12:00:00", " 08:00", "12:5", "tomorrow")
            .forEach { time ->
                val value = """{"repeat":"daily","time":"$time"}"""
                assertTrue(
                    "Expected rejection: $time",
                    runCatching { parse(value) }.exceptionOrNull() is IllegalArgumentException,
                )
            }
        assertTrue(runCatching { parse("""{"repeat":"daily"}""") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `unsupported repeat modes are rejected`() {
        assertTrue(runCatching { parse("""{"repeat":"weekly","time":"08:00"}""") }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `notes remain optional`() {
        assertEquals("", parse("""{"repeat":"daily","time":"23:59"}""").note)
    }
}
