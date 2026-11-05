/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.pages.setting

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.emyth.app.data.datastore.DEFAULT_SCHEDULED_WAKE_PROMPT
import dev.emyth.app.data.datastore.ScheduledWake
import dev.emyth.app.data.datastore.ScheduledWakeExecutionStatus
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.data.datastore.WakeRepeat
import dev.emyth.app.data.datastore.effectivePrompt
import dev.emyth.app.data.datastore.isPending
import dev.emyth.app.ui.components.nav.BackButton
import dev.emyth.app.ui.theme.CustomColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWakePromptsPage() {
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val formatter = remember(zone) { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone) }
    val wakes = settings.wakeSetting.scheduledWakes
    val orderedWakes = remember(wakes) {
        val currentTime = System.currentTimeMillis()
        val (pendingWakes, pastWakes) = wakes.partition { it.isPending(currentTime) }
        pendingWakes.sortedBy { it.triggerAtMillis } + pastWakes.sortedByDescending { it.triggerAtMillis }
    }

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var draftNote by rememberSaveable { mutableStateOf("") }
    var originalNote by rememberSaveable { mutableStateOf("") }
    var planDescription by rememberSaveable { mutableStateOf("") }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun saveContent() {
        if (saving || draft.isBlank()) return
        val id = editingId ?: return
        val note = draftNote
        val prompt = draft.takeUnless { it == DEFAULT_SCHEDULED_WAKE_PROMPT }
        saving = true
        editorError = null
        scope.launch {
            var saved = false
            try {
                saved = store.updateWakeContent(id, note, prompt)
                if (saved) {
                    editingId = null
                } else {
                    editorError = "该计划已经结束或已由 AI 取消，备注和提示词未保存。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WakePromptsPage", "Failed to save wake content", e)
                editorError = "保存失败，请稍后重试。"
            } finally {
                saving = false
            }
            if (saved) snackbar.showSnackbar("备注和提示词已保存，将用于之后的唤醒")
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("唤醒提示词") },
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
                    "这里展示 AI 通过工具设置的唤醒计划。你可以编辑唤醒备注和提示词；" +
                        "创建、调整时间和取消计划，请在聊天中与 AI 商量后由它调用工具。" +
                        "调度触发不代表回复成功，下面会单独显示最近的执行结果。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (orderedWakes.isEmpty()) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "还没有 AI 设置的唤醒计划。在聊天中让 AI 创建后，就能在这里编辑备注和提示词。",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(orderedWakes, key = { it.id }) { wake ->
                WakePromptCard(wake, now, formatter, enabled = !saving) {
                    editingId = wake.id
                    draft = wake.effectivePrompt
                    draftNote = wake.note
                    originalNote = wake.note
                    planDescription = wakePlanTitle(wake) + " · " + formatter.format(
                        Instant.ofEpochMilli(wake.triggerAtMillis)
                    )
                    editorError = null
                }
            }
        }
    }

    if (editingId != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) editingId = null },
            title = { Text("编辑唤醒内容") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(planDescription, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "编辑前的备注：${originalNote.ifBlank { "未填写" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = draftNote,
                        onValueChange = {
                            draftNote = it
                            editorError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("唤醒备注（可留空）") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !saving,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            editorError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("唤醒时给 AI 的提示词") },
                        minLines = 5,
                        maxLines = 12,
                        enabled = !saving,
                        isError = draft.isBlank() || editorError != null,
                    )
                    Text(
                        "备注和提示词只用于这条计划的后续唤醒，会连同聊天上下文一起提供给 AI。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            draft = DEFAULT_SCHEDULED_WAKE_PROMPT
                            editorError = null
                        },
                        enabled = !saving,
                    ) { Text("恢复默认提示词") }
                    editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { saveContent() }, enabled = !saving && draft.isNotBlank()) {
                    Text(if (saving) "保存中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }, enabled = !saving) { Text("返回") }
            },
        )
    }
}

@Composable
private fun WakePromptCard(
    wake: ScheduledWake,
    now: Long,
    formatter: DateTimeFormatter,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    val pending = wake.isPending(now)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(wakePlanTitle(wake), style = MaterialTheme.typography.titleMedium)
            Text(
                (if (pending) "下次唤醒：" else "计划时间：") +
                    formatter.format(Instant.ofEpochMilli(wake.triggerAtMillis)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!pending) {
                Text(
                    if (wake.lastTriggeredAtMillis > 0L) "这条一次性计划已触发调度" else "已到计划时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wake.lastTriggeredAtMillis > 0L) {
                Text(
                    "最近调度触发：" + formatter.format(Instant.ofEpochMilli(wake.lastTriggeredAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val execution = wake.execution
            if (execution != null) {
                Text(
                    "最近执行：" + if (execution.status == ScheduledWakeExecutionStatus.COMPLETED) {
                        "已生成并保存回复"
                    } else {
                        execution.status.label
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "状态更新：" + formatter.format(Instant.ofEpochMilli(execution.updatedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!execution.status.isTerminal) {
                    Text(
                        "这是最近记录的阶段，不代表当前连接仍在线。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                execution.detail?.let { detail ->
                    val safeDetail = detail.filterNot { it.isISOControl() && it != '\n' }.trim().take(240)
                    if (safeDetail.isNotEmpty()) {
                        Text(safeDetail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (execution.status == ScheduledWakeExecutionStatus.COMPLETED) {
                    Text(
                        "回复已保存；通知是否送达取决于系统通知设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (wake.lastTriggeredAtMillis > 0L) {
                Text(
                    "执行结果：无法确认。旧记录只保存了调度触发时间，未记录回复是否生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "唤醒备注：${wake.note.ifBlank { "未填写" }}",
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text("唤醒提示词", style = MaterialTheme.typography.labelMedium)
            Text(wake.effectivePrompt, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, enabled = enabled && pending) { Text("编辑备注与提示词") }
            }
        }
    }
}

private fun wakePlanTitle(wake: ScheduledWake): String =
    if (wake.repeat == WakeRepeat.DAILY) "每天 ${wake.time.orEmpty()}" else "一次性唤醒"
