/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.pages.setting

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.emyth.app.data.datastore.DEFAULT_TEA_ORDER_KEYWORDS
import dev.emyth.app.data.datastore.DEFAULT_TEA_ORDER_PROMPT
import dev.emyth.app.data.datastore.MAX_TEA_ORDER_KEYWORDS
import dev.emyth.app.data.datastore.MAX_TEA_ORDER_KEYWORD_LENGTH
import dev.emyth.app.data.datastore.MIN_TEA_ORDER_KEYWORD_LENGTH
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.datastore.TeaOrderSetting
import dev.emyth.app.data.datastore.effectivePrompt
import dev.emyth.app.data.service.RikkaNotificationListenerService
import dev.emyth.app.data.tea.TeaOrderEvent
import dev.emyth.app.data.tea.TeaOrderFailure
import dev.emyth.app.data.tea.TeaOrderStatus
import dev.emyth.app.data.tea.TeaOrderStore
import dev.emyth.app.ui.components.nav.BackButton
import dev.emyth.app.ui.theme.CustomColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TeaOrderEditor { KEYWORDS, PROMPT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingTeaOrdersPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val history = remember(context) {
        try {
            TeaOrderStore.get(context)
        } catch (e: Exception) {
            Log.e("TeaOrdersPage", "Failed to read tea order history", e)
            null
        }
    }
    val emptyEvents = remember { MutableStateFlow(emptyList<TeaOrderEvent>()) }
    val events by (history?.events ?: emptyEvents).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val config = settings.teaOrderSetting
    val selectedAssistant = settings.assistants.find { it.id.toString() == config.assistantId }
    val formatter = remember {
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }

    var hasAccess by remember(context) { mutableStateOf(hasTeaOrderNotificationAccess(context)) }
    var saving by remember { mutableStateOf(false) }
    var showAssistants by rememberSaveable { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf<TeaOrderEditor?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = hasTeaOrderNotificationAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun saveConfig(transform: (TeaOrderSetting) -> TeaOrderSetting) {
        if (saving || settings.init) return
        saving = true
        scope.launch {
            var message = "已保存"
            try {
                store.updateTeaOrderSetting(transform)
                editor = null
                showAssistants = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TeaOrdersPage", "Failed to save tea order settings", e)
                message = "保存失败，请稍后重试"
            } finally {
                saving = false
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("淘宝闪购茶饮提醒") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "开启后，仅从淘宝 App 的闪购订单通知中识别茶饮线索，并请你指定的助手主动发来消息。" +
                        "通知可能缺少商品信息，无法保证每次都能识别，请用实际订单通知检查效果。" +
                        "订单通知也不代表实际饮用数量。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                TeaOrderCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动提醒", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    !config.enabled -> "已关闭，不识别订单或自动告知助手"
                                    history == null -> "本机识别记录暂时不可用，无法处理提醒"
                                    selectedAssistant == null -> "请重新选择接收提醒的助手"
                                    !hasAccess -> "等待开启系统通知访问权限"
                                    else -> "已开启，等待新的淘宝闪购茶饮订单通知"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { enabled -> saveConfig { it.copy(enabled = enabled) } },
                            enabled = !saving && !settings.init &&
                                (config.enabled || history != null && selectedAssistant != null &&
                                    config.keywords.isNotEmpty()),
                        )
                    }
                    if (selectedAssistant == null) {
                        Text("先选择一个助手，再开启自动提醒。", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "接收助手：${selectedAssistant?.name?.ifBlank { "未命名助手" } ?: "尚未选择"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { showAssistants = true }, enabled = !saving && !settings.init) {
                        Text("选择助手")
                    }
                }
            }
            item {
                TeaOrderCard {
                    Text("通知访问权限", style = MaterialTheme.typography.titleMedium)
                    Text(if (hasAccess) "系统已允许访问通知" else "尚未授予通知访问权限")
                    Text(
                        "请在系统设置中允许 Emyth 访问通知。此提醒只使用淘宝 App 的通知作为来源。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (_: Exception) {
                            scope.launch { snackbar.showSnackbar("无法打开系统设置，请手动搜索“通知访问权限”") }
                        }
                    }) { Text(if (hasAccess) "查看系统设置" else "去开启") }
                }
            }
            item {
                TeaOrderCard {
                    Text("茶饮识别词", style = MaterialTheme.typography.titleMedium)
                    Text(config.keywords.joinToString("、").ifBlank { "尚未设置" }, maxLines = 3)
                    Text(
                        "品牌或商品词还需与闪购订单证据同时出现；单独出现茶饮词不会触发提醒。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        draft = config.keywords.joinToString("\n")
                        editor = TeaOrderEditor.KEYWORDS
                    }, enabled = !saving && !settings.init) { Text("编辑识别词") }
                }
            }
            item {
                TeaOrderCard {
                    Text("提醒提示词", style = MaterialTheme.typography.titleMedium)
                    Text(config.effectivePrompt, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        draft = config.effectivePrompt
                        editor = TeaOrderEditor.PROMPT
                    }, enabled = !saving && !settings.init) { Text("编辑提示词") }
                }
            }
            item {
                Text("最近识别记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "记录保存在本机，只展示识别依据和处理状态。已生成回复不代表系统通知一定送达。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (history == null) {
                item {
                    TeaOrderCard {
                        Text("本机识别记录暂时无法读取，请返回后重试。恢复前无法开启自动提醒。")
                    }
                }
            } else if (events.isEmpty()) {
                item {
                    TeaOrderCard {
                        Text("暂无记录。开启后收到新的相关通知时，识别结果会显示在这里。")
                    }
                }
            }
            items(events.sortedByDescending { it.detectedAtMillis }, key = { it.id }) { event ->
                TeaOrderEventCard(event, formatter)
            }
        }
    }

    if (showAssistants) {
        AlertDialog(
            onDismissRequest = { if (!saving) showAssistants = false },
            title = { Text("选择接收提醒的助手") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    settings.assistants.forEach { assistant ->
                        TextButton(
                            onClick = { saveConfig { it.copy(assistantId = assistant.id.toString()) } },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving,
                        ) {
                            Text(
                                assistant.name.ifBlank { "未命名助手" } +
                                    if (assistant.id.toString() == config.assistantId) "（已选择）" else "",
                            )
                        }
                    }
                    if (settings.assistants.isEmpty()) Text("还没有可用助手，请先创建助手。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAssistants = false }, enabled = !saving) { Text("返回") }
            },
        )
    }

    editor?.let { editing ->
        val words = draft.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val keywordError = when {
            words.isEmpty() -> "至少填写一个识别词"
            words.size > MAX_TEA_ORDER_KEYWORDS -> "最多填写 $MAX_TEA_ORDER_KEYWORDS 个识别词"
            words.any { it.length !in MIN_TEA_ORDER_KEYWORD_LENGTH..MAX_TEA_ORDER_KEYWORD_LENGTH } ->
                "每个识别词最少 $MIN_TEA_ORDER_KEYWORD_LENGTH 个、最多 $MAX_TEA_ORDER_KEYWORD_LENGTH 个字符"
            else -> null
        }
        val error = if (editing == TeaOrderEditor.KEYWORDS) keywordError else {
            if (draft.isBlank()) "提示词不能为空" else null
        }
        AlertDialog(
            onDismissRequest = { if (!saving) editor = null },
            title = { Text(if (editing == TeaOrderEditor.KEYWORDS) "编辑茶饮识别词" else "编辑提醒提示词") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (editing == TeaOrderEditor.KEYWORDS) "每行一个识别词" else "收到订单时给助手的提示词") },
                        minLines = 5,
                        maxLines = 12,
                        enabled = !saving,
                        isError = error != null,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        if (editing == TeaOrderEditor.KEYWORDS) {
                            "保存时会去掉空行和重复词。最多 $MAX_TEA_ORDER_KEYWORDS 项，每项最少 " +
                                "$MIN_TEA_ORDER_KEYWORD_LENGTH 个、最多 $MAX_TEA_ORDER_KEYWORD_LENGTH 个字符。"
                        } else {
                            "提醒触发时，这段提示词会与识别到的订单证据、聊天上下文一起提供给所选助手。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        draft = if (editing == TeaOrderEditor.KEYWORDS) {
                            DEFAULT_TEA_ORDER_KEYWORDS.joinToString("\n")
                        } else DEFAULT_TEA_ORDER_PROMPT
                    }, enabled = !saving) { Text("恢复默认") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveConfig {
                        if (editing == TeaOrderEditor.KEYWORDS) it.copy(keywords = words) else it.copy(prompt = draft)
                    }
                }, enabled = !saving && error == null) { Text(if (saving) "保存中…" else "保存") }
            },
            dismissButton = {
                TextButton(onClick = { editor = null }, enabled = !saving) { Text("返回") }
            },
        )
    }
}

