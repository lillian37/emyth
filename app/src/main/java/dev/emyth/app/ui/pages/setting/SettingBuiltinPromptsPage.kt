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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import dev.emyth.app.data.ai.prompts.BUILTIN_PROMPTS
import dev.emyth.app.data.ai.prompts.BuiltinPromptDefinition
import dev.emyth.app.data.datastore.SettingsStore
import dev.emyth.app.ui.components.nav.BackButton
import dev.emyth.app.ui.theme.CustomColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingBuiltinPromptsPage() {
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val groups = remember { BUILTIN_PROMPTS.groupBy { it.group } }
    val overrides = settings.builtinPromptSetting.overrides

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var showDefault by rememberSaveable { mutableStateOf(false) }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun savePrompt() {
        if (saving) return
        val definition = BUILTIN_PROMPTS.firstOrNull { it.id == editingId } ?: return
        val overrideText = if (draft.isBlank()) "" else draft.takeUnless { it == definition.defaultText }
        saving = true
        editorError = null
        scope.launch {
            var saved = false
            try {
                store.updateBuiltinPrompt(definition.id, overrideText)
                editingId = null
                saved = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BuiltinPromptsPage", "Failed to save builtin prompt", e)
                editorError = "保存失败，草稿已保留，请稍后重试。"
            } finally {
                saving = false
            }
            if (saved) {
                snackbar.showSnackbar(
                    if (overrideText == "") "已保存，之后不再附加这段规则"
                    else "已保存，对之后开始的请求生效"
                )
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("内置提示词") },
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
            item(key = "introduction") {
                Text(
                    "这里的修改对所有助手生效，用于之后开始的请求。" +
                        "调整文字不会改变相关功能的开关。留空并保存后，不附加这段规则。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            groups.forEach { (group, definitions) ->
                item(key = "group:$group") {
                    Text(
                        group,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(definitions, key = { "prompt:${it.id}" }) { definition ->
                    val overrideText = overrides[definition.id]
                    BuiltinPromptCard(
                        definition = definition,
                        text = overrideText ?: definition.defaultText,
                        status = when {
                            overrideText == null -> "默认"
                            overrideText.isEmpty() -> "不附加"
                            else -> "已修改"
                        },
                        enabled = !saving,
                    ) {
                        editingId = definition.id
                        draft = overrideText ?: definition.defaultText
                        showDefault = false
                        editorError = null
                    }
                }
            }
        }
    }

    val editing = BUILTIN_PROMPTS.firstOrNull { it.id == editingId }
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) editingId = null },
            title = { Text(editing.title) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(editing.description, style = MaterialTheme.typography.bodyMedium)
                    BuiltinPromptVariables(editing)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            editorError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("提示词模板") },
                        minLines = 5,
                        maxLines = 12,
                        enabled = !saving,
                        isError = editorError != null,
                    )
                    Text(
                        "留空并保存后不附加这段规则。恢复默认会先填回默认文字，点击保存后生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            draft = editing.defaultText
                            editorError = null
                        },
                        enabled = !saving,
                    ) { Text("恢复默认") }
                    TextButton(onClick = { showDefault = !showDefault }, enabled = !saving) {
                        Text(if (showDefault) "收起默认全文" else "查看默认全文")
                    }
                    if (showDefault) {
                        SelectionContainer {
                            Text(
                                editing.defaultText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { savePrompt() }, enabled = !saving) {
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
private fun BuiltinPromptCard(
    definition: BuiltinPromptDefinition,
    text: String,
    status: String,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    definition.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(definition.description, style = MaterialTheme.typography.bodyMedium)
            BuiltinPromptVariables(definition)
            Text(
                if (text.isEmpty()) "不附加这段规则" else text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
            }
        }
    }
}

@Composable
private fun BuiltinPromptVariables(definition: BuiltinPromptDefinition) {
    if (definition.variables.isNotEmpty()) {
        Text(
            "可用变量：" + definition.variables.joinToString("、") { variable ->
                if (variable.startsWith("{{") && variable.endsWith("}}")) variable else "{{$variable}}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
