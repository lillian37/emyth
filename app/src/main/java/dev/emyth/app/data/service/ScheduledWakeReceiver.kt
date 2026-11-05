/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.datastore.ScheduledWakeExecutionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.java.KoinJavaComponent.getKoin

class ScheduledWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action == ScheduledWakeScheduler.ACTION_WAKE) {
            val id = intent.getStringExtra(ScheduledWakeScheduler.EXTRA_WAKE_ID) ?: return
            val scheduledAt = intent.getLongExtra(ScheduledWakeScheduler.EXTRA_TRIGGER_AT, 0L)
            if (id.isBlank() || scheduledAt <= 0L) return
            try {
                // AlarmManager protects synchronous receiver delivery, not a later IO coroutine.
                // Hand off immediately while the alarm's background-start allowance is current.
                // The service will consume/rearm the plan only after it owns CPU/FGS protection.
                appContext.startForegroundService(
                    Intent(appContext, ProactiveMessageTriggerService::class.java).apply {
                        putExtra(ScheduledWakeScheduler.EXTRA_WAKE_ID, id)
                        putExtra(ScheduledWakeScheduler.EXTRA_TRIGGER_AT, scheduledAt)
                    }
                )
            } catch (e: Exception) {
                Log.e("ScheduledWakeReceiver", "Failed to start scheduled wake service", e)
                recordStartFailure(appContext, id, scheduledAt, e)
            }
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = ScheduledWakeScheduler(appContext, getKoin().get<SettingsStore>())
                scheduler.restore()
            } catch (e: Exception) {
                Log.e("ScheduledWakeReceiver", "Failed to handle scheduled wake", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun recordStartFailure(context: Context, id: String, scheduledAt: Long, error: Exception) {
        val pending = goAsync()
        val wakeLock = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Emyth:WakeFailureRecord")
                .apply { setReferenceCounted(false); acquire(10_000L) }
        }.getOrNull()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) {
                    ScheduledWakeScheduler(context, getKoin().get<SettingsStore>()).recordExecution(
                        id, scheduledAt, ScheduledWakeExecutionStatus.START_FAILED,
                        detail = "系统未允许启动后台唤醒服务（${error.javaClass.simpleName}）",
                    )
                }
            } catch (e: Exception) {
                Log.e("ScheduledWakeReceiver", "Failed to persist scheduled wake start failure", e)
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                pending.finish()
            }
        }
    }
}
