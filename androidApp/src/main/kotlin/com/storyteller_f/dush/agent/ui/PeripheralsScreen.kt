package com.storyteller_f.dush.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller_f.dush.agent.band.PeripheralEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeripheralsScreen(open: (String) -> Unit, add: () -> Unit, back: (() -> Unit)? = null) {
    val host = rememberPeripheralHost()
    val state by host.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("外设") }, navigationIcon = {
        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }, floatingActionButton = {
        FloatingActionButton(onClick = add, modifier = Modifier.testTag("peripheral-add")) { Icon(Icons.Filled.Add, "添加外设") }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("peripherals-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PeripheralPermission(host); PeripheralError(state) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else if (state.devices.isEmpty()) item { Text("还没有外设，点击 + 添加。") }
            items(state.devices, key = { it.id }) { device ->
                Card(onClick = { open(device.id) }, modifier = Modifier.fillMaxWidth().testTag("peripheral-item-${device.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(peripheralStatus(device, state.statuses[device.id]))
                        state.information[device.id]?.batteryPercent?.let { Text("电量 $it%") }
                        Text(if (device.threadId == null) "未绑定对话" else "已绑定：${state.threads.firstOrNull { it.id == device.threadId }?.title ?: "对话不存在"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeripheralAddScreen(added: (String) -> Unit, back: () -> Unit) {
    var name by remember { mutableStateOf("小米手环 6 NFC") }
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val host = rememberPeripheralHost { when (it) {
        is PeripheralEffect.Added -> { key = ""; added(it.id) }
        is PeripheralEffect.FillConfiguration -> { address = it.address; key = it.key }
        else -> Unit
    } }
    val state by host.state.collectAsStateWithLifecycle()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) host.importConfiguration {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(4097); require(bytes.size <= 4096) { "配置文件过大" }; bytes.toString(Charsets.UTF_8)
            } ?: error("Cannot read configuration")
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("添加外设") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding().testTag("peripheral-add-scroll"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("小米手环 6 NFC", style = MaterialTheme.typography.titleMedium) }
            item { Text("添加已配对的手环。请先停止小米运动健康或其他应用的连接。添加后可在对话设置中绑定。") }
            item { PeripheralPermission(host) }
            item { OutlinedTextField(name, { name = it }, label = { Text("外设名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("peripheral-name")) }
            item { OutlinedTextField(address, { address = it }, label = { Text("蓝牙地址") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("peripheral-address")) }
            item { OutlinedTextField(key, { key = it }, label = { Text("已有配对密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("peripheral-key")) }
            item { Text("密钥加密保存在此手机，不会上传。当前支持小米手环 6 NFC；这里不会执行首次配对或解除厂商绑定。", style = MaterialTheme.typography.bodySmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(enabled, { enabled = it }, modifier = Modifier.testTag("peripheral-auto-connect")); Text("添加后连接")
            } }
            item { OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }) { Text("导入配置") } }
            item { Button(onClick = { host.add(name, address, key, enabled) }, enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("peripheral-save")) { Text("添加外设") } }
            item { PeripheralError(state) }
        }
    }
}
