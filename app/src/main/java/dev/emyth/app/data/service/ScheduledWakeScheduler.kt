/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.emyth.app.data.datastore.ScheduledWake
import dev.emyth.app.data.datastore.ScheduledWakeExecutionStatus
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.datastore.WakeRepeat
import dev.emyth.app.data.datastore.WakeSetting
import dev.emyth.app.data.datastore.afterAlarm
import dev.emyth.app.data.datastore.rescheduled
import dev.emyth.app.data.datastore.withWakeExecution
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

class ScheduledWakeScheduler(context: Context, private val store: SettingsStore) {
    private val context = context.applicationContext
    private val alarms get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val ACTION_WAKE = "dev.emyth.app.SCHEDULED_WAKE"
        const val EXTRA_WAKE_ID = "scheduled_wake_id"
        const val EXTRA_TRIGGER_AT = "scheduled_wake_trigger_at"
        private const val TAG = "ScheduledWakeScheduler"
        private val mutex = Mutex()
    }

    suspend fun add(wake: ScheduledWake): Boolean = mutex.withLock {
        withContext(NonCancellable) {
            store.updateWakeSetting { it.copy(scheduledWakes = it.scheduledWakes + wake) }
            try {
                arm(wake)
            } catch (e: Exception) {
                store.updateWakeSetting { setting ->
                    setting.copy(scheduledWakes = setting.scheduledWakes.filterNot { it.id == wake.id })
                }
                cancelAlarm(wake)
                throw e
            }
        }
    }

    suspend fun cancel(id: String?, note: String?): Pair<Int, Int> = mutex.withLock {
        withContext(NonCancellable) {
            var cancelled = emptyList<ScheduledWake>()
            val remaining = store.updateWakeSetting { setting ->
                cancelled = setting.scheduledWakes.filter { wake ->
                    when {
                        id != null -> wake.id == id
                        note != null -> wake.note.contains(note)
                        else -> false
                    }
                }
                setting.copy(scheduledWakes = setting.scheduledWakes - cancelled.toSet())
            }
            cancelled.forEach(::cancelAlarm)
            cancelled.size to remaining.scheduledWakes.size
        }
    }

    suspend fun restore(): WakeSetting = mutex.withLock {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val setting = store.updateWakeSetting { current ->
            current.copy(scheduledWakes = current.scheduledWakes.map { it.rescheduled(now, zone) })
        }
        setting.scheduledWakes.forEach { wake ->
            try {
                cancelLegacyAlarm(wake)
                if (wake.repeat == WakeRepeat.DAILY ||
                    (wake.lastTriggeredAtMillis == 0L && wake.triggerAtMillis > now)
                ) {
                    arm(wake)
                } else {
                    cancelAlarm(wake)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore wake ${wake.id}", e)
            }
        }
        setting
    }

    suspend fun onAlarm(id: String, scheduledAtMillis: Long): ScheduledWake? = mutex.withLock {
        withContext(NonCancellable) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            var triggered: ScheduledWake? = null
            var next: ScheduledWake? = null
            store.updateWakeSetting { setting ->
                setting.copy(scheduledWakes = setting.scheduledWakes.map { wake ->
                    if (wake.id != id) return@map wake
                    val consumed = wake.afterAlarm(scheduledAtMillis, now, zone)
                    val updated = consumed ?: wake.rescheduled(now, zone)
                    triggered = consumed
                    if (updated.repeat == WakeRepeat.DAILY) next = updated
                    updated
                })
            }
            // Arm tomorrow before starting any AI/network work, so a failed response cannot stop the series.
            next?.let { wake ->
                try {
                    arm(wake)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rearm daily wake ${wake.id}", e)
                }
            }
            triggered?.takeIf { it.repeat == WakeRepeat.ONCE }?.let { wake ->
                try {
                    cancelAlarm(wake)
                } catch (e: Exception) {
                    // Consumption is already durable. Failure to remove an old PendingIntent
                    // must not discard the accepted request; afterAlarm still rejects duplicates.
                    Log.w(TAG, "Failed to remove consumed alarm ${wake.id}", e)
                }
            }
            triggered
        }
    }

    suspend fun recordExecution(
        id: String,
        scheduledAtMillis: Long,
        status: ScheduledWakeExecutionStatus,
        conversationId: String? = null,
        detail: String? = null,
    ) = mutex.withLock {
        store.updateWakeSetting { setting ->
            setting.withWakeExecution(
                id, scheduledAtMillis, status, System.currentTimeMillis(), conversationId, detail,
            )
        }
    }

    private fun intent(wake: ScheduledWake) = Intent(context, ScheduledWakeReceiver::class.java).apply {
        action = ACTION_WAKE
        data = Uri.Builder().scheme("emyth").authority("scheduled-wake").appendPath(wake.id).build()
        putExtra(EXTRA_WAKE_ID, wake.id)
        putExtra(EXTRA_TRIGGER_AT, wake.triggerAtMillis)
    }

    private fun arm(wake: ScheduledWake): Boolean {
        val pending = PendingIntent.getBroadcast(
            context, 0, intent(wake), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake.triggerAtMillis, pending)
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm permission changed, using an inexact alarm", e)
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake.triggerAtMillis, pending)
        return false
    }

    private fun cancelAlarm(wake: ScheduledWake) {
        PendingIntent.getBroadcast(
            context, 0, intent(wake), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { alarms.cancel(it); it.cancel() }
        cancelLegacyAlarm(wake)
    }

    private fun cancelLegacyAlarm(wake: ScheduledWake) {
        // Older app versions used numeric IDs as request codes and did not distinguish alarms by URI.
        if (wake.id.toLongOrNull() == null) return
        val legacyIntent = Intent(context, ProactiveMessageReceiver::class.java).apply {
            action = ProactiveMessageService.ACTION_PROACTIVE_MESSAGE
        }
        PendingIntent.getBroadcast(
            context, wake.id.hashCode(), legacyIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { alarms.cancel(it); it.cancel() }
    }
}
