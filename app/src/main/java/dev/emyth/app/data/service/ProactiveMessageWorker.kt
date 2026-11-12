/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CancellationException
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.datastore.firstLoadedSettings
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based fallback for proactive message scheduling.
 * More reliable than AlarmManager on devices with aggressive battery optimization.
 */
class ProactiveMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProactiveMessageWorker"
        private const val UNIQUE_WORK_NAME = "proactive_message_work"

        fun scheduleAt(context: Context, triggerAt: Long, replace: Boolean) {
            val delayMs = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)

            val workRequest = OneTimeWorkRequestBuilder<ProactiveMessageWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder().putLong(ProactiveMessageService.EXTRA_RANDOM_TRIGGER_AT, triggerAt).build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    workRequest
                )

            Log.d(TAG, "Scheduled proactive fallback for $triggerAt (replace=$replace)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled WorkManager proactive message")
        }

        /**
         * Check if exact alarm permission is granted (Android 12+)
         */
        fun canScheduleExactAlarms(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return true
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }

        /**
         * Check if app is ignoring battery optimizations
         */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "ProactiveMessageWorker triggered")

        val settings = try {
            prepareProactiveStep("读取主动消息设置") {
                val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                settingsStore.settingsFlowRaw.firstLoadedSettings()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unable to load proactive settings; retrying delivery", e)
            return Result.retry()
        }
        val proactiveSetting = settings.proactiveMessageSetting

        if (!proactiveSetting.enabled) {
            Log.d(TAG, "Proactive message disabled, skipping")
            return Result.success()
        }

        // Acquire a wake lock for the duration of the work
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProactiveMessage::WorkerWakeLock"
        )
        try {
            wakeLock.acquire(5 * 60 * 1000L) // Handoff only; the service owns its own request lock.
            // Delegate to the existing trigger service logic
            // Start the foreground service which handles the actual AI generation
            val serviceIntent = android.content.Intent(applicationContext, ProactiveMessageTriggerService::class.java)
            inputData.getLong(ProactiveMessageService.EXTRA_RANDOM_TRIGGER_AT, 0L).takeIf { it > 0L }?.let {
                serviceIntent.putExtra(ProactiveMessageService.EXTRA_RANDOM_TRIGGER_AT, it)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            // The service atomically claims this deadline and rearms both sources before generating.
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ProactiveMessageWorker failed", e)
            ProactiveExecutionStore.begin(
                applicationContext,
                inputData.getLong(ProactiveMessageService.EXTRA_RANDOM_TRIGGER_AT, 0L).takeIf { it > 0L },
                ProactiveExecutionStatus.START_FAILED,
                "备用任务已运行，但后台服务未能启动（${e.javaClass.simpleName}）；已安排重试",
            )
            // Retry this delivery; replacing unique work here would cancel our own retry chain.
            return Result.retry()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
