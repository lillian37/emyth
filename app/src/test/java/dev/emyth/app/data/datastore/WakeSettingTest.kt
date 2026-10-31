package dev.emyth.app.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class WakeSettingTest {
    private val utc = ZoneId.of("UTC")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private fun daily(triggerAt: Long, time: String = "00:00") = ScheduledWake(
        id = "wake", triggerAtMillis = triggerAt, note = "hello", repeat = WakeRepeat.DAILY, time = time,
    )

    @Test
    fun `old stored records remain one-time wakes`() {
        val setting = Json.decodeFromString<WakeSetting>(
            """{"scheduledWakes":[{"id":"123","triggerAtMillis":1893456000000,"note":"old"}]}"""
        )
        val wake = setting.scheduledWakes.single()
        assertEquals(WakeRepeat.ONCE, wake.repeat)
        assertNull(wake.time)
        assertEquals(0L, wake.lastTriggeredAtMillis)
        assertEquals(1893456000000L, wake.triggerAtMillis)
    }

    @Test
    fun `daily rules and delivery metadata survive serialization`() {
        val wake = daily(at("2026-11-01T00:00:00Z"))
            .copy(lastTriggeredAtMillis = at("2026-10-31T00:00:01Z"), lastTriggeredDate = "2026-10-31")
        val encoded = Json.encodeToString(wake)
        assertTrue(encoded.contains("\"repeat\":\"daily\""))
        assertEquals(wake, Json.decodeFromString<ScheduledWake>(encoded))
    }

    @Test
    fun `a later time today is used`() {
        assertEquals(at("2026-10-31T08:30:00Z"),
            nextDailyWakeMillis("08:30", at("2026-10-31T08:00:00Z"), utc))
    }

    @Test
    fun `midnight after today's midnight rolls into tomorrow`() {
        assertEquals(at("2026-11-01T00:00:00Z"),
            nextDailyWakeMillis("00:00", at("2026-10-31T00:00:01Z"), utc))
    }

    @Test
    fun `an exact match selects a strictly future occurrence`() {
        assertEquals(at("2026-11-01T00:00:00Z"),
            nextDailyWakeMillis("00:00", at("2026-10-31T00:00:00Z"), utc))
    }

    @Test
    fun `year and leap-day boundaries use calendar dates`() {
        assertEquals(at("2027-01-01T00:00:00Z"),
            nextDailyWakeMillis("00:00", at("2026-12-31T23:59:59Z"), utc))
        assertEquals(at("2028-02-29T08:00:00Z"),
            nextDailyWakeMillis("08:00", at("2028-02-28T09:00:00Z"), utc))
    }

    @Test
    fun `phone time zone determines the wall-clock time`() {
        assertEquals(at("2026-10-31T16:00:00Z"),
            nextDailyWakeMillis("00:00", at("2026-10-31T12:00:00Z"), ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `spring daylight saving keeps the same local hour`() {
        val zone = ZoneId.of("Europe/Berlin")
        val before = at("2026-03-28T07:30:00Z")
        val next = nextDailyWakeMillis("08:30", before, zone)
        assertEquals(23L, Duration.between(Instant.ofEpochMilli(before), Instant.ofEpochMilli(next)).toHours())
        assertEquals("08:30", Instant.ofEpochMilli(next).atZone(zone).toLocalTime().toString())
    }

    @Test
    fun `autumn daylight saving keeps the same local hour`() {
        val zone = ZoneId.of("Europe/Berlin")
        val before = at("2026-10-24T06:30:00Z")
        val next = nextDailyWakeMillis("08:30", before, zone)
        assertEquals(25L, Duration.between(Instant.ofEpochMilli(before), Instant.ofEpochMilli(next)).toHours())
        assertEquals("08:30", Instant.ofEpochMilli(next).atZone(zone).toLocalTime().toString())
    }

    @Test
    fun `a nonexistent clock time moves forward through the daylight-saving gap`() {
        val next = nextDailyWakeMillis("02:30", at("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(at("2026-03-08T07:30:00Z"), next)
    }

    @Test
    fun `a repeated clock hour does not create a second wake that day`() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(at("2026-11-01T05:30:00Z"),
            nextDailyWakeMillis("01:30", at("2026-11-01T04:00:00Z"), zone))
        assertEquals(at("2026-11-02T06:30:00Z"),
            nextDailyWakeMillis("01:30", at("2026-11-01T05:45:00Z"), zone))
    }

    @Test
    fun `one-time timestamps stay absolute after a time-zone change`() {
        val wake = ScheduledWake(id = "once", triggerAtMillis = at("2026-11-01T00:00:00Z"))
        assertEquals(wake, wake.rescheduled(at("2026-10-31T12:00:00Z"), ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `a one-time alarm is delivered only once`() {
        val due = at("2026-10-31T00:00:00Z")
        val wake = ScheduledWake(id = "once", triggerAtMillis = due)
        val fired = requireNotNull(wake.afterAlarm(due, due + 1_000, utc))
        assertEquals(due + 1_000, fired.lastTriggeredAtMillis)
        assertNull(fired.afterAlarm(due, due + 2_000, utc))
        assertNull(wake.afterAlarm(due + 1, due + 2_000, utc))
    }

    @Test
    fun `an early delivery is ignored`() {
        val due = at("2026-10-31T00:00:00Z")
        assertNull(daily(due).afterAlarm(due, due - 1, utc))
    }

    @Test
    fun `a cold-start restore cannot discard the alarm already being delivered`() {
        val due = at("2026-10-31T00:00:00Z")
        val restored = daily(due).rescheduled(due + 1_000, utc)
        assertEquals(at("2026-11-01T00:00:00Z"), restored.triggerAtMillis)
        val fired = requireNotNull(restored.afterAlarm(due, due + 1_000, utc))
        assertEquals("2026-10-31", fired.lastTriggeredDate)
        assertEquals(at("2026-11-01T00:00:00Z"), fired.triggerAtMillis)
        assertNull(fired.afterAlarm(due, due + 2_000, utc))
    }

    @Test
    fun `a later duplicate on the same local day is ignored`() {
        val due = at("2026-10-31T00:00:00Z")
        val fired = requireNotNull(daily(due).afterAlarm(due, due + 1_000, utc))
        assertNull(fired.afterAlarm(due + 60_000, due + 61_000, utc))
    }

    @Test
    fun `restoring after a backward clock change does not repeat an already fired day`() {
        val wake = daily(at("2026-10-31T08:00:00Z"), "08:00")
            .copy(lastTriggeredAtMillis = at("2026-10-31T08:00:01Z"), lastTriggeredDate = "2026-10-31")
        val restored = wake.rescheduled(at("2026-10-31T07:00:00Z"), utc)
        assertEquals(at("2026-11-01T08:00:00Z"), restored.triggerAtMillis)
    }

    @Test
    fun `a clock correction to a different date can establish a new daily sequence`() {
        val wake = daily(at("2026-11-03T08:00:00Z"), "08:00")
            .copy(lastTriggeredAtMillis = at("2026-11-02T08:00:01Z"), lastTriggeredDate = "2026-11-02")
        val correctedDue = at("2026-10-31T08:00:00Z")
        val fired = requireNotNull(wake.afterAlarm(correctedDue, correctedDue + 1_000, utc))
        assertEquals(at("2026-11-01T08:00:00Z"), fired.triggerAtMillis)
    }

    @Test
    fun `a delayed alarm advances from delivery time without a catch-up burst`() {
        val due = at("2026-10-29T00:00:00Z")
        val fired = requireNotNull(daily(due).afterAlarm(due, at("2026-10-31T12:00:00Z"), utc))
        assertEquals(at("2026-11-01T00:00:00Z"), fired.triggerAtMillis)
    }

    @Test
    fun `daily wakes keep rearming across multiple weeks`() {
        var wake = daily(at("2026-10-31T00:00:00Z"))
        repeat(35) {
            val due = wake.triggerAtMillis
            wake = requireNotNull(wake.afterAlarm(due, due + 1_000, utc))
            assertEquals(due + Duration.ofDays(1).toMillis(), wake.triggerAtMillis)
        }
    }
}
