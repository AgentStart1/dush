package com.storyteller_f.dush.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.storyteller_f.dush.agent.data.MessageRole
import com.storyteller_f.dush.agent.repository.InferenceProvider

/** Shared business UI. Platforms supply composition, scheduling, theme and capabilities. */
@Composable
fun WorkspaceApp(host: WorkspaceHost, dataLocation: String,
    supportedProviders: List<InferenceProvider> = InferenceProvider.entries.filter { it != InferenceProvider.LlmdAndroid }) {
    val state by host.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(host) { host.effects.collect { snackbar.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("dush", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { host.navigate(WorkspaceRoute.Chats) }) { Text("聊天") }
                TextButton(onClick = { host.navigate(WorkspaceRoute.Connections) }) { Text("连接") }
                TextButton(onClick = { host.navigate(WorkspaceRoute.Agents) }) { Text("Agents") }
                TextButton(onClick = { host.navigate(WorkspaceRoute.Settings) }) { Text("设置") }
            }
            HorizontalDivider()
            when (val route = state.route) {
                WorkspaceRoute.Chats -> {
                    Button(host::newThread, enabled = !state.busy, modifier = Modifier.testTag("new-chat")) { Text("新建聊天") }
                    if (state.threads.isEmpty()) Text("先在连接中配置推理服务，再创建聊天。")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.threads, key = { it.id }) { thread ->
                            OutlinedCard(onClick = { host.navigate(WorkspaceRoute.Thread(thread.id)) }, modifier = Modifier.fillMaxWidth()) {
                                Text(thread.title, Modifier.padding(16.dp))
                            }
                        }
                    }
                }
                is WorkspaceRoute.Thread -> {
                    val session = requireNotNull(state.threadHost)
                    key(session) { ThreadContent(host, state, route, session) }
                }
                WorkspaceRoute.Connections -> {
                    Button(onClick = { host.navigate(WorkspaceRoute.Connection(null)) }) { Text("添加连接") }
                    Text("模型由推理服务管理。配置服务地址和精确模型 ID 后测试连接。")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.connections, key = { it.id }) { connection ->
                            OutlinedCard(onClick = { host.navigate(WorkspaceRoute.Connection(connection.id)) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(connection.name + if (connection.isDefault) " · 默认" else "")
                                    Text("${connection.provider} · ${connection.model}")
                                }
                            }
                        }
                    }
                }
                is WorkspaceRoute.Connection -> key(route) {
                    val editor = remember { host.connectionEditor(route.id) }
                    DisposableEffect(editor) { onDispose { editor.close() } }
                    ConnectionEditorContent(editor, supportedProviders)
                }
                WorkspaceRoute.Agents -> {
                    Button(onClick = { host.navigate(WorkspaceRoute.Agent(null)) }) { Text("添加 Agent") }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.agents, key = { it.id }) { agent ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(agent.name + if (agent.isDefault) " · 默认" else "")
                                    Row {
                                        TextButton(onClick = { host.navigate(WorkspaceRoute.Agent(agent.id)) }) { Text("编辑") }
                                        TextButton(onClick = { host.defaultAgent(agent.id) }, enabled = !state.busy) { Text("设为默认") }
                                        TextButton(onClick = { host.deleteAgent(agent.id) }, enabled = !state.busy) { Text("删除") }
                                    }
                                }
                            }
                        }
                    }
                }
                is WorkspaceRoute.Agent -> key(route) {
                    val editor = remember { host.agentEditor(route.id) }
                    DisposableEffect(editor) { onDispose { editor.close() } }
                    LaunchedEffect(editor) { editor.effects.collect { host.navigate(WorkspaceRoute.Agents) } }
                    AgentEditorContent(editor)
                }
                WorkspaceRoute.Settings -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("桌面设置", style = MaterialTheme.typography.titleLarge)
                    Text("数据库保存在本机，API key 保存在系统凭据存储中。")
                    SelectionContainer { Text(dataLocation) }
                    Text("回复在应用运行期间继续；退出会取消回复并保存已有内容。重启可恢复连接、Agent 和聊天记录。")
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ThreadContent(host: WorkspaceHost, state: WorkspaceState, route: WorkspaceRoute.Thread, session: ThreadHost) {
    val messages = session.messages.collectAsLazyPagingItems()
    val thread = state.threads.firstOrNull { it.id == route.id }
    Text(thread?.title ?: "聊天", style = MaterialTheme.typography.titleLarge)
    var choosing by remember(route.id) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { choosing = true }, enabled = !state.busy && route.id !in state.active) {
            Text("Agent：" + (state.agents.firstOrNull { it.id == thread?.agentId }?.name ?: "默认"))
        }
        DropdownMenu(choosing, { choosing = false }) {
            state.agents.forEach { agent -> DropdownMenuItem(text = { Text(agent.name) }, onClick = { choosing = false; host.selectAgent(route.id, agent.id) }) }
        }
    }
    when (messages.loadState.refresh) {
        is LoadState.Loading -> if (messages.itemCount == 0) Text("正在加载消息…")
        is LoadState.Error -> TextButton(onClick = messages::retry) { Text("消息加载失败，重试") }
        is LoadState.NotLoading -> Unit
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "bottom-sentinel") { Spacer(Modifier.height(1.dp)) }
        item(key = "newer-loading") {
            if (messages.loadState.prepend is LoadState.Error) {
                TextButton(onClick = messages::retry) { Text("较新消息加载失败，重试") }
            }
        }
        items(count = messages.itemCount, key = messages.itemKey { "message:${it.id}" }) { index ->
            val message = messages[index] ?: return@items
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (message.role == MessageRole.User) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (message.role == MessageRole.User) "你" else "Assistant · ${message.status}", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(message.content.ifEmpty { "…" }) }
                }
            }
        }
        item(key = "history-loading") {
            when (messages.loadState.append) {
                is LoadState.Loading -> Text("正在加载历史消息…")
                is LoadState.Error -> TextButton(onClick = messages::retry) { Text("历史加载失败，重试") }
                else -> Unit
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state.input, host::input, label = { Text("消息") }, modifier = Modifier.weight(1f).testTag("chat-input"), enabled = !state.busy, maxLines = 5)
        Button(host::send, enabled = state.input.isNotBlank() && !state.busy, modifier = Modifier.testTag("chat-send")) { Text("发送") }
        if (route.id in state.active) OutlinedButton(onClick = { host.stop(route.id) }, enabled = !state.busy) { Text("停止") }
    }
}

