package com.storyteller_f.dush.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ThreadPeripheralCard(threadId: String, manage: () -> Unit, detail: (String) -> Unit) {
    val host = rememberPeripheralHost()
    val state by host.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("thread-peripherals")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("绑定外设", style = MaterialTheme.typography.titleMedium)
            Text("选择已添加的外设，将语音交给这个对话的 Agent 处理。", style = MaterialTheme.typography.bodySmall)
            state.devices.filter { it.threadId == threadId }.forEach { device ->
                Text(device.name)
                Text(peripheralStatus(device, state.statuses[device.id]), modifier = Modifier.testTag("thread-peripheral-status"))
                Row {
                    TextButton(onClick = { detail(device.id) }) { Text("外设详情") }
                    TextButton(onClick = { host.unbind(device.id, threadId) }, enabled = !state.working, modifier = Modifier.testTag("thread-peripheral-unbind")) { Text("解除绑定") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { choosing = true }, enabled = !state.working, modifier = Modifier.testTag("thread-peripheral-bind")) { Text("绑定外设") }
                TextButton(onClick = manage, modifier = Modifier.testTag("thread-peripheral-manage")) { Text("管理外设") }
            }
            PeripheralError(state)
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择外设") }, text = {
        LazyColumn(Modifier.semantics { testTagsAsResourceId = true }) {
            if (state.devices.isEmpty()) item { Text("还没有外设，请先到外设页面添加。") }
            items(state.devices.size) { index ->
                val device = state.devices[index]
                val elsewhere = device.threadId != null && device.threadId != threadId
                TextButton(onClick = { host.bind(device.id, threadId); choosing = false }, enabled = !elsewhere,
                    modifier = Modifier.fillMaxWidth().testTag("thread-peripheral-option-$index")) {
                    Text(device.name + when { elsewhere -> " · 已绑定其他对话"; device.threadId == threadId -> " · 已绑定"; else -> "" })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false; manage() }) { Text("管理外设") } }, dismissButton = { TextButton(onClick = { choosing = false }) { Text("取消") } })
}
