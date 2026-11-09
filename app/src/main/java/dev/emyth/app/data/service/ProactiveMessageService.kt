/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.os.Build
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import dev.emyth.app.data.ai.filterInvalidProactiveToolMessages
import dev.emyth.app.data.ai.mergeProactiveMessages
import dev.emyth.app.data.ai.prepareProactiveMessages
import dev.emyth.app.data.ai.finalizeOwnedProactiveMessages
import dev.emyth.app.data.ai.finalizeProactiveGeneration
import dev.emyth.app.data.ai.guardProactiveStream
import dev.emyth.app.data.ai.ProactiveStreamTimeoutException
import dev.emyth.app.data.ai.prompts.ProactiveBuiltinPrompts
import dev.emyth.app.data.ai.prompts.appendProactivePrompt
import dev.emyth.app.data.ai.prompts.proactiveDeviceContext
import dev.emyth.app.data.ai.prompts.proactiveScheduledContext
import dev.emyth.app.data.ai.prompts.proactiveUserText
import dev.emyth.app.data.ai.transformers.InputMessageTransformer
import dev.emyth.app.data.ai.transformers.OutputMessageTransformer
import dev.emyth.app.data.ai.transformers.TemplateTransformer
import dev.emyth.app.data.ai.transformers.TimeReminderTransformer
import dev.emyth.app.data.ai.transformers.PromptInjectionTransformer
import dev.emyth.app.data.ai.transformers.PlaceholderTransformer
import dev.emyth.app.data.ai.transformers.DocumentAsPromptTransformer
import dev.emyth.app.data.ai.transformers.OcrTransformer
import dev.emyth.app.data.ai.transformers.ThinkTagTransformer
import dev.emyth.app.data.ai.transformers.Base64ImageToLocalFileTransformer
import dev.emyth.app.data.ai.transformers.RegexOutputTransformer
import dev.emyth.app.data.ai.transformers.transforms
import dev.emyth.app.data.ai.transformers.visualTransforms
import dev.emyth.app.data.ai.transformers.onGenerationFinish
import dev.emyth.app.data.ai.tools.LocalTools
import dev.emyth.app.data.ai.tools.SystemTools
import dev.emyth.app.data.ai.tools.createSearchTools
import dev.emyth.app.data.ai.tools.createSkillTools
import dev.emyth.app.data.ai.tools.ToolNaming
import dev.emyth.app.data.ai.mcp.McpManager
import dev.emyth.app.data.files.SkillManager
import dev.emyth.app.plugin.provider.PluginToolProvider
import dev.emyth.app.data.repository.MemoryRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import dev.emyth.app.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import dev.emyth.app.data.datastore.ProactiveMessageSetting
import dev.emyth.app.data.datastore.Settings
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.datastore.ScheduledWakeExecutionStatus
import dev.emyth.app.data.datastore.scheduledWakePrompt
import dev.emyth.app.data.datastore.effectivePrompt
import dev.emyth.app.data.tea.TeaOrderEvent
import dev.emyth.app.data.tea.TeaOrderFailure
import dev.emyth.app.data.tea.TeaOrderMonitor
import dev.emyth.app.data.tea.TeaOrderStore
import dev.emyth.app.data.tea.teaOrderContext
import dev.emyth.app.data.tea.teaOrderDeliveryFailure
import dev.emyth.app.data.event.AppEvent
import dev.emyth.app.data.event.AppEventBus
import dev.emyth.app.data.datastore.findProvider
import dev.emyth.app.data.datastore.getCurrentAssistant
import dev.emyth.app.data.datastore.findModelById
import dev.emyth.app.data.model.Assistant
import dev.emyth.app.RouteActivity
import dev.emyth.app.data.model.Conversation
import dev.emyth.app.data.model.toMessageNode
import dev.emyth.app.data.repository.ConversationRepository
import dev.emyth.app.service.ChatService
import dev.emyth.app.utils.sendNotification
import java.time.Instant
import java.io.IOException
import kotlin.uuid.Uuid
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class ProactiveMessageService : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()

    companion object {
        const val TAG = "ProactiveMessageService"
        const val ACTION_PROACTIVE_MESSAGE = "dev.emyth.app.PROACTIVE_MESSAGE"
        private const val REQUEST_CODE = 10001

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            if (!setting.enabled) {
                cancel(context)
                return
            }

            val minMinutes = setting.minIntervalMinutes.coerceAtLeast(1)
            val maxMinutes = setting.maxIntervalMinutes.coerceAtLeast(minMinutes)
            val delayMinutes = Random.nextInt(minMinutes, maxMinutes + 1)
            val triggerTime = java.lang.System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())

            // 保存下次触发时间到SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // Android 12+ needs canScheduleExactAlarms() check
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    } else {
                        // Fallback: use inexact alarm if exact alarm permission not granted
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.w(TAG, "Exact alarm permission not granted, using inexact alarm")
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes, trigger at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}")

            // Also schedule via WorkManager as a more reliable fallback
            ProactiveMessageWorker.scheduleNext(context, setting)
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun cancel(context: Context) {
            // 清除保存的触发时间
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "Cancelled proactive message alarm")
            }

            // Also cancel WorkManager fallback
            ProactiveMessageWorker.cancel(context)
        }

        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            // 先安排下一次（写入SP让UI立即显示），再立即触发
            scheduleNext(context, setting)
            // 立即触发：直接启动TriggerService
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }

    suspend fun buildProactiveContext(context: Context, settings: Settings): String {
        val sb = StringBuilder()
        sb.appendLine("[主动消息上下文]")

        // Time since last chat
        try {
            val lastMessageTime = getLastMessageTime()
            if (lastMessageTime != null) {
                val nowMs = java.lang.System.currentTimeMillis()
                val lastMs = lastMessageTime.toEpochMilliseconds()
                val diffMs = nowMs - lastMs
                val duration = diffMs.milliseconds
                val minutesAgo = duration.inWholeMinutes
                val hoursAgo = duration.inWholeHours
                when {
                    hoursAgo > 24 -> sb.appendLine("距离上次聊天: ${hoursAgo / 24}天${hoursAgo % 24}小时")
                    hoursAgo > 0 -> sb.appendLine("距离上次聊天: ${hoursAgo}小时${minutesAgo % 60}分钟")
                    else -> sb.appendLine("距离上次聊天: ${minutesAgo}分钟")
                }
            } else {
                sb.appendLine("距离上次聊天: 很久没有聊天了")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
        }

        // Current time
        val currentTime = java.lang.System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.appendLine("当前时间: ${sdf.format(java.util.Date(currentTime))}")

        // Location context
        try {
            val amapApiKey = settings.systemToolsSetting.amapApiKey
            if (amapApiKey.isNotBlank()) {
                val amapService = AmapService(amapApiKey)
                val locationService = LocationService(context, amapService)
                val locationResult = locationService.getCurrentLocation(amapApiKey)
                if (locationResult.isSuccess) {
                    val location = locationResult.getOrThrow()
                    val locationLine = if (location.address.isNotBlank()) {
                        "当前位置: ${location.address}"
                    } else {
                        "当前坐标: ${location.latitude}, ${location.longitude}"
                    }
                    if (!location.isFresh) {
                        val ageMinutes = location.ageMs / 60000
                        sb.appendLine("$locationLine（注意：这是大约 ${ageMinutes} 分钟前的定位，可能不是当前实时位置，不要当作用户现在就在这里）")
                    } else {
                        sb.appendLine(locationLine)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get location context", e)
        }

        // App usage
        try {
            val appUsageService = AppUsageService(context)
            val usageResult = appUsageService.getTodayUsageStats()
            if (usageResult.isSuccess) {
                val usageStats = usageResult.getOrThrow()
                if (usageStats.isNotEmpty()) {
                    sb.appendLine("今日应用使用:")
                    usageStats.take(5).forEach { stat ->
                        val minutes = stat.totalTimeInForeground / 60000
                        if (minutes > 0) {
                            sb.appendLine("  - ${stat.appName}: ${minutes}分钟")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app usage context", e)
        }

        // Foreground app
        try {
            val appUsageService = AppUsageService(context)
            val foregroundResult = appUsageService.getForegroundApp()
            if (foregroundResult.isSuccess) {
                val foregroundApp = foregroundResult.getOrThrow()
                if (foregroundApp.isNotBlank()) {
                    sb.appendLine("当前前台应用: $foregroundApp")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get foreground app", e)
        }

        // 今日通知
        try {
            val notifications = dev.emyth.app.data.service.RikkaNotificationListenerService.getTodayNotifications().take(10)
            if (notifications.isNotEmpty()) {
                sb.appendLine("今日通知（最近${notifications.size}条）:")
                notifications.forEach { notif ->
                    sb.appendLine("  - [${notif.appName}] ${notif.title}: ${notif.content.take(50)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get notifications", e)
        }

        // 设备信息（电量、充电状态等）
        try {
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                sb.appendLine("设备电量: ${pct}%${if (isCharging) "（充电中）" else ""}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery info", e)
        }

        // 健康状态（Gadgetbridge）- 跳过，AI可通过工具自行查询

        sb.appendProactivePrompt(
            settings.builtinPromptSetting.text(ProactiveBuiltinPrompts.contextRules),
            blankLinesBefore = 1,
        )
        return sb.toString()
    }


    suspend fun getLastMessageTimeMs(): Long {
        return try { getLastMessageTime()?.toEpochMilliseconds() ?: 0L } catch (e: Exception) { 0L }
    }
    private suspend fun getLastMessageTime(): kotlinx.datetime.Instant? {
        return try {
            val settings = settingsStore.settingsFlow.first()
            val assistantId = settings.assistantId
            val recentConversations = conversationRepository.getRecentConversations(assistantId, limit = 1)
            if (recentConversations.isNotEmpty()) {
                val conv = recentConversations.first()
                val fullConv = conversationRepository.getConversationById(conv.id)
                val localDateTime: LocalDateTime? = fullConv?.messageNodes?.lastOrNull()?.messages?.lastOrNull()?.createdAt
                localDateTime?.toInstant(TimeZone.currentSystemDefault())
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
            null
        }
    }
}

class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(ProactiveMessageService.TAG, "=== onReceive triggered at ${System.currentTimeMillis()}, action=${intent.action} ===")
        when (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                Log.d(ProactiveMessageService.TAG, "Starting ProactiveMessageTriggerService...")
                val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
                val note = intent.getStringExtra("scheduled_wake_note")
                if (note != null) {
                    serviceIntent.putExtra("scheduled_wake_note", note)
                }
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(ProactiveMessageService.TAG, "Boot completed, rescheduling proactive message")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                        val settings = settingsStore.settingsFlow.first()
                        val proactiveSetting = settings.proactiveMessageSetting
                        if (proactiveSetting.enabled) {
                            ProactiveMessageService.scheduleNext(context, proactiveSetting)
                        }
                    } catch (e: Exception) {
                        Log.e(ProactiveMessageService.TAG, "Failed to reschedule after boot", e)
                    }
                }
            }
        }
    }
}

class ProactiveMessageTriggerService : android.app.Service(), KoinComponent {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val activeRuns = ProactiveServiceRuns()
    private val wakeLockGuard = Any()
    private val heldWakeLocks = mutableSetOf<PowerManager.WakeLock>()
    @Volatile
    private var destroyed = false

    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val skillManager: SkillManager by inject()
    private val mcpManager: McpManager by inject()
    private val pluginToolProvider: PluginToolProvider by inject()
    private val json: Json by inject()
    private val chatService: ChatService by inject()
    private val proactiveMessageService = ProactiveMessageService()
    private val eventBus: AppEventBus by inject()

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        private const val MAX_TOOL_STEPS = 5 // 主动消息最大工具调用步数
        private const val WAKE_LOCK_SAFETY_MS = 2 * 60 * 60 * 1000L
        private const val CLEANUP_TIMEOUT_MS = 10_000L
        private const val RESCHEDULE_TIMEOUT_MS = 5_000L
        private const val STREAM_CHECKPOINT_MS = 500L
        // 外部触发（网关轮询）时跳过内部 minInterval 去重
        const val EXTRA_FORCE_TRIGGER = "force_trigger"
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        const val EXTRA_DEVICE_EVENT_CONTEXT = "device_event_context"
        // 计划唤醒（schedule_wake 工具设置的定点唤醒）触发时携带的备注
        const val EXTRA_SCHEDULED_WAKE_NOTE = "scheduled_wake_note"
        const val EXTRA_SCHEDULED_WAKE_PROMPT = "scheduled_wake_prompt"
        const val EXTRA_TEA_ORDER_EVENT_ID = "tea_order_event_id"

        // 保护 last_triggered_time 的 check-then-act 竞态（防止 AlarmManager 与 WorkManager
        // 前后脚触发导致"最小间隔"被砍半）。纯同步 SharedPreferences 读写，无挂起点，用对象锁即可。
        private val prefsLock = Any()
    }

    // 输入转换器（与 ChatService 保持一致）
    private val inputTransformers by lazy {
        listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
        )
    }

    // 输出转换器（与 ChatService 保持一致）
    private val outputTransformers by lazy {
        listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }

    private fun acquireRunWakeLock(): PowerManager.WakeLock {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Emyth:ProactiveGeneration")
        synchronized(wakeLockGuard) {
            if (destroyed) throw CancellationException("后台服务已停止")
            lock.acquire(WAKE_LOCK_SAFETY_MS)
            heldWakeLocks.add(lock)
        }
        return lock
    }

    private fun releaseRunWakeLock(lock: PowerManager.WakeLock?) {
        if (lock == null) return
        synchronized(wakeLockGuard) {
            heldWakeLocks.remove(lock)
            releaseHeldWakeLock(lock)
        }
    }

    private fun releaseHeldWakeLock(lock: PowerManager.WakeLock) {
        try {
            if (lock.isHeld) lock.release()
        } catch (e: RuntimeException) {
            // The platform's safety timeout can release the lock between isHeld and release.
            // Resource cleanup must still proceed to reference and foreground-service cleanup.
            Log.w(TAG, "Generation wake lock was already released or could not be released", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed; cancelling active proactive requests")
        destroyed = true
        serviceScope.cancel()
        synchronized(wakeLockGuard) {
            heldWakeLocks.forEach(::releaseHeldWakeLock)
            heldWakeLocks.clear()
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== TriggerService onStartCommand ===")
        // 外部触发（网关轮询/激进模式设备事件）时跳过内部 minInterval 去重
        val isForceTrigger = intent?.getBooleanExtra(EXTRA_FORCE_TRIGGER, false) ?: false
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        val deviceEventContext = intent?.getStringExtra(EXTRA_DEVICE_EVENT_CONTEXT)
        val isFromDeviceEvent = deviceEventContext != null
        val scheduledWakeId = intent?.getStringExtra(ScheduledWakeScheduler.EXTRA_WAKE_ID)
        val scheduledAt = intent?.getLongExtra(ScheduledWakeScheduler.EXTRA_TRIGGER_AT, 0L) ?: 0L
        val legacyWakeNote = intent?.getStringExtra(EXTRA_SCHEDULED_WAKE_NOTE)
        val legacyWakePrompt = intent?.getStringExtra(EXTRA_SCHEDULED_WAKE_PROMPT)
        val isScheduledWake = scheduledWakeId != null || legacyWakeNote != null
        val teaOrderEventId = intent?.getStringExtra(EXTRA_TEA_ORDER_EVENT_ID)
        val isTeaOrder = teaOrderEventId != null
        if (isForceTrigger) {
            Log.d(TAG, "Force trigger${if (isFromDeviceEvent) " from device event" else " from gateway poll"}, will skip min interval check")
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在思考...")
            .setSmallIcon(dev.emyth.app.R.drawable.small_icon)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
        val foregroundAttempt = runCatching { startForeground(20001, notification) }

        activeRuns.start(startId)
        // Acquire before handing work to IO: the receiver/Worker may release its handoff lock
        // as soon as onStartCommand returns, while a foreground service alone does not keep CPU on.
        val wakeLockAttempt = runCatching {
            foregroundAttempt.getOrThrow()
            acquireRunWakeLock()
        }
        serviceScope.launch {
            var conversationId: kotlin.uuid.Uuid? = null
            var referenceAcquired = false
            val runWakeLock = wakeLockAttempt.getOrNull()
            val ownedMessageIds = mutableSetOf<Uuid>()
            var interruption: String? = null
            var ownsScheduledWake = false
            var wakeOutcome: ScheduledWakeExecutionStatus? = null
            var wakeDetail: String? = null
            var teaStore: TeaOrderStore? = null
            var claimedTeaEvent: TeaOrderEvent? = null
            var teaFailure = TeaOrderFailure.GENERATION_FAILED
            var teaReplySaved = false
            var teaRetryQueued = false
            var teaConfigurationWatcher: Job? = null
            val teaConfigurationFailure = AtomicReference<TeaOrderFailure?>(null)

            suspend fun recordWake(status: ScheduledWakeExecutionStatus, detail: String? = null) {
                if (!ownsScheduledWake || scheduledWakeId == null) return
                try {
                    withTimeout(3_000L) {
                        ScheduledWakeScheduler(this@ProactiveMessageTriggerService, settingsStore).recordExecution(
                            scheduledWakeId, scheduledAt, status, conversationId?.toString(), detail,
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "Timed out recording scheduled wake stage", e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record scheduled wake stage", e)
                }
            }

            try {
                wakeLockAttempt.getOrThrow()
                if (teaOrderEventId != null) {
                    val store = TeaOrderStore.get(this@ProactiveMessageTriggerService)
                    teaStore = store
                    claimedTeaEvent = store.claim(teaOrderEventId) ?: return@launch
                }
                val teaEvent = claimedTeaEvent
                val acceptedWake = if (scheduledWakeId != null) {
                    // Consume/rearm only after synchronous FGS + WakeLock acquisition.
                    // The scheduler atomically rejects duplicate/obsolete alarm deliveries.
                    ScheduledWakeScheduler(this@ProactiveMessageTriggerService, settingsStore)
                        .onAlarm(scheduledWakeId, scheduledAt) ?: return@launch
                } else null
                ownsScheduledWake = acceptedWake != null
                val wakeNote = acceptedWake?.note ?: legacyWakeNote
                val wakePrompt = acceptedWake?.prompt ?: legacyWakePrompt
                recordWake(ScheduledWakeExecutionStatus.PREPARING, "正在读取聊天配置")
                val settings = if (isTeaOrder) settingsStore.settingsFlowRaw.first()
                    else settingsStore.settingsFlow.first()
                val proactiveSetting = settings.proactiveMessageSetting
                if (teaEvent != null) {
                    teaOrderDeliveryFailure(
                        teaEvent, settings.teaOrderSetting,
                        settings.assistants.mapTo(mutableSetOf()) { it.id.toString() },
                    )?.let { failure ->
                        teaFailure = failure
                        throw CancellationException("茶饮提醒已关闭或接收助手不可用")
                    }
                    val owner = currentCoroutineContext()[Job]!!
                    teaConfigurationWatcher = serviceScope.launch {
                        settingsStore.settingsFlowRaw.collect { current ->
                            val failure = teaOrderDeliveryFailure(
                                teaEvent, current.teaOrderSetting,
                                current.assistants.mapTo(mutableSetOf()) { it.id.toString() },
                            )
                            if (failure != null) {
                                teaConfigurationFailure.set(failure)
                                owner.cancel(CancellationException("茶饮提醒配置已改变"))
                            }
                        }
                    }
                }

                // 激进模式设备事件触发时，不检查主动消息开关（可独立工作）
                if (!proactiveSetting.enabled && !isFromDeviceEvent && !isScheduledWake && !isTeaOrder) {
                    return@launch
                }

                val prefs = getSharedPreferences(ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE)

                // 去重判断：防止 AlarmManager 和 WorkManager 在同一窗口内重复触发。
                // 外部触发（网关轮询/激进模式设备事件）跳过此检查，因为这是独立信号源，不受内部闹钟链约束。
                // 注意：isForceTrigger 跳过的是"时间间隔节流"（两回事），不跳过后面 tryClaimGeneration 的并发安全检查。
                // 把"读取 last_triggered_time -> 判断 -> 写入"整段放在同步块里，修复 check-then-act 竞态。
                if (!isForceTrigger && !isScheduledWake && !isTeaOrder) {
                    val skipDueToInterval = synchronized(prefsLock) {
                        val lastTriggeredTime = prefs.getLong("last_triggered_time", 0L)
                        val minIntervalMs = proactiveSetting.minIntervalMinutes.coerceAtLeast(1) * 60 * 1000L
                        if (System.currentTimeMillis() - lastTriggeredTime < minIntervalMs) {
                            true
                        } else {
                            // 立即写入触发时间，防止并发重复
                            prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                            false
                        }
                    }
                    if (skipDueToInterval) {
                        Log.d(TAG, "Duplicate trigger within min interval, skipping")
                        ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                        return@launch
                    }
                } else if (!isTeaOrder) {
                    // 强制触发也写入时间戳，保持与常规触发一致的状态记录
                    synchronized(prefsLock) {
                        prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                    }
                }

                // 获取助手
                val assistant = if (teaEvent != null) {
                    settings.assistants.find { it.id.toString() == teaEvent.targetAssistantId }
                        ?: run {
                            teaFailure = TeaOrderFailure.ASSISTANT_UNAVAILABLE
                            throw IllegalStateException("茶饮提醒所选助手已不存在")
                        }
                } else {
                    settings.assistants.find { it.id.toString() == proactiveSetting.assistantId }
                        ?: settings.getCurrentAssistant()
                }
                val assistantUuid = assistant.id
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)

                if (model == null) {
                    throw IllegalStateException("未找到此助手的聊天模型，无法生成后台消息")
                }

                // 找到最近的对话
                val recentConversationId = if (isTeaOrder) {
                    conversationRepository.getConversationsOfAssistant(assistantUuid).first()
                        .maxByOrNull { it.updateAt }?.id
                } else {
                    conversationRepository.getRecentConversations(assistantUuid, limit = 1).firstOrNull()?.id
                }
                var conversation = recentConversationId?.let { conversationRepository.getConversationById(it) }
                val selectedExistingConversation = conversation != null

                conversationId = conversation?.id ?: kotlin.uuid.Uuid.random()
                val conversationId = conversationId!!

                // 持有会话引用，防止生成期间 session 被 idle 清除（finally 块会对应 release）。
                // 放在 claim 之前：即使 claim 失败提前返回，引用也能在 finally 里被正确释放，保持计数平衡。
                // 同时把数据库里的完整对话同步到 session，防止流式更新时 conv 是空状态导致覆盖历史。
                // 主动打语音：开启时，若用户超过阈值没来找我，触发来电（不发消息）
                if (proactiveSetting.voiceCallEnabled && !isFromDeviceEvent && !isScheduledWake && !isTeaOrder) {
                    val lastMsgTimeMs = proactiveMessageService.getLastMessageTimeMs()
                    val thresholdMs = proactiveSetting.voiceCallIdleThresholdMinutes.coerceAtLeast(1) * 60_000L
                    if (lastMsgTimeMs == 0L || System.currentTimeMillis() - lastMsgTimeMs >= thresholdMs) {
                        Log.d(TAG, "主动打语音：空闲超过阈值，触发来电")
                        eventBus.emit(AppEvent.RequestVoiceCall(conversationId.toString()))
                        ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                        return@launch
                    }
                }

                chatService.addConversationReference(conversationId)
                referenceAcquired = true

                // 抢占生成权：尝试把当前协程的 Job 注册进 ConversationSession。
                // 这一步对所有触发源（含 isForceTrigger / 激进模式设备事件）一视同仁，是并发安全的核心。
                // 普通主动消息仍在繁忙时跳过；明确设置的定时唤醒则等待当前任务及收尾结束。
                // 等待发生在请求模型之前，不重放任何已经执行的模型或工具操作。
                val myJob = coroutineContext[Job]
                    ?: throw IllegalStateException("后台生成任务不存在")
                val session = chatService.getOrCreateSession(conversationId)
                val claimed = if (isScheduledWake || isTeaOrder) {
                    recordWake(ScheduledWakeExecutionStatus.PREPARING, "正在等待当前会话的回复结束（最多两分钟）")
                    session.claimGenerationWhenAvailable(myJob)
                } else {
                    session.tryClaimGeneration(myJob)
                }
                if (!claimed) {
                    if (teaEvent != null) {
                        teaFailure = TeaOrderFailure.SESSION_BUSY
                        if (teaStore?.releaseBeforeStart(teaEvent.id) == true) {
                            TeaOrderMonitor.retry(this@ProactiveMessageTriggerService, teaEvent.id)
                            teaRetryQueued = true
                        }
                    }
                    wakeOutcome = ScheduledWakeExecutionStatus.BUSY
                    wakeDetail = "当前会话持续有消息在生成，等待两分钟后结束本次唤醒，未请求模型"
                    Log.d(
                        TAG,
                        "Skip proactive trigger: session $conversationId already generating " +
                            "(normal chat or another proactive trigger in progress)"
                    )
                    // 必须走到 finally 块的"安排下一次触发"逻辑，不能绕过定时链收尾。
                    // 当前 start 退出只释放自己的资源，不停止另一条仍在运行的前台服务生成。
                    return@launch
                }

                // Refresh AFTER claiming and under the save lock. A previous request may have
                // finalized its reasoning since the first lookup; never restore that stale copy.
                chatService.getOrCreateSession(conversationId).saveMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val latest = conversationRepository.getConversationById(conversationId)
                    currentCoroutineContext().ensureActive()
                    if (selectedExistingConversation && latest == null) {
                        throw CancellationException("对话已被删除，停止此次后台生成")
                    }
                    conversation = latest
                    if (latest != null) {
                        chatService.updateConversationState(conversationId) { _ -> latest }
                    } else {
                        chatService.updateConversationState(conversationId) { current ->
                            current.copy(assistantId = assistantUuid)
                        }
                    }
                }

                // 构建上下文
                val idleMinutes = runCatching { val last = proactiveMessageService.getLastMessageTimeMs(); if (last > 0) ((System.currentTimeMillis() - last) / 60000L).toInt() else Int.MAX_VALUE }.getOrDefault(Int.MAX_VALUE)

                // 如果有设备事件上下文（激进模式），使用它替代常规上下文；否则使用常规上下文
                recordWake(ScheduledWakeExecutionStatus.PREPARING, "正在获取唤醒上下文")
                val contextStr = prepareProactiveStep("获取唤醒上下文") {
                    if (teaEvent != null) {
                        teaOrderContext(teaEvent)
                    } else if (isFromDeviceEvent && deviceEventContext != null) {
                        settings.builtinPromptSetting.proactiveDeviceContext(deviceEventContext)
                    } else {
                        val base = proactiveMessageService.buildProactiveContext(
                            this@ProactiveMessageTriggerService, settings
                        )
                        if (isScheduledWake) {
                            settings.builtinPromptSetting.proactiveScheduledContext(base, wakeNote)
                        } else {
                            base
                        }
                    }
                }

                // 获取历史消息（先过滤掉悬空的工具调用消息，避免 tool_use 结构不完整触发 400）
                val historyMessages = filterInvalidProactiveToolMessages(
                    conversation?.currentMessages?.let {
                        if (assistant.contextMessageSize > 0) {
                            it.takeLast(assistant.contextMessageSize)
                        } else it
                    } ?: emptyList()
                )

                // 构建系统提示词（包含记忆 + 上下文，都放在最后面避免被网关淹没）
                val systemPrompt = buildSystemPrompt(
                    assistant = assistant,
                    settings = settings,
                    idleMinutes = idleMinutes,
                    jumpThreshold = proactiveSetting.jumpIdleThresholdMinutes,
                    isFromDeviceEvent = isFromDeviceEvent,
                    deviceEventContext = contextStr,
                    isScheduledWake = isScheduledWake,
                    isTeaOrder = isTeaOrder,
                )

                // user message 只放简短指令（上下文已在系统提示词中）
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(
                        when {
                            teaEvent != null -> settings.teaOrderSetting.effectivePrompt
                            isFromDeviceEvent -> settings.builtinPromptSetting.proactiveUserText(true)
                            isScheduledWake -> scheduledWakePrompt(wakePrompt)
                            else -> settings.builtinPromptSetting.proactiveUserText(false)
                        }
                    ))
                )

                // 转换器可能新增时间提醒、模式或世界书消息；必须保留完整结果，不能只取第一条。
                recordWake(ScheduledWakeExecutionStatus.PREPARING, "正在应用聊天设置")
                val messages = prepareProactiveStep("应用聊天设置") {
                    prepareProactiveMessages(
                        systemPrompt = systemPrompt,
                        historyMessages = historyMessages,
                        userMessage = userMessage,
                    ) { input ->
                        input.transforms(
                            transformers = inputTransformers + templateTransformer,
                            context = this@ProactiveMessageTriggerService,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                        )
                    }
                }

                // 直接调用 AI API 生成消息
                val providerSetting = model.findProvider(settings.providers)
                if (providerSetting == null) {
                    throw IllegalStateException("未找到聊天模型的服务商配置，无法生成后台消息")
                }

                val providerImpl = providerManager.getProviderByType(providerSetting)

                // 构建工具列表（与 ChatService 保持一致）
                recordWake(ScheduledWakeExecutionStatus.PREPARING, "正在准备可用工具")
                val tools = prepareProactiveStep("准备可用工具") { buildTools(settings, assistant, model) }

                // 主动消息场景：支持工具调用，但限制最大步数
                // temperature 不强制默认 0.8f，保持与 GenerationHandler 一致（assistant.temperature 为 null 时不传），
                // 否则对智谱 GLM 等 thinking 模型会同时下发 temperature + thinking，触发 "Invalid request body" 400。
                val params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature,
                    topP = assistant.topP,
                    maxTokens = assistant.maxTokens,
                    tools = tools,
                    reasoningLevel = ReasoningLevel.OFF,  // 主动消息强制不走思考，避免思考链空转/重复上下文
                    customHeaders = buildList {
                        addAll(assistant.customHeaders)
                        addAll(model.customHeaders)
                    },
                    customBody = buildList {
                        addAll(assistant.customBodies)
                        addAll(model.customBodies)
                    }
                )

                Log.d(TAG, "Calling AI API for proactive message with ${historyMessages.size} history messages, ${tools.size} tools (reasoning=${assistant.reasoningLevel}, model=${model.modelId}, provider=${providerSetting::class.simpleName})...")
                // 诊断: 列出工具及其 parameters 是否为 null, 便于定位 "Invalid request body"
                tools.forEach { t ->
                    val hasSchema = t.parameters() != null
                    if (!hasSchema) Log.w(TAG, "Tool '${t.name}' has NULL parameters schema — may cause API rejection")
                }

                // 执行生成，支持工具调用
                if (teaEvent != null) {
                    val latestSettings = settingsStore.settingsFlowRaw.first()
                    teaOrderDeliveryFailure(
                        teaEvent, latestSettings.teaOrderSetting,
                        latestSettings.assistants.mapTo(mutableSetOf()) { it.id.toString() },
                    )?.let { failure ->
                        teaFailure = failure
                        throw CancellationException("茶饮提醒已关闭或接收助手不可用")
                    }
                    if (teaStore?.markGenerationStarted(teaEvent.id) != true) {
                        throw CancellationException("茶饮提醒记录已移除")
                    }
                }
                val (finalMessages, hasToolCalls, hasJumpFlag) = generateWithTools(
                    conversationId = conversationId,
                    providerImpl = providerImpl,
                    providerSetting = providerSetting,
                    initialMessages = messages,
                    params = params,
                    tools = tools,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    ownedMessageIds = ownedMessageIds,
                    onStage = { stage -> recordWake(stage) },
                )

                // 提取AI消息
                val aiMessage = finalMessages.lastOrNull {
                    it.role == MessageRole.ASSISTANT && it.id in ownedMessageIds
                } ?: throw IllegalStateException("主动消息没有收到有效的模型回复")

                // 解析 [JUMP] 标记（AI总是可以跳转，不需要开关）
                val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.trim()
                val replyText = rawText.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim()
                // AI总是可以跳转，不需要allowForceJump开关
                val shouldJump = hasJumpFlag

                // 若移除了标记，同步更新 session 里 aiMessage 的文本 parts
                if (rawText != replyText) {
                    val cleanedAiMessage = aiMessage.copy(
                        parts = aiMessage.parts.map { part ->
                            if (part is UIMessagePart.Text) {
                                UIMessagePart.Text(part.text.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim())
                            } else {
                                part
                            }
                        }
                    )
                    updateOrAppendAiMessage(conversationId, cleanedAiMessage, allowAppend = false)
                }

                Log.d(TAG, "Proactive message generated: '${replyText.take(100)}...' (${replyText.length} chars), hasToolCalls=$hasToolCalls, shouldJump=$shouldJump")

                if (rawText.trim().equals("[PASS]", ignoreCase = true)) {
                    // AI 选择跳过，移除本次生成的 aiMessage node（基于 id 匹配，不误删历史）
                    Log.d(ProactiveMessageService.TAG, "AI chose to skip proactive message")
                    val aiId = aiMessage.id
                    val session = chatService.getOrCreateSession(conversationId)
                    session.saveMutex.withLock {
                        val conv = chatService.getConversationFlow(conversationId).value
                        chatService.updateConversation(
                            conversationId,
                            conv.copy(
                                messageNodes = conv.messageNodes.filterNot { node ->
                                    node.messages.any { it.id == aiId }
                                }
                            )
                        )
                        chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)
                    }
                    wakeOutcome = ScheduledWakeExecutionStatus.SKIPPED
                    wakeDetail = "模型返回了完整的 [PASS]，本次未发送回复"
                    teaFailure = TeaOrderFailure.DELIVERY_SKIPPED
                } else {
                    // An EOF containing only reasoning/tool results is not an intentional skip.
                    // Preserve those checkpoints and report the incomplete reply instead of deleting them.
                    check(replyText.isNotBlank()) {
                        "后台生成没有返回最终回复，已保留收到的内容和工具结果"
                    }
                    // 有效回复：session 里已有 aiMessage（流式过程已追加），持久化并发通知
                    saveProactiveMessage(
                        settings, assistant, conversationId, conversation
                    )
                    teaReplySaved = teaEvent != null
                    if (teaEvent != null) {
                        try {
                            teaStore?.markDelivered(teaEvent.id, conversationId.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to record saved tea-order reply", e)
                        }
                    }
                    wakeOutcome = ScheduledWakeExecutionStatus.COMPLETED
                    // Notify as soon as the local reply is durable. Optional remote-memory IO
                    // must not delay the user's notification or hide a rejected notification.
                    wakeDetail = notifySavedProactiveReply(conversationId, assistant.name.ifBlank { "AI" }, replyText)
                    recordWake(ScheduledWakeExecutionStatus.COMPLETED, wakeDetail)
                    // 悬浮球提醒（作为通知之上的增强层；无 overlay 权限时由 FloatingBubbleService 兜底跳过）
                    if (proactiveSetting.floatingBubbleEnabled) {
                        try {
                            FloatingBubbleService.show(
                                context = this@ProactiveMessageTriggerService,
                                conversationId = conversationId.toString(),
                                senderName = assistant.name.ifBlank { "AI" },
                                avatar = assistant.avatar,
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to display proactive floating bubble", e)
                        }
                    }
                    // 强制跳转屏幕到聊天界面（方案 A：普通拉起前台）
                    if (shouldJump) {
                        try {
                            val jumpIntent = Intent(this@ProactiveMessageTriggerService, RouteActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                putExtra("conversationId", conversationId.toString())
                            }
                            startActivity(jumpIntent)
                            Log.d(TAG, "Force jump to conversation $conversationId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Force jump failed", e)
                        }
                    }
                    // 同步保存 AI 主动消息 / 激进模式回复到外置记忆库（Supabase）
                    // 保证日记总结（DiarySummaryService 只读 Supabase chat_messages 表）和记忆召回能看到这部分内容
                    try {
                        val externalMemoryConfigs = settings.externalMemories.filter {
                            it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                        }
                        if (externalMemoryConfigs.isNotEmpty() && replyText.isNotBlank()) {
                            kotlinx.coroutines.coroutineScope {
                                externalMemoryConfigs.forEach { config ->
                                    launch {
                                        runCatching {
                                            val service = ExternalMemoryService(config)
                                            service.saveMessage(
                                                assistantId = assistant.id.toString(),
                                                conversationId = conversationId.toString(),
                                                role = "assistant",
                                                content = replyText,
                                            ).getOrThrow()
                                        }.onFailure {
                                            if (it is CancellationException) throw it
                                            Log.w(
                                                ProactiveMessageService.TAG,
                                                "Failed to save proactive message to external memory ${config.name}",
                                                it
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(ProactiveMessageService.TAG, "Failed to save proactive message to external memory", e)
                    }
                }
            } catch (e: CancellationException) {
                teaFailure = teaConfigurationFailure.get() ?: teaFailure.let {
                    if (it == TeaOrderFailure.GENERATION_FAILED) TeaOrderFailure.CANCELLED else it
                }
                if (wakeOutcome != ScheduledWakeExecutionStatus.COMPLETED) {
                    wakeOutcome = ScheduledWakeExecutionStatus.INTERRUPTED
                    wakeDetail = if (destroyed) "后台服务停止，本次生成中断" else "本次生成被新消息或停止操作中断"
                }
                interruption = if (destroyed) "后台服务已停止，生成已中断" else "生成已被新消息或停止操作中断"
                // 协程被取消（通常是用户发了新消息，sendMessage 里 session.getJob()?.cancel() 触发），
                // 这是正常情况，不应当成错误。打印 debug 日志并重新抛出，遵循 Kotlin 协程取消传播语义。
                // 注意 catch 顺序：CancellationException 必须在 Exception 之前，否则被泛化分支提前吃掉。
                // 重新抛出后 finally 块仍会正常执行（scheduleNext 已用 NonCancellable 保护）。
                Log.d(
                    ProactiveMessageService.TAG,
                    "Proactive generation cancelled (likely user started a new message), " +
                        "conversationId=$conversationId"
                )
                throw e
            } catch (e: Exception) {
                if (scheduledWakeId != null && wakeLockAttempt.isFailure) {
                    try {
                        withTimeout(3_000L) {
                            ScheduledWakeScheduler(this@ProactiveMessageTriggerService, settingsStore).recordExecution(
                                scheduledWakeId, scheduledAt, ScheduledWakeExecutionStatus.START_FAILED,
                                detail = "后台服务或唤醒保护未能启动（${e.javaClass.simpleName}）",
                            )
                        }
                    } catch (failure: Exception) {
                        Log.w(TAG, "Failed to record service startup failure", failure)
                    }
                }
                if (wakeOutcome == ScheduledWakeExecutionStatus.COMPLETED) {
                    wakeDetail = wakeDetail?.let { "$it；后续处理未完成" }
                        ?: "回复已保存，但后续通知或同步未能完成"
                } else {
                    wakeOutcome = ScheduledWakeExecutionStatus.FAILED
                    wakeDetail = when (e) {
                        is ProactiveStreamTimeoutException -> e.message
                        is ProactivePreparationTimeoutException -> e.message
                        else -> "后台生成未完成（${e.javaClass.simpleName}），请查看聊天页面的错误详情"
                    }
                }
                interruption = if (e is ProactiveStreamTimeoutException) {
                    e.message ?: "后台请求长时间没有进展，生成已中断"
                } else {
                    "后台请求中断，未完成的输出已保留"
                }
                chatService.addError(e, conversationId, title = "主动消息或定时唤醒未能完成")
                Log.e(ProactiveMessageService.TAG, "Failed to trigger proactive message", e)
                // 如果是 API 返回的 HTTP 错误, 把原始错误体也打出来便于定位
                val cause = e.cause
                if (cause != null) {
                    Log.e(ProactiveMessageService.TAG, "Underlying cause: ${cause::class.simpleName}: ${cause.message}", cause)
                }
            } finally {
                teaConfigurationWatcher?.cancel()
                // 确保无论成功/失败/取消都安排下一次，避免一次 API 错误或用户打断永久中断定时链。
                // 激进模式设备事件触发时不需要安排下一次定时主动消息（由 DeviceEventAiTriggerService 自己驱动）。
                // 用 NonCancellable 包裹：协程被取消后处于已取消状态，finally 里的挂起点
                // (settingsFlow.first()) 会立刻抛 CancellationException，导致 scheduleNext 被跳过、
                // 定时链断裂。NonCancellable 保证这段收尾逻辑跑完。
                withContext(NonCancellable) {
                    try {
                        claimedTeaEvent?.let { event ->
                            try {
                                if (teaReplySaved) teaStore?.markDelivered(event.id, conversationId?.toString())
                                else if (!teaRetryQueued) teaStore?.markFailed(event.id, teaFailure)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to finalize tea-order delivery", e)
                            }
                        }
                        if (ownsScheduledWake) {
                            recordWake(
                                wakeOutcome ?: ScheduledWakeExecutionStatus.INTERRUPTED,
                                wakeDetail ?: "本次唤醒未完成，未确认已保存回复",
                            )
                        }
                        try {
                            withTimeout(CLEANUP_TIMEOUT_MS) {
                                conversationId?.let { cid ->
                                    if (ownedMessageIds.isNotEmpty()) {
                                        val session = chatService.getOrCreateSession(cid)
                                        session.saveMutex.withLock {
                                            val current = chatService.getConversationFlow(cid).value
                                            val finalized = current.finalizeOwnedProactiveMessages(
                                                ownedMessageIds,
                                                kotlin.time.Clock.System.now(),
                                                interruption,
                                            )
                                            if (finalized != current) chatService.updateConversation(cid, finalized)
                                            chatService.saveConversation(cid, finalized)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to finalize proactive generation", e)
                        }
                        if (!isFromDeviceEvent && !isTeaOrder && wakeLockAttempt.isSuccess) {
                            try {
                                withTimeout(RESCHEDULE_TIMEOUT_MS) {
                                    val currentSettings = settingsStore.settingsFlow.first()
                                    ProactiveMessageService.scheduleNext(
                                        this@ProactiveMessageTriggerService,
                                        currentSettings.proactiveMessageSetting
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to reschedule proactive generation", e)
                            }
                        }
                    } finally {
                        if (referenceAcquired) conversationId?.let { chatService.removeConversationReference(it) }
                        releaseRunWakeLock(runWakeLock)
                        withContext(Dispatchers.Main.immediate) {
                            val stopId = activeRuns.finish(startId)
                            if (!destroyed && stopId != null && activeRuns.canStop(stopId)) stopSelfResult(stopId)
                        }
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    /**
     * 构建系统提示词，包含记忆等内容
     * isFromDeviceEvent: 是否由激进模式设备事件触发
     */
    private suspend fun buildSystemPrompt(
        assistant: Assistant,
        settings: Settings,
        idleMinutes: Int = 0,
        jumpThreshold: Int = 120,
        isFromDeviceEvent: Boolean = false,
        deviceEventContext: String? = null,
        isScheduledWake: Boolean = false,
        isTeaOrder: Boolean = false,
    ): String {
        return buildString {
            // 基础系统提示词
            val effectiveSystemPrompt = if (assistant.allowConversationSystemPrompt) {
                assistant.systemPrompt
            } else {
                assistant.systemPrompt
            }
            if (effectiveSystemPrompt.isNotBlank()) {
                append(effectiveSystemPrompt)
            }

            // 记忆（设备事件上下文移到最后面，避免被网关注入的内容淹没）
            if (assistant.enableMemory) {
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    // 主动消息：限制记忆条数与每条长度，避免上下文过长导致生成变慢
                    val limitedMemories = memories.sortedByDescending { it.id }.take(15)
                    appendLine()
                    appendLine()
                    appendLine("## 记忆（最近 ${limitedMemories.size} 条，已精简）")
                    limitedMemories.forEach { memory ->
                        appendLine("- ${memory.content.take(300)}")
                    }
                }
            }

            if (isFromDeviceEvent) {
                // 激进模式设备事件触发的专用提示词 + 设备事件上下文（放在最后面，网关追加内容之后模型最后看到的就是这个）
                appendProactivePrompt(
                    settings.builtinPromptSetting.text(
                        ProactiveBuiltinPrompts.deviceTriggerRules,
                        mapOf("idle_minutes" to idleMinutes.toString()),
                    ),
                    blankLinesBefore = 2,
                )
                // 直接注入设备事件上下文
                if (!deviceEventContext.isNullOrBlank()) {
                    appendLine()
                    appendLine(deviceEventContext)
                }
            } else {
                // 定时唤醒使用计划自身的提示词；不要注入允许随机主动消息跳过的规则。
                if (!isScheduledWake && !isTeaOrder) appendProactivePrompt(
                    settings.builtinPromptSetting.text(
                        ProactiveBuiltinPrompts.timerTriggerRules,
                        mapOf("idle_minutes" to idleMinutes.toString()),
                    ),
                    blankLinesBefore = 2,
                )
                // 注入完整上下文（定位、前台app、app使用、通知、电量、健康等）
                if (!deviceEventContext.isNullOrBlank()) {
                    appendLine()
                    appendLine(deviceEventContext)
                }
            }
        }
    }

    /**
     * 保存主动消息到对话历史
     * 同时保存用户上下文消息和AI回复，以便AI下次触发时能看到之前的上下文
     */
    private suspend fun saveProactiveMessage(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
        existingConversation: Conversation?
    ): Uuid {
        val assistantUuid = assistant.id

        // 确保对话存在于数据库（新建时 insert）。
        // 若 session 为空且没有已存对话，先 insert 一条空对话占位，避免后续 saveConversation 跳过。
        if (existingConversation == null) {
            val session = chatService.getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                val exists = conversationRepository.existsConversationById(conversationId)
                if (!exists) {
                    val newConversation = Conversation(
                        id = conversationId,
                        assistantId = assistantUuid,
                        title = "",
                        messageNodes = emptyList()
                    )
                    conversationRepository.insertConversation(newConversation)
                }
            }
        }

        // 流式过程中已实时追加 aiMessage 到 session，这里直接持久化当前 session 状态。
        // 加 saveMutex 防止与用户发送消息/其他主动消息并发覆盖。
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            chatService.saveConversation(
                conversationId,
                chatService.getConversationFlow(conversationId).value
            )
        }

        Log.d(TAG, "Saved proactive message to conversation $conversationId")
        return conversationId
    }

    private fun notifySavedProactiveReply(conversationId: Uuid, senderName: String, message: String): String {
        val attemptedAt = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        )
        val prefix = "回复已保存；$attemptedAt "
        return try {
            val manager = NotificationManagerCompat.from(this)
            val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.getNotificationChannel(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            } else null
            when {
                !manager.areNotificationsEnabled() -> prefix + "应用通知已被系统关闭，未提交提醒"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && channel == null ->
                    prefix + "聊天通知类别不存在，未提交提醒"
                channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE ->
                    prefix + "聊天通知类别已关闭，未提交提醒"
                showProactiveNotification(conversationId, senderName, message) ->
                    prefix + "已向系统提交通知；锁屏展示仍取决于系统设置"
                else -> prefix + "通知权限检查未通过，未提交提醒"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post proactive notification", e)
            prefix + "提交通知失败（${e.javaClass.simpleName}）"
        }
    }

    private fun showProactiveNotification(
        conversationId: kotlin.uuid.Uuid,
        senderName: String,
        message: String
    ): Boolean {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 20002
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    /**
     * 构建工具列表（主动消息场景精简版）
     * 只加载系统工具 + 本地工具 + MCP 工具 + 插件工具，不加载搜索/Skill 工具，
     * 避免工具过多导致请求体过大触发 API 400。
     */
    private suspend fun buildTools(settings: Settings, assistant: Assistant, model: Model): List<Tool> {
        return buildList {
            // 本地工具（助手已启用的）
            addAll(localTools.getTools(assistant.localTools))

            // 系统工具（位置、通知、日历、闹钟、相机）
            val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions()
            if (systemToolsOptions.isNotEmpty()) {
                val systemTools = SystemTools(this@ProactiveMessageTriggerService, settings)
                addAll(systemTools.getTools(systemToolsOptions))
            }

            // MCP 工具
            mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
                add(
                    Tool(
                        name = ToolNaming.buildMcpToolName(serverId, tool.name),
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = tool.needsApproval,
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }

            // 插件工具
            addAll(pluginToolProvider.getTools())
        }
    }

    /**
     * 基于 AI 消息 id 在对话里就地更新（保留 MessageNode.id，避免 Compose 重建/状态丢失）
     * 或追加新 node。不使用 toMessageNode() 生成随机新 id，也不用 dropLast(1) 盲目删除。
     *
     * 注意：这里必须走 saveMutex 保护，因为流式更新与 ChatService.sendMessage/addProactiveMessage
     * 可能并发修改同一会话，read-modify-write 不加锁会导致后写入者覆盖前者。
     */
    private suspend fun updateOrAppendAiMessage(
        conversationId: Uuid,
        aiMessage: UIMessage,
        allowAppend: Boolean = true,
    ) {
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            currentCoroutineContext().ensureActive()
            val conv = chatService.getConversationFlow(conversationId).value
            val existingNodeIndex = conv.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == aiMessage.id }
            }
            val updated = if (existingNodeIndex >= 0) {
                // 已存在该 id 的 node：保留 node id，只更新其 messages
                val oldNode = conv.messageNodes[existingNodeIndex]
                val updatedNode = oldNode.copy(
                    messages = oldNode.messages.map {
                        if (it.id == aiMessage.id) aiMessage else it
                    }
                )
                conv.copy(
                    messageNodes = conv.messageNodes.toMutableList().apply {
                        this[existingNodeIndex] = updatedNode
                    }
                )
            } else {
                // A later snapshot must not resurrect a message the user deleted while generating.
                if (!allowAppend) return@withLock
                // 本次生成的 node 还没有：追加（首次调用时才创建新 node）
                conv.copy(messageNodes = conv.messageNodes + aiMessage.toMessageNode())
            }
            chatService.updateConversation(conversationId, updated)
            chatService.saveConversation(conversationId, updated)
        }
    }

    /** Preserve known tool results before moving to another possibly slow or cancelled tool. */
    private suspend fun checkpointCompletedTools(
        conversationId: Uuid,
        messageId: Uuid,
        executedTools: List<UIMessagePart.Tool>,
    ) {
        withContext(NonCancellable) {
            val saved = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                val session = chatService.getOrCreateSession(conversationId)
                session.saveMutex.withLock {
                    val current = chatService.getConversationFlow(conversationId).value
                    val nodes = current.messageNodes.map { node ->
                        val updatedMessages = node.messages.map messageLoop@{ message ->
                            if (message.id != messageId) return@messageLoop message
                            message.copy(parts = message.parts.map { part ->
                                if (part is UIMessagePart.Tool) {
                                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                                } else part
                            })
                        }
                        if (updatedMessages == node.messages) node else node.copy(messages = updatedMessages)
                    }
                    if (nodes != current.messageNodes) {
                        val updated = current.copy(messageNodes = nodes)
                        chatService.updateConversation(conversationId, updated)
                        chatService.saveConversation(conversationId, updated)
                    }
                }
                true
            }
            if (saved == null) throw IOException("工具结果未能及时保存，后台生成已中断")
        }
        // A new user request may have cancelled this run while its known result was being saved.
        currentCoroutineContext().ensureActive()
    }

    /**
     * 生成消息，支持工具调用
     * 返回最终消息列表和是否发生了工具调用
     */
    private suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: me.rerere.ai.provider.Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings,
        ownedMessageIds: MutableSet<Uuid>,
        onStage: suspend (ScheduledWakeExecutionStatus) -> Unit,
    ): Triple<List<UIMessage>, Boolean, Boolean> {
        var messages = initialMessages.toMutableList()
        var hasToolCalls = false
        var hasJumpFlag = false // AI 原始输出是否含 [JUMP] 标记（在输出转换器处理前检测）
        val initialMessageIds = initialMessages.mapTo(mutableSetOf()) { it.id }
        val publishedMessageIds = mutableSetOf<Uuid>()

        for (step in 0 until MAX_TOOL_STEPS) {
            Log.d(TAG, "generateWithTools: step $step/${MAX_TOOL_STEPS}")

            // 防御性：每轮调用前合并相邻同角色（尤其 assistant）消息，
            // 避免工具调用多步生成产生相邻 assistant 消息触发 API 400
            messages = mergeProactiveMessages(messages).toMutableList()

            // 流式调用 AI（替代非流式 generateText，兼容 thinking 模型）
            var streamMessages = messages.toList()
            var streamingRecorded = false
            onStage(ScheduledWakeExecutionStatus.REQUESTING)
            providerImpl.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = params
            ).guardProactiveStream(isProgress = { chunk ->
                chunk.choices.any { choice ->
                    (choice.delta ?: choice.message)?.parts?.any { part ->
                        when (part) {
                            is UIMessagePart.Text -> part.text.isNotEmpty()
                            is UIMessagePart.Reasoning -> part.reasoning.isNotEmpty() || part.metadata != null
                            is UIMessagePart.Tool -> part.input.isNotEmpty() ||
                                part.toolName.isNotEmpty() || part.toolCallId.isNotEmpty()
                            else -> true
                        }
                    } == true
                }
            }).map { chunk ->
                // Merge every delta BEFORE conflation: only complete snapshots may be dropped.
                streamMessages = streamMessages.handleMessageChunk(chunk = chunk, model = model)
                streamMessages.lastOrNull {
                    it.role == MessageRole.ASSISTANT && it.id !in initialMessageIds
                }?.also { ownedMessageIds.add(it.id) }
            }.filterNotNull().checkpointProactiveSnapshots(STREAM_CHECKPOINT_MS).collect { currentAiMessage ->
                updateOrAppendAiMessage(
                    conversationId,
                    currentAiMessage,
                    allowAppend = publishedMessageIds.add(currentAiMessage.id),
                )
                if (!streamingRecorded) {
                    onStage(ScheduledWakeExecutionStatus.STREAMING)
                    streamingRecorded = true
                }
            }

            // 流式结束，更新 messages
            messages = streamMessages.toMutableList()
            val aiMessage = streamMessages.lastOrNull {
                it.role == MessageRole.ASSISTANT && it.id in ownedMessageIds
            } ?: throw IllegalStateException("主动消息没有收到有效的模型回复")


            // 在输出转换器处理前，检测 AI 原始输出是否含 [JUMP] 标记
            val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (rawAiText.contains("[JUMP]")) {
                hasJumpFlag = true
                Log.d(TAG, "[JUMP] flag detected in raw AI output")
            }
            // 应用输出转换器
            val processedMessage = listOf(aiMessage).transforms(
                transformers = outputTransformers,
                context = this@ProactiveMessageTriggerService,
                model = model,
                assistant = assistant,
                settings = settings
            ).onGenerationFinish(
                transformers = outputTransformers,
                context = this@ProactiveMessageTriggerService,
                model = model,
                assistant = assistant,
                settings = settings,
            ).first().finishReasoning()
            messages[messages.lastIndex] = processedMessage
            // A model round is over even while its requested tools are still running.
            updateOrAppendAiMessage(conversationId, processedMessage, allowAppend = false)

            // 检查是否有工具调用
            val toolCalls = processedMessage.getTools().filter { !it.isExecuted }

            if (toolCalls.isEmpty()) {
                // 没有工具调用，生成完成
                // 设置 Reasoning 的 finishedAt，否则UI会一直显示"思考中"
                val finalMessage = processedMessage.finalizeProactiveGeneration(kotlin.time.Clock.System.now())
                messages[messages.lastIndex] = finalMessage
                // 最终更新 session 状态（用 id 匹配就地更新）
                updateOrAppendAiMessage(conversationId, finalMessage, allowAppend = false)
                break
            }

            // 有工具调用
            hasToolCalls = true
            onStage(ScheduledWakeExecutionStatus.RUNNING_TOOLS)
            Log.d(TAG, "Tool calls detected: ${toolCalls.size}")

            // 执行工具（后台模式下自动执行，不需要用户审批）
            val executedTools = mutableListOf<UIMessagePart.Tool>()
            for (toolCall in toolCalls) {
                val toolDef = tools.find { it.name == toolCall.toolName }
                if (toolDef == null) {
                    Log.w(TAG, "Tool ${toolCall.toolName} not found")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool not found"}"""))
                    ))
                    checkpointCompletedTools(conversationId, processedMessage.id, executedTools)
                    continue
                }

                // 检查是否需要审批
                if (toolDef.needsApproval && !settings.autoApproveAllTools) {
                    // 后台模式下，需要审批的工具自动拒绝
                    Log.w(TAG, "Tool ${toolCall.toolName} needs approval, auto-denying in proactive mode")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool execution denied: requires user approval in proactive mode"}""")),
                        approvalState = ToolApprovalState.Denied("Proactive mode: requires approval")
                    ))
                } else {
                    // 执行工具
                    try {
                        val args = try {
                            json.parseToJsonElement(toolCall.input.ifBlank { "{}" })
                        } catch (e: Exception) {
                            // toolCall.input 可能因为流式截断而是不完整的 JSON, 回退为空对象
                            Log.w(TAG, "Tool ${toolCall.toolName} input JSON is incomplete, falling back to empty object: ${toolCall.input.take(200)}")
                            JsonObject(emptyMap())
                        }
                        Log.d(TAG, "Executing tool ${toolDef.name} with args: $args")
                        val result = toolDef.execute(args)
                        executedTools.add(toolCall.copy(output = result))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed: ${toolCall.toolName}, args=${toolCall.input}", e)
                        val error = JsonObject(mapOf(
                            "error" to kotlinx.serialization.json.JsonPrimitive(e.message ?: "Tool execution failed")
                        ))
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text(error.toString()))
                        ))
                    }
                }
                checkpointCompletedTools(conversationId, processedMessage.id, executedTools)
            }

            // 更新消息中的工具状态
            val updatedParts = processedMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            val updatedMessage = processedMessage.copy(parts = updatedParts)
            messages[messages.lastIndex] = updatedMessage
            // 更新 session 状态（带工具结果的消息，用 id 匹配就地更新）
            updateOrAppendAiMessage(conversationId, updatedMessage, allowAppend = false)
        }

        return Triple(messages, hasToolCalls, hasJumpFlag)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