@Composable
fun ConnectionEditorContent(host: ConnectionHost, providers: List<InferenceProvider>,
    modifier: Modifier = Modifier, showHeading: Boolean = true, platformHint: String? = null,
    authorize: (() -> Unit)? = null) {
    val state by host.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val enabled = state.ready && !state.busy
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) Text("连接配置", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(draft.name, { value -> host.edit { it.copy(name = value) } }, label = { Text("名称") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("connection-name"))
        var choosing by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.testTag("connection-provider")) { Text(draft.provider) }
            if (choosing) {
                providers.forEach { provider -> TextButton(onClick = {
                    choosing = false
                    host.edit { it.copy(provider = provider.name, endpoint = when (provider) {
                        InferenceProvider.OpenAI -> "https://api.openai.com/v1"
                        InferenceProvider.OpenRouter -> "https://openrouter.ai/api/v1"
                        InferenceProvider.LlmdHttp -> "http://127.0.0.1:11435/v1"
                        InferenceProvider.LlmdAndroid -> "com.storytellerf.llmd"
                        InferenceProvider.OpenAICompatible -> ""
                    }) }
                }) { Text(provider.name) } }
            }
        }
        if (providers.none { it.name == draft.provider }) Text("此平台不支持该连接类型，请选择 HTTP 服务。", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(draft.endpoint, { value -> host.edit { it.copy(endpoint = value) } }, label = { Text("服务地址 / API base URL") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("connection-endpoint"))
        OutlinedTextField(draft.model, { value -> host.edit { it.copy(model = value) } }, label = { Text("精确模型 ID") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("connection-model"))
        if (draft.provider == InferenceProvider.LlmdAndroid.name) {
            Text("先授权，再在 llmd 中开启模型并等待 Ready。此接口返回完整回复；取消后忽略结果，服务端可能继续推理。")
            if (authorize != null) Button(authorize, enabled = enabled) { Text("授权 llmd") }
        } else {
            OutlinedTextField(state.credential ?: "", host::credential, label = { Text("API key（留空保留已有密钥）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("connection-key"))
            TextButton(host::clearCredential, enabled = enabled) { Text("清除已有密钥") }
            Text("HTTP 仅用于可信的本地服务；远程服务请使用 HTTPS。")
            platformHint?.let { Text(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(host::save, enabled = enabled && providers.any { it.name == draft.provider }, modifier = Modifier.testTag("connection-save")) { Text("保存") }
            OutlinedButton(host::test, enabled = enabled && providers.any { it.name == draft.provider }, modifier = Modifier.testTag("connection-test")) { Text("测试连接") }
            if (state.busy) TextButton(host::cancel) { Text("取消") }
        }
        if (state.items.any { it.id == draft.id }) Row {
            TextButton(host::selectDefault, enabled = enabled) { Text("设为默认") }
            TextButton(host::delete, enabled = enabled) { Text("删除连接") }
        }
        state.message?.let { Text(it, Modifier.testTag("connection-result")) }
    }
}

@Composable
fun AgentEditorContent(host: AgentEditorHost, modifier: Modifier = Modifier, showHeading: Boolean = true) {
    val state by host.state.collectAsStateWithLifecycle()
    val enabled = state.ready && !state.saving
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) Text("Agent 配置", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(state.name, { value -> host.edit { it.copy(name = value) } }, label = { Text("Name") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.prompt, { value -> host.edit { it.copy(prompt = value) } }, label = { Text("System prompt") }, minLines = 4, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Text("Temperature：${state.temperature}")
        Slider(state.temperature.toFloat(), { value -> host.edit { it.copy(temperature = value.toDouble()) } }, valueRange = 0f..2f, enabled = enabled)
        OutlinedTextField(state.maxTokens, { value -> host.edit { it.copy(maxTokens = value) } }, label = { Text("Max tokens") }, singleLine = true, enabled = enabled)
        var choosing by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { choosing = true }, enabled = enabled) { Text(state.connections.firstOrNull { it.id == state.connectionId }?.name ?: "使用默认连接") }
            DropdownMenu(choosing, { choosing = false }) {
                DropdownMenuItem(text = { Text("使用默认连接") }, onClick = { choosing = false; host.edit { it.copy(connectionId = null) } })
                state.connections.forEach { connection -> DropdownMenuItem(text = { Text(connection.name) }, onClick = { choosing = false; host.edit { it.copy(connectionId = connection.id) } }) }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(host::save, enabled = enabled) { Text("Save") }
    }
}
