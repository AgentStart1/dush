package com.storyteller_f.dush.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller_f.dush.agent.AppGraph

@Composable
fun ThreadAgentSettingsCard(threadId: String, editAgent: (String?) -> Unit) {
    val host = remember(threadId) {
        ThreadAgentSettingsHost(threadId, AppGraph.chatRepository, AppGraph.agentRepository,
            AppGraph.connectionRepository, AppGraph.bands.coordination)
    }
    DisposableEffect(host) { onDispose { host.close() } }
    val state by host.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    val selected = state.options.firstOrNull { it.id == state.selectedId }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("对话 Agent", style = MaterialTheme.typography.titleMedium)
            Text(selected?.let { "${it.name} · ${it.model}" } ?: "尚未选择 Agent")
            Text("这个对话及绑定手环的后续消息由所选 Agent 处理。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { choosing = true }, enabled = !state.saving,
                    modifier = Modifier.testTag("thread-agent-select")) { Text("选择 Agent") }
                TextButton(onClick = { editAgent(selected?.id) }, modifier = Modifier.testTag("thread-agent-edit")) {
                    Text(if (selected == null) "新建 Agent" else "配置 Agent")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("选择 Agent") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.semantics { testTagsAsResourceId = true }) {
                if (state.options.isEmpty()) item { Text("还没有 Agent，请先新建。") }
                items(state.options.size) { index ->
                    val option = state.options[index]
                    TextButton(onClick = { host.select(option.id); choosing = false },
                        modifier = Modifier.fillMaxWidth().testTag("thread-agent-option-$index")) {
                        Text("${if (option.id == state.selectedId) "✓ " else ""}${option.name} · ${option.model}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false; editAgent(null) }) { Text("新建 Agent") } },
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("取消") } },
    )
}
