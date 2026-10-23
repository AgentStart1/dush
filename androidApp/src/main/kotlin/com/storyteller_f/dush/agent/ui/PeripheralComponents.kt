package com.storyteller_f.dush.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.storyteller_f.dush.agent.AppGraph
import com.storyteller_f.dush.agent.band.*

@Composable
internal fun rememberPeripheralHost(onEffect: (PeripheralEffect) -> Unit = {}): PeripheralHost {
    val hub = AppGraph.bands
    val host = remember { PeripheralHost(hub, AppGraph.chatRepository, hub.coordination) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val currentEffect by rememberUpdatedState(onEffect)
    DisposableEffect(host) { onDispose { host.close() } }
    LaunchedEffect(host, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            host.effects.collect { effect ->
                if (effect == PeripheralEffect.StartService) {
                    try {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) hub.start(context)
                        else host.startFailed()
                    } catch (failure: Exception) { host.startFailed() }
                } else currentEffect(effect)
            }
        }
    }
    return host
}

@Composable
internal fun PeripheralPermission(host: PeripheralHost, connectId: String? = null) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
        if (it && connectId != null) host.connect(connectId)
    }
    if (!permitted) OutlinedButton(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }, modifier = Modifier.testTag("peripheral-permission")) { Text("允许蓝牙连接") }
}

internal fun peripheralStatus(device: PeripheralDevice, status: BandStatus?): String = if (!device.enabled) "已断开" else when (status) {
    BandStatus.Connecting -> "正在连接…"
    BandStatus.Ready -> "已连接"
    is BandStatus.Listening -> "正在听：${status.text}"
    is BandStatus.Thinking -> "正在处理回复"
    is BandStatus.Failed -> status.message
    null -> "等待连接"
}

@Composable
internal fun SpeechModelMenu(model: SpeechModelState, download: () -> Unit) {
    when (model) {
        SpeechModelState.Ready -> Text("本地中文识别已就绪")
        is SpeechModelState.Downloading -> {
            Text("下载中文识别模型：${model.bytes / 1024 / 1024} MB")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        else -> {
            if (model is SpeechModelState.Failed) Text(model.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = download, modifier = Modifier.testTag("peripheral-download-model")) { Text("下载中文识别模型（约 42 MB）") }
        }
    }
}

@Composable
internal fun PeripheralError(state: PeripheralState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("peripheral-error")) }
}

@Composable
internal fun DeviceInfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(16.dp))
        Text(value ?: "未提供", style = MaterialTheme.typography.bodyMedium)
    }
}