@Composable
private fun TeaOrderCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun TeaOrderEventCard(event: TeaOrderEvent, formatter: DateTimeFormatter) {
    TeaOrderCard {
        Text(teaOrderStatusLabel(event), style = MaterialTheme.typography.titleMedium)
        Text("识别时间：${formatter.format(Instant.ofEpochMilli(event.detectedAtMillis))}")
        Text(event.reason, style = MaterialTheme.typography.bodySmall)
        if (event.matchedTeaKeywords.isNotEmpty()) {
            Text("茶饮线索：${event.matchedTeaKeywords.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
        }
        if (event.targetAssistantName.isNotBlank()) {
            Text("接收助手：${event.targetAssistantName}", style = MaterialTheme.typography.bodySmall)
        }
        if (event.status != TeaOrderStatus.OBSERVED) {
            Text(
                "状态更新：${formatter.format(Instant.ofEpochMilli(event.updatedAtMillis))}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        event.failureReason?.let { Text(teaOrderFailureLabel(it), style = MaterialTheme.typography.bodySmall) }
    }
}

private fun teaOrderStatusLabel(event: TeaOrderEvent): String = when (event.status) {
    TeaOrderStatus.OBSERVED -> "仅记录识别结果，未自动提醒"
    TeaOrderStatus.PENDING -> "已识别，等待助手处理"
    TeaOrderStatus.PROCESSING -> "助手正在处理"
    TeaOrderStatus.DELIVERED -> "已生成并保存回复"
    TeaOrderStatus.FAILED -> "未完成提醒"
}

private fun teaOrderFailureLabel(failure: TeaOrderFailure): String = when (failure) {
    TeaOrderFailure.INTERRUPTED -> "处理被中断，未确认回复完成"
    TeaOrderFailure.STALE_NOTIFICATION -> "通知时间较早，已跳过提醒"
    TeaOrderFailure.INVALID_NOTIFICATION_TIME -> "无法确认通知时间，已跳过提醒"
    TeaOrderFailure.ASSISTANT_UNAVAILABLE -> "所选助手已不可用，请重新选择助手"
    TeaOrderFailure.FEATURE_DISABLED -> "提醒已关闭"
    TeaOrderFailure.GENERATION_FAILED -> "生成回复失败，请检查模型配置或网络"
    TeaOrderFailure.CANCELLED -> "处理已取消"
    TeaOrderFailure.SERVICE_START_FAILED -> "后台处理未能启动"
    TeaOrderFailure.SESSION_BUSY -> "助手正在处理其他消息，本次未启动提醒"
    TeaOrderFailure.DELIVERY_SKIPPED -> "助手本次选择不发出提醒"
}

private fun hasTeaOrderNotificationAccess(context: Context): Boolean = try {
    val component = ComponentName(context, RikkaNotificationListenerService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.isNotificationListenerAccessGranted(component) == true
    } else {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.mapNotNull(ComponentName::unflattenFromString)
            ?.contains(component) == true
    }
} catch (_: Exception) {
    false
}
