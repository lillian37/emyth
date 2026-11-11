package dev.emyth.app.data.service

import dev.emyth.app.data.datastore.ProactiveMessageSetting
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveScheduleCoordinatorTest {
    private val enabled = ProactiveMessageSetting(enabled = true, minIntervalMinutes = 1, maxIntervalMinutes = 2)

    @Test
    fun bothSourcesUseTheSameSingleRandomDeadline() {
        val fixture = Fixture()

        fixture.coordinator.scheduleNext(enabled)

        assertEquals(61_000L, fixture.triggerAt)
        assertEquals(listOf(61_000L), fixture.alarms)
        assertEquals(listOf(61_000L to true), fixture.work)
        assertEquals(listOf(1 to 2), fixture.ranges)
    }

    @Test
    fun coldStartRestorationPreservesThePendingDeadlineWithoutRandomizing() {
        val fixture = Fixture(triggerAt = 60_000L)

        fixture.coordinator.restore(enabled)
        fixture.time = 20_000L
        fixture.coordinator.restore(enabled)

        assertEquals(60_000L, fixture.triggerAt)
        assertEquals(listOf(60_000L, 60_000L), fixture.alarms)
        assertEquals(listOf(60_000L to false, 60_000L to false), fixture.work)
        assertTrue(fixture.ranges.isEmpty())
    }

    @Test
    fun restorationKeepsAnOverdueDeadlineAvailableForImmediateDelivery() {
        val fixture = Fixture(triggerAt = 500L)

        fixture.coordinator.restore(enabled)

        assertEquals(500L, fixture.triggerAt)
        assertEquals(listOf(500L), fixture.alarms)
        assertEquals(listOf(500L to false), fixture.work)
        assertTrue(fixture.ranges.isEmpty())
        assertTrue(fixture.coordinator.claim(500L, enabled))
        assertEquals(61_000L, fixture.triggerAt)
    }

    @Test
    fun restorationCreatesAMissingDeadlineAndKeepsExistingWork() {
        val fixture = Fixture()

        fixture.coordinator.restore(enabled)

        assertEquals(61_000L, fixture.triggerAt)
        assertEquals(listOf(61_000L to false), fixture.work)
        assertEquals(1, fixture.ranges.size)
    }

    @Test
    fun duplicateSourceDeliveryCannotConsumeOrRescheduleTheNewRound() {
        val fixture = Fixture(triggerAt = 1_000L)

        assertTrue(fixture.coordinator.claim(1_000L, enabled))
        assertFalse(fixture.coordinator.claim(1_000L, enabled))

        assertEquals(61_000L, fixture.triggerAt)
        assertEquals(1, fixture.alarms.size)
        assertEquals(1, fixture.work.size)
    }

    @Test
    fun concurrentSourcesCanClaimTheSameRoundOnlyOnce() {
        val fixture = Fixture(triggerAt = 1_000L)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val results = List(2) {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    fixture.coordinator.claim(1_000L, enabled)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, fixture.work.size)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun resetInvalidatesThePreviousRoundEvenWhenTheClockHasNotMoved() {
        val fixture = Fixture()
        fixture.coordinator.scheduleNext(enabled)
        val obsolete = fixture.triggerAt!!

        fixture.coordinator.scheduleNext(enabled)
        assertEquals(obsolete + 1L, fixture.triggerAt)
        fixture.time = obsolete

        assertFalse(fixture.coordinator.claim(obsolete, enabled))
        assertEquals(obsolete + 1L, fixture.triggerAt)
        assertEquals(2, fixture.work.size)
    }

    @Test
    fun shorteningTheIntervalMovesTheDeadlineEarlierAndInvalidatesTheOldRound() {
        val fixture = Fixture()
        fixture.coordinator.scheduleNext(enabled.copy(minIntervalMinutes = 90, maxIntervalMinutes = 90))
        val obsolete = fixture.triggerAt!!

        fixture.coordinator.scheduleNext(enabled.copy(minIntervalMinutes = 1, maxIntervalMinutes = 1))

        assertEquals(5_401_000L, obsolete)
        assertEquals(61_000L, fixture.triggerAt)
        assertTrue(fixture.triggerAt!! < obsolete)
        fixture.time = obsolete
        assertFalse(fixture.coordinator.claim(obsolete, enabled))
        assertEquals(61_000L, fixture.triggerAt)
        assertEquals(2, fixture.work.size)
    }

    @Test
    fun anExplicitResetAfterAClockRollbackUsesTheNewClockTime() {
        val fixture = Fixture()
        fixture.time = 1_000_000L
        fixture.coordinator.scheduleNext(enabled)
        val obsolete = fixture.triggerAt!!

        fixture.time = 1_000L
        fixture.coordinator.scheduleNext(enabled)

        assertEquals(1_060_000L, obsolete)
        assertEquals(61_000L, fixture.triggerAt)
        fixture.time = obsolete
        assertFalse(fixture.coordinator.claim(obsolete, enabled))
        assertEquals(61_000L, fixture.triggerAt)
    }

    @Test
    fun earlyAndInvalidDeliveriesLeaveTheCurrentRoundUnchanged() {
        val fixture = Fixture(triggerAt = 60_000L)

        assertFalse(fixture.coordinator.claim(60_000L, enabled))
        assertFalse(fixture.coordinator.claim(0L, enabled))
        assertFalse(fixture.coordinator.claim(-1L, enabled))

        assertEquals(60_000L, fixture.triggerAt)
        assertTrue(fixture.work.isEmpty())
        assertTrue(fixture.ranges.isEmpty())
    }

    @Test
    fun disabledSettingsCancelBothSourcesAndRejectClaims() {
        val fixture = Fixture(triggerAt = 1_000L)
        val disabled = enabled.copy(enabled = false)

        assertFalse(fixture.coordinator.claim(1_000L, disabled))
        fixture.coordinator.restore(disabled)

        assertNull(fixture.triggerAt)
        assertEquals(1, fixture.cancelledAlarms)
        assertEquals(1, fixture.cancelledWork)
        assertFalse(fixture.coordinator.claim(1_000L, enabled))
        assertTrue(fixture.work.isEmpty())
    }

    @Test
    fun disablingThenEnablingStartsANewRound() {
        val fixture = Fixture(triggerAt = 1_000L)

        fixture.coordinator.scheduleNext(enabled.copy(enabled = false))
        fixture.time = 2_000L
        fixture.coordinator.scheduleNext(enabled)

        assertEquals(62_000L, fixture.triggerAt)
        assertEquals(listOf(62_000L to true), fixture.work)
        assertEquals(1, fixture.cancelledAlarms)
        assertEquals(1, fixture.cancelledWork)
    }

    @Test
    fun invalidIntervalBoundsAreNormalizedBeforeChoosingADelay() {
        val fixture = Fixture()

        fixture.coordinator.scheduleNext(enabled.copy(minIntervalMinutes = Int.MIN_VALUE, maxIntervalMinutes = -1))

        assertEquals(listOf(1 to 1), fixture.ranges)
        assertEquals(61_000L, fixture.triggerAt)
    }

    @Test
    fun maximumIntegerIntervalDoesNotOverflowRandomBoundsOrMilliseconds() {
        var triggerAt: Long? = null
        val alarms = mutableListOf<Long>()
        val coordinator = ProactiveScheduleCoordinator(
            readTrigger = { triggerAt },
            writeTrigger = { triggerAt = it },
            scheduleAlarm = { alarms.add(it) },
            scheduleWork = { _, _ -> },
            cancelAlarm = {},
            cancelWork = {},
            now = { 1_000L },
        )

        coordinator.scheduleNext(enabled.copy(minIntervalMinutes = Int.MAX_VALUE, maxIntervalMinutes = Int.MIN_VALUE))

        assertEquals(1_000L + Int.MAX_VALUE.toLong() * 60_000L, triggerAt)
        assertEquals(listOf(triggerAt), alarms)
    }

    private class Fixture(var triggerAt: Long? = null) {
        var time = 1_000L
        val alarms = mutableListOf<Long>()
        val work = mutableListOf<Pair<Long, Boolean>>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var cancelledAlarms = 0
        var cancelledWork = 0
        val coordinator = ProactiveScheduleCoordinator(
            readTrigger = { triggerAt },
            writeTrigger = { triggerAt = it },
            scheduleAlarm = { alarms.add(it) },
            scheduleWork = { at, replace -> work.add(at to replace) },
            cancelAlarm = { cancelledAlarms++ },
            cancelWork = { cancelledWork++ },
            now = { time },
            chooseDelayMinutes = { min, max ->
                ranges.add(min to max)
                min.toLong()
            },
        )
    }
}
