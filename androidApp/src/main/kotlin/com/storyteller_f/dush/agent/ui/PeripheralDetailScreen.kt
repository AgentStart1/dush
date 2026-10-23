package com.storyteller_f.dush.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller_f.dush.agent.band.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeripheralDetailScreen(id: String, back: () -> Unit, openThread: (String) -> Unit) {
    val host = rememberPeripheralHost { if (it == PeripheralEffect.Removed) back() }
    val state by host.state.collectAsStateWithLifecycle()
    val device = state.devices.firstOrNull { it.id == id }
    var name by remember(device?.id, device?.name) { mutableStateOf(device?.name.orEmpty()) }
    var removing by remember { mutableStateOf(false) }
    var choosingThread by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("外设详情") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding().fillMaxSize().testTag("peripheral-detail-scroll"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (device == null) item { Text(if (state.loading) "正在读取…" else "外设已移除") }
            else {
                item { Text(device.name, style = MaterialTheme.typography.headlineSmall) }
                item { Text(peripheralStatus(device, state.statuses[id]), modifier = Modifier.testTag("peripheral-connection-status")) }
                item { PeripheralPermission(host, id) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (device.enabled) OutlinedButton(onClick = { host.disconnect(id) }, enabled = !state.working, modifier = Modifier.testTag("peripheral-disconnect")) { Text("断开连接") }
                    else Button(onClick = { host.connect(id) }, enabled = !state.working, modifier = Modifier.testTag("peripheral-connect")) { Text("连接") }
                    OutlinedButton(onClick = { host.refresh(id) }, enabled = device.enabled && state.statuses[id] == BandStatus.Ready && !state.working, modifier = Modifier.testTag("peripheral-refresh")) { Text("刷新信息") }
                } }
                item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("设备信息", style = MaterialTheme.typography.titleMedium)
                    DeviceInfoRow("类型", "小米手环 6 NFC")
                    DeviceInfoRow("蓝牙地址", device.address)
                    val info = state.information[id]
                    DeviceInfoRow("电量", info?.batteryPercent?.let { "$it%${if (info.charging == true) " · 充电中" else ""}" })
                    DeviceInfoRow("设备型号", info?.model)
                    DeviceInfoRow("厂商", info?.manufacturer)
                    DeviceInfoRow("固件版本", info?.firmware)
                    DeviceInfoRow("硬件版本", info?.hardware)
                    Text("设备信息在连接或刷新时读取；断开后显示最近一次读取结果。", style = MaterialTheme.typography.bodySmall)
                } } }
                item { PeripheralHealthCard(state.health[id], state.healthStatuses[id], device.enabled && state.statuses[id] == BandStatus.Ready,
                    sync = { host.syncHealth(id) }, measure = { host.measureHeart(id) }) }
                item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("外设菜单", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it }, label = { Text("外设名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("peripheral-rename-input"))
                    TextButton(onClick = { host.rename(id, name) }, enabled = !state.working, modifier = Modifier.testTag("peripheral-rename-save")) { Text("保存名称") }
                    HorizontalDivider()
                    Text("对话绑定", style = MaterialTheme.typography.titleSmall)
                    if (device.threadId != null) {
                        Text(state.threads.firstOrNull { it.id == device.threadId }?.title ?: "对话不存在", modifier = Modifier.testTag("peripheral-bound-thread"))
                        TextButton(onClick = { openThread(device.threadId) }) { Text("打开对话") }
                        TextButton(onClick = { host.unbind(id) }, enabled = !state.working, modifier = Modifier.testTag("peripheral-unbind")) { Text("解除对话绑定") }
                    } else {
                        Text("未绑定对话：此外设仍可连接并读取设备信息。")
                        TextButton(onClick = { choosingThread = true }, enabled = state.threads.isNotEmpty(), modifier = Modifier.testTag("peripheral-bind-thread")) { Text("绑定到对话") }
                    }
                    HorizontalDivider()
                    Text("本地语音", style = MaterialTheme.typography.titleSmall)
                    SpeechModelMenu(state.model, host::download)
                    Text("识别文字会发送到绑定对话；回复会回显到手环，最多显示 300 字符。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    TextButton(onClick = { removing = true }, enabled = !state.working, modifier = Modifier.testTag("peripheral-remove")) { Text("移除外设", color = MaterialTheme.colorScheme.error) }
                } } }
                item { PeripheralError(state) }
            }
        }
    }
    if (removing) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { removing = false }, title = { Text("移除外设？") }, text = { Text("从 dush 移除此外设及保存的密钥，对话记录不受影响。不会解除手环的厂商配对。") }, confirmButton = {
        TextButton(onClick = { removing = false; host.remove(id) }, modifier = Modifier.testTag("peripheral-remove-confirm")) { Text("移除") }
    }, dismissButton = { TextButton(onClick = { removing = false }) { Text("取消") } })
    if (choosingThread) AlertDialog(onDismissRequest = { choosingThread = false }, title = { Text("绑定到对话") }, text = {
        LazyColumn(Modifier.semantics { testTagsAsResourceId = true }) {
            items(state.threads.size) { index ->
                val thread = state.threads[index]
                TextButton(onClick = { host.bind(id, thread.id); choosingThread = false }, modifier = Modifier.testTag("peripheral-thread-option-$index")) { Text(thread.title) }
            }
        }
    }, confirmButton = { TextButton(onClick = { choosingThread = false }) { Text("取消") } })
}
