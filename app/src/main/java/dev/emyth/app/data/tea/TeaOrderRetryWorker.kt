package dev.emyth.app.data.tea

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.emyth.app.data.datastore.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.java.KoinJavaComponent.getKoin

/** Bounded transport retry, restricted to an event for which no model/tool request has started. */
class TeaOrderRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(TeaOrderMonitor.RETRY_EVENT_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        try {
            val store = TeaOrderStore.get(applicationContext)
            val event = store.events.value.firstOrNull { it.id == eventId } ?: return Result.success()
            if (event.generationStartedAtMillis != null) return Result.success()
            if (event.status == TeaOrderStatus.PROCESSING) return followUp(event)
            if (event.status != TeaOrderStatus.PENDING) return Result.success()
            val settings = getKoin().get<SettingsStore>().settingsFlowRaw.first()
            // The service may have claimed while settings were loading. Retain a check while that
            // owner has not started generation: a busy release can otherwise lose its KEEP enqueue.
            val current = store.events.value.firstOrNull { it.id == eventId } ?: return Result.success()
            if (current.status != TeaOrderStatus.PENDING || current.generationStartedAtMillis != null) {
                return followUp(current)
            }
            val setting = settings.teaOrderSetting
            val failure = when {
                !setting.enabled -> TeaOrderFailure.FEATURE_DISABLED
                setting.assistantId != event.targetAssistantId ||
                    settings.assistants.none { it.id.toString() == event.targetAssistantId } ->
                    TeaOrderFailure.ASSISTANT_UNAVAILABLE
                setting.enabledSinceMillis <= 0L || event.notificationPostedAtMillis < setting.enabledSinceMillis ->
                    TeaOrderFailure.FEATURE_DISABLED
                event.notificationPostedAtMillis <= 0L ||
                    event.notificationPostedAtMillis > System.currentTimeMillis() + 60_000L ->
                    TeaOrderFailure.INVALID_NOTIFICATION_TIME
                System.currentTimeMillis() - event.notificationPostedAtMillis >
                    TeaOrderStateReducer.FRESH_NOTIFICATION_MILLIS -> TeaOrderFailure.STALE_NOTIFICATION
                runAttemptCount >= MAX_DISPATCH_ATTEMPTS -> TeaOrderFailure.SESSION_BUSY
                else -> null
            }
            if (failure != null) {
                store.markPendingFailed(event.id, failure)
                return followUp(store.events.value.firstOrNull { it.id == eventId })
            }
            val accepted = TeaOrderMonitor.dispatch(applicationContext, eventId)
            if (!accepted && runAttemptCount + 1 >= MAX_DISPATCH_ATTEMPTS) {
                store.markPendingFailed(eventId, TeaOrderFailure.SERVICE_START_FAILED)
                return followUp(store.events.value.firstOrNull { it.id == eventId })
            }
            // A busy service can release the claim before this worker finishes. KEEP would drop a new
            // request enqueued from that service while this work is RUNNING, so retain this work's retry.
            // A future run waits without dispatch for PROCESSING whose generation has not started.
            // Retain the next ledger check even if the service just claimed PROCESSING: a busy service
            // may release it a moment later while this worker is still RUNNING. No later worker dispatch
            // is permitted unless its initial check sees PENDING with generationStarted still null.
            return Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w(TAG, TeaOrderFailure.SERVICE_START_FAILED.name)
            val store = runCatching { TeaOrderStore.get(applicationContext) }.getOrNull()
            val current = store?.events?.value?.firstOrNull { it.id == eventId }
            if (current != null &&
                (current.status != TeaOrderStatus.PENDING || current.generationStartedAtMillis != null)
            ) return followUp(current)
            if (runAttemptCount + 1 < MAX_DISPATCH_ATTEMPTS) return Result.retry()
            // Best-effort visibility when setup failed repeatedly; the guarded transition cannot
            // overwrite a service-owned PROCESSING event or any event whose generation has started.
            runCatching {
                store?.markPendingFailed(eventId, TeaOrderFailure.SERVICE_START_FAILED)
            }
            if (store != null) return followUp(store.events.value.firstOrNull { it.id == eventId })
            return Result.failure()
        }
    }

    /** Never dispatch or fail a PROCESSING owner, including after the transport-attempt budget. */
    private fun followUp(event: TeaOrderEvent?): Result {
        if (event == null || event.generationStartedAtMillis != null ||
            (event.status != TeaOrderStatus.PENDING && event.status != TeaOrderStatus.PROCESSING)
        ) return Result.success()
        val now = System.currentTimeMillis()
        return if (event.notificationPostedAtMillis > 0L && event.notificationPostedAtMillis <= now + 60_000L &&
            now - event.notificationPostedAtMillis <= TeaOrderStateReducer.FRESH_NOTIFICATION_MILLIS
        ) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "TeaOrderRetryWorker"
        private const val MAX_DISPATCH_ATTEMPTS = 5
    }
}
