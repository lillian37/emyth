/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.emyth.app.data.datastore.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

class ScheduledWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = ScheduledWakeScheduler(appContext, getKoin().get<SettingsStore>())
                if (intent.action == ScheduledWakeScheduler.ACTION_WAKE) {
                    val id = intent.getStringExtra(ScheduledWakeScheduler.EXTRA_WAKE_ID) ?: return@launch
                    val scheduledAt = intent.getLongExtra(ScheduledWakeScheduler.EXTRA_TRIGGER_AT, 0L)
                    val wake = scheduler.onAlarm(id, scheduledAt) ?: return@launch
                    appContext.startForegroundService(
                        Intent(appContext, ProactiveMessageTriggerService::class.java).apply {
                            putExtra(ProactiveMessageTriggerService.EXTRA_SCHEDULED_WAKE_NOTE, wake.note)
                            wake.prompt?.let {
                                putExtra(ProactiveMessageTriggerService.EXTRA_SCHEDULED_WAKE_PROMPT, it)
                            }
                        }
                    )
                } else {
                    scheduler.restore()
                }
            } catch (e: Exception) {
                Log.e("ScheduledWakeReceiver", "Failed to handle scheduled wake", e)
            } finally {
                pending.finish()
            }
        }
    }
}
