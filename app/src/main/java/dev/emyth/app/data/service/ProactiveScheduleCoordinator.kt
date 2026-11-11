/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import dev.emyth.app.data.datastore.ProactiveMessageSetting
import kotlin.random.Random

/** One persisted deadline belongs to both delivery sources and can only be consumed once. */
internal class ProactiveScheduleCoordinator(
    private val readTrigger: () -> Long?,
    private val writeTrigger: (Long?) -> Unit,
    private val scheduleAlarm: (Long) -> Unit,
    private val scheduleWork: (Long, Boolean) -> Unit,
    private val cancelAlarm: () -> Unit,
    private val cancelWork: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val chooseDelayMinutes: (Int, Int) -> Long = { min, max ->
        Random.nextLong(min.toLong(), max.toLong() + 1L)
    },
) {
    private val lock = Any()

    fun scheduleNext(setting: ProactiveMessageSetting) = synchronized(lock) {
        scheduleNextLocked(setting)
    }

    fun restore(setting: ProactiveMessageSetting) = synchronized(lock) {
        if (!setting.enabled) {
            cancelLocked()
        } else {
            // A past deadline is still pending: let the platform deliver it immediately.
            val triggerAt = readTrigger()?.takeIf { it > 0L } ?: nextDeadline(setting).also(writeTrigger)
            scheduleAlarm(triggerAt)
            scheduleWork(triggerAt, false)
        }
    }

    fun claim(expectedAt: Long, setting: ProactiveMessageSetting): Boolean = synchronized(lock) {
        if (!setting.enabled || expectedAt <= 0L || expectedAt != readTrigger() || expectedAt > now()) {
            false
        } else {
            // Rearm before generation so interruption cannot break the scheduling chain.
            scheduleNextLocked(setting)
            true
        }
    }

    fun cancel() = synchronized(lock) {
        cancelLocked()
    }

    private fun scheduleNextLocked(setting: ProactiveMessageSetting) {
        if (!setting.enabled) {
            cancelLocked()
            return
        }
        val triggerAt = nextDeadline(setting)
        writeTrigger(triggerAt)
        scheduleAlarm(triggerAt)
        scheduleWork(triggerAt, true)
    }

    private fun nextDeadline(setting: ProactiveMessageSetting): Long {
        val min = setting.minIntervalMinutes.coerceAtLeast(1)
        val max = setting.maxIntervalMinutes.coerceAtLeast(min)
        val delayMillis = chooseDelayMinutes(min, max) * 60_000L
        val candidate = now() + delayMillis
        return if (candidate == readTrigger()) candidate + 1L else candidate
    }

    private fun cancelLocked() {
        writeTrigger(null)
        cancelAlarm()
        cancelWork()
    }
}
