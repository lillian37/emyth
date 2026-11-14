/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.emyth.app.data.editprotection.EditProtectionEvent
import dev.emyth.app.data.editprotection.EditProtectionEventKind
import dev.emyth.app.data.editprotection.EditProtectionStatus
import dev.emyth.app.data.editprotection.EditProtectionStore
import dev.emyth.app.data.editprotection.ProtectedEditScope
import dev.emyth.app.ui.components.ui.CardGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun rememberEditProtectionStatus(assistantId: String): EditProtectionStatus {
    val context = LocalContext.current
    val store = remember(context) { EditProtectionStore.get(context) }
    val owner = LocalLifecycleOwner.current
    var status by remember(store, assistantId) { mutableStateOf(readEditStatus(store, assistantId)) }
    LaunchedEffect(store, assistantId, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                status = readEditStatus(store, assistantId)
                delay(1_000L)
            }
        }
    }
    return status
}

@Composable
internal fun rememberEditPermissionRequest(
    assistantId: String,
    editScope: ProtectedEditScope,
    conversationId: String? = null,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("编辑权限") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
    return {
        scope.launch {
            try {
                val store = EditProtectionStore.get(context)
                store.requestHumanEdit(assistantId, editScope, conversationId)
                message = if (store.status(assistantId).editingAllowed) {
                    "编辑权限现已开放，可以重新进入编辑。"
                } else {
                    "编辑申请已记录，将尝试唤醒助手；授权后可编辑 15 分钟。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "编辑申请未能记录，请稍后重试。"
            }
        }
    }
}

@Composable
internal fun EditProtectionNotice(
    assistantId: String,
    editScope: ProtectedEditScope,
    modifier: Modifier = Modifier,
    onRequest: (() -> Unit)? = null,
) {
    val status = rememberEditProtectionStatus(assistantId)
    val request = rememberEditPermissionRequest(assistantId, editScope)
    if (!status.enabled) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (status.editingAllowed) {
                "编辑权限剩余 ${formatEditWindow(status.remainingMillis)}，保存时会再次检查权限"
            } else {
                "编辑保护已锁定；可查看内容，修改需要助手授权"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!status.editingAllowed) {
            TextButton(onClick = onRequest ?: request) { Text("请求编辑权限") }
        }
    }
}

@Composable
internal fun AssistantEditProtectionCard(assistantId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { EditProtectionStore.get(context) }
    val status = rememberEditProtectionStatus(assistantId)
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var events by remember(store, assistantId) { mutableStateOf(readEditEvents(store, assistantId)) }
    var showRecovery by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store, assistantId, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                events = readEditEvents(store, assistantId)
                delay(2_000L)
            }
        }
    }
    CardGroup(modifier = modifier) {
        item(
            headlineContent = { Text("编辑保护") },
            supportingContent = { Text("保护此助手的人设、会话提示词和内置记忆；不影响助手自己的记忆工具") },
            trailingContent = {
                Switch(
                    checked = status.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            try {
                                store.setEnabled(assistantId, enabled)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "未能更新编辑保护，请稍后重试。"
                            }
                        }
                    },
                )
            },
        )
        if (status.enabled) {
            item(
                headlineContent = {
                    Text(if (status.editingAllowed) "临时编辑已开放" else "已锁定")
                },
                supportingContent = {
                    Text(
                        if (status.editingAllowed) {
                            "剩余 ${formatEditWindow(status.remainingMillis)}；实际修改会记录给助手"
                        } else {
                            "请在聊天中协商，由助手开放 15 分钟编辑权限"
                        }
                    )
                },
            )
            item(
                headlineContent = { Text("紧急恢复编辑") },
                supportingContent = { Text("本地临时开放 15 分钟，留下记录并尝试唤醒助手") },
                onClick = { showRecovery = true },
            )
        }
        if (events.isNotEmpty()) {
            item(
                headlineContent = { Text("最近编辑记录") },
                supportingContent = { Text(events.first().summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text("查看") },
                onClick = { showHistory = true },
            )
        }
    }
    if (showRecovery) {
        AlertDialog(
            onDismissRequest = { showRecovery = false },
            title = { Text("紧急恢复编辑 15 分钟") },
            text = { Text("将本地临时开放编辑权限，并保留恢复记录、尝试唤醒助手。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            store.emergencyRecover(assistantId)
                            showRecovery = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "未能恢复编辑，请稍后重试。"
                        }
                    }
                }) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { showRecovery = false }) { Text("取消") } },
        )
    }
    if (showHistory) EditProtectionHistoryDialog(events) { showHistory = false }
    error?.let { text ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("编辑保护") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun EditProtectionHistoryDialog(events: List<EditProtectionEvent>, onDismiss: () -> Unit) {
    val format = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑记录") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(events.take(8), key = { it.id }) { event ->
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${event.kind.label()} · ${format.format(Date(event.atMillis))}")
                        Text(event.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun formatEditWindow(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
    return "${seconds / 60}分${seconds % 60}秒"
}

private fun readEditStatus(store: EditProtectionStore, assistantId: String): EditProtectionStatus =
    runCatching { store.status(assistantId) }.getOrElse {
        // A failed state read cannot make locked fields editable or crash their containing screen.
        EditProtectionStatus(
            enabled = true,
            editingAllowed = false,
            grantedUntilMillis = null,
            remainingMillis = 0L,
            hasPendingWake = false,
        )
    }

private fun readEditEvents(store: EditProtectionStore, assistantId: String): List<EditProtectionEvent> =
    runCatching { store.events(assistantId) }.getOrDefault(emptyList())

private fun EditProtectionEventKind.label(): String = when (this) {
    EditProtectionEventKind.ENABLED -> "启用保护"
    EditProtectionEventKind.DISABLED -> "关闭保护"
    EditProtectionEventKind.GRANTED -> "助手授权"
    EditProtectionEventKind.REVOKED -> "收回授权"
    EditProtectionEventKind.RECOVERED -> "紧急恢复"
    EditProtectionEventKind.BLOCKED -> "已拦截"
    EditProtectionEventKind.CHANGE_PENDING -> "保存待确认"
    EditProtectionEventKind.CHANGED -> "已修改"
    EditProtectionEventKind.CHANGE_FAILED -> "保存失败"
}
