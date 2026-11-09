package dev.emyth.app.data.tea

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.emyth.app.data.datastore.Settings
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.service.ProactiveMessageTriggerService
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.java.KoinJavaComponent.getKoin

/** Opt-in notification intake. Only bounded, structured detector evidence reaches the durable ledger. */
object TeaOrderMonitor {
    private const val TAG = "TeaOrderMonitor"
    internal const val RETRY_EVENT_ID = "tea_order_event_id"
    private const val RETRY_PREFIX = "tea_order_retry_"
    internal const val RETRY_DELAY_MILLIS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationMutex = Mutex()
    @Volatile private var started = false
    @Volatile private var configuration = Configuration()
    @Volatile private var listener = WeakReference<NotificationListenerService>(null)

    /** Safe to call from application startup or the listener; subscribes to persisted settings once. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (started) return
            val settingsStore = runCatching { getKoin().get<SettingsStore>() }.getOrNull() ?: return
            started = true
            scope.launch {
                try {
                    // Raw flow avoids the UI StateFlow's initial placeholder Settings instance.
                    settingsStore.settingsFlowRaw.map(::configurationFor).distinctUntilChanged().collect { next ->
                        val previous = configuration
                        configuration = next
                        reconcilePending(appContext, next)
                        if (next.canObserve && next != previous) scanActive(appContext)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    configuration = Configuration()
                    started = false
                    Log.w(TAG, TeaOrderFailure.FEATURE_DISABLED.name)
                }
            }
        }
    }

    fun onListenerConnected(service: NotificationListenerService) {
        listener = WeakReference(service)
        start(service)
        if (configuration.canObserve) scope.launch { scanActive(service.applicationContext) }
    }

    fun onListenerDisconnected(service: NotificationListenerService) {
        if (listener.get() === service) listener.clear()
    }

    fun onNotificationPosted(context: Context, sbn: StatusBarNotification) {
        // Do not read or retain notification content while disabled, or from other apps.
        if (!configuration.canObserve || sbn.packageName != TeaOrderDetector.TAOBAO_PACKAGE) return
        val appContext = context.applicationContext
        scope.launch { observe(appContext, sbn) }
    }

    /** Safe only before any request/tool has started. The service owns the atomic ledger claim. */
    fun retry(context: Context, eventId: String, delayMillis: Long = RETRY_DELAY_MILLIS) {
        if (eventId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<TeaOrderRetryWorker>()
            .setInputData(workDataOf(RETRY_EVENT_ID to eventId))
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RETRY_PREFIX + eventId,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    internal fun dispatch(context: Context, eventId: String): Boolean = try {
        val intent = Intent(context, ProactiveMessageTriggerService::class.java)
            .putExtra(ProactiveMessageTriggerService.EXTRA_TEA_ORDER_EVENT_ID, eventId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        true
    } catch (_: Exception) {
        Log.w(TAG, TeaOrderFailure.SERVICE_START_FAILED.name)
        false
    }

    private suspend fun scanActive(context: Context) {
        if (!configuration.canObserve) return
        val active = runCatching { listener.get()?.activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            if (!configuration.canObserve) return
            if (sbn.packageName == TeaOrderDetector.TAOBAO_PACKAGE) observe(context, sbn)
        }
    }

    private suspend fun observe(context: Context, sbn: StatusBarNotification) = notificationMutex.withLock {
        val config = configuration
        if (!config.canObserve || sbn.packageName != TeaOrderDetector.TAOBAO_PACKAGE) return@withLock
        val now = System.currentTimeMillis()
        // Active snapshots can include old purchases; do not even parse pre-activation or stale content.
        if (sbn.postTime < config.enabledSinceMillis || sbn.postTime <= 0L ||
            now - sbn.postTime > TeaOrderStateReducer.FRESH_NOTIFICATION_MILLIS || sbn.postTime > now + 60_000L
        ) return@withLock
        try {
            // A group summary can combine different orders; only evaluate individual notifications.
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@withLock
            val extras = sbn.notification.extras
            val title = listOfNotNull(
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() },
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            val detection = TeaOrderDetector.detect(
                packageName = sbn.packageName,
                title = title,
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                teaKeywords = config.keywords.toSet(),
            )
            if (detection.status == TeaOrderDetectionStatus.IGNORED || configuration != config) return@withLock
            val store = TeaOrderStore.get(context)
            val previous = store.events.value
            val recorded = store.record(
                TeaOrderCandidate(
                    packageName = sbn.packageName,
                    notificationKey = sbn.key,
                    postedAtMillis = sbn.postTime,
                    observedAtMillis = now,
                    targetAssistantId = config.assistantId.orEmpty(),
                    targetAssistantName = config.assistantName,
                    detection = detection,
                )
            )
            val event = recorded.event ?: return@withLock
            if (!recorded.shouldProcess) return@withLock
            if (configuration != config) {
                reconcilePending(context, configuration)
                return@withLock
            }
            // Repeated updates need no extra foreground-service start. Promotion from uncertain does.
            if (previous.none { it.id == event.id && it.status == TeaOrderStatus.PENDING }) {
                dispatch(context, event.id)
            }
            // Also covers a system-accepted start that never reaches the service. It cannot replay a claim.
            retry(context, event.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log notification bodies, arbitrary detector input, model text, or exception messages.
            Log.w(TAG, TeaOrderFailure.SERVICE_START_FAILED.name)
        }
    }

    private fun reconcilePending(context: Context, config: Configuration) {
        try {
            val store = TeaOrderStore.get(context)
            for (event in store.events.value) {
                if (event.status != TeaOrderStatus.PENDING || event.generationStartedAtMillis != null) continue
                val failure = when {
                    !config.enabled -> TeaOrderFailure.FEATURE_DISABLED
                    config.assistantId == null || event.targetAssistantId != config.assistantId ->
                        TeaOrderFailure.ASSISTANT_UNAVAILABLE
                    !config.canObserve || event.notificationPostedAtMillis < config.enabledSinceMillis ->
                        TeaOrderFailure.FEATURE_DISABLED
                    System.currentTimeMillis() - event.notificationPostedAtMillis >
                        TeaOrderStateReducer.FRESH_NOTIFICATION_MILLIS -> TeaOrderFailure.STALE_NOTIFICATION
                    else -> null
                }
                if (failure != null) store.markPendingFailed(event.id, failure)
                else retry(context, event.id, delayMillis = 0L)
            }
        } catch (_: Exception) {
            Log.w(TAG, TeaOrderFailure.SERVICE_START_FAILED.name)
        }
    }

    private fun configurationFor(settings: Settings): Configuration {
        val setting = settings.teaOrderSetting
        val assistant = settings.assistants.firstOrNull { it.id.toString() == setting.assistantId }
        return Configuration(
            enabled = setting.enabled,
            enabledSinceMillis = setting.enabledSinceMillis,
            assistantId = assistant?.id?.toString(),
            assistantName = assistant?.name.orEmpty(),
            keywords = setting.keywords,
        )
    }

    private data class Configuration(
        val enabled: Boolean = false,
        val enabledSinceMillis: Long = 0L,
        val assistantId: String? = null,
        val assistantName: String = "",
        val keywords: List<String> = emptyList(),
    ) {
        val canObserve: Boolean
            get() = enabled && enabledSinceMillis > 0L && assistantId != null && keywords.isNotEmpty()
    }
}
