/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.pages.setting

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.emyth.app.RouteActivity
import dev.emyth.app.data.service.ProactiveExecution
import dev.emyth.app.data.service.ProactiveExecutionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun ProactiveExecutionStatus.displayText(): String = when (this) {
    ProactiveExecutionStatus.PREPARING -> "正在准备"
    ProactiveExecutionStatus.REQUESTING -> "正在请求回复"
    ProactiveExecutionStatus.STREAMING -> "正在接收回复"
    ProactiveExecutionStatus.RUNNING_TOOLS -> "正在使用工具"
    ProactiveExecutionStatus.SKIPPED -> "AI 选择跳过"
    ProactiveExecutionStatus.BUSY -> "会话忙，本轮跳过"
    ProactiveExecutionStatus.VOICE_CALL -> "已请求语音来电"
    ProactiveExecutionStatus.COMPLETED -> "回复已保存"
    ProactiveExecutionStatus.FAILED -> "执行失败"
    ProactiveExecutionStatus.INTERRUPTED -> "执行已中断"
    ProactiveExecutionStatus.START_FAILED -> "后台启动失败"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProactiveExecutionHistorySheet(
    executions: List<ProactiveExecution>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("主动消息记录", style = MaterialTheme.typography.titleLarge)
                Text(
                    "最近 ${executions.size} 次 · 点按记录查看详情",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            items(executions, key = { it.id }) { execution ->
                val expanded = expandedId == execution.id
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { expandedId = if (expanded) null else execution.id }
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(execution.status.displayText(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            timeFormat.format(Date(execution.startedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        if (expanded) "收起" else "详情",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "计划时间：" + (execution.scheduledAt?.let { timeFormat.format(Date(it)) } ?: "未记录"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "最近更新：${timeFormat.format(Date(execution.updatedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        execution.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        execution.conversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(context, RouteActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                            putExtra("conversationId", conversationId)
                                        }
                                    )
                                },
                            ) { Text("查看对应聊天") }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
