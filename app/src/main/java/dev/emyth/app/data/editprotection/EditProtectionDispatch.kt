/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.editprotection

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.emyth.app.data.service.ProactiveMessageTriggerService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val WORK_NAME = "edit_protection_wake"
const val EXTRA_EDIT_PROTECTION_ASSISTANT_ID = "edit_protection_assistant_id"

/** The local edit already remains blocked even if Android cannot launch the delivery immediately. */
fun startEditProtectionDispatch(context: Context, scope: CoroutineScope) {
    val app = context.applicationContext
    scope.launch(Dispatchers.IO) {
        val store = EditProtectionStore.get(app)
        store.recoverAfterProcessStart()
        store.changes.collect {
            val delay = store.nextWakeDelayMillis() ?: return@collect
            scheduleEditProtectionWake(app, delay)
            if (delay == 0L) store.pendingAssistantIds().forEach { assistantId ->
                runCatching { deliverEditProtectionWake(app, assistantId) }
                    .onFailure { Log.w("EditProtection", "Immediate wake delivery deferred", it) }
            }
        }
    }
}

fun scheduleEditProtectionWake(context: Context, delayMillis: Long = 0L) {
    val request = OneTimeWorkRequestBuilder<EditProtectionWakeWorker>()
        .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
}

private fun deliverEditProtectionWake(context: Context, assistantId: String) {
    context.startForegroundService(Intent(context, ProactiveMessageTriggerService::class.java).apply {
        putExtra(EXTRA_EDIT_PROTECTION_ASSISTANT_ID, assistantId)
    })
}

class EditProtectionWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val store = EditProtectionStore.get(applicationContext)
        val delay = store.nextWakeDelayMillis()
        if (delay == null) Result.success() else {
            if (delay == 0L) store.pendingAssistantIds().forEach {
                deliverEditProtectionWake(applicationContext, it)
            }
            // The service claims and marks each batch; this worker never replays model operations.
            if (store.nextWakeDelayMillis() == null) Result.success() else Result.retry()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w("EditProtection", "Wake delivery remains queued", failure)
        Result.retry()
    }
}
