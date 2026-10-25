package com.storyteller_f.dush.agent.ui

import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import android.Manifest
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.storyteller_f.dush.agent.AppGraph
import com.storyteller_f.dush.agent.data.ChatMessageEntity
import com.storyteller_f.dush.agent.data.MessageRole
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller_f.dush.agent.repository.InferenceProvider
import com.storyteller_f.dush.agent.data.MessageStatus
import com.storyteller_f.dush.agent.worker.ReplyWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private val ShapeSmall = RoundedCornerShape(16.dp)
private val ShapeMedium = RoundedCornerShape(20.dp)
private val ShapeInput = RoundedCornerShape(24.dp)
private val UserBubbleShape = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp,
)
private val AgentBubbleShape = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun dushTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

@Serializable
sealed interface RootRoute : NavKey {
    @Serializable data object Home : RootRoute
    @Serializable data class ChatThread(val threadId: String) : RootRoute
    @Serializable data class ChatThreadSettings(val threadId: String) : RootRoute
    @Serializable data object Peripherals : RootRoute
    @Serializable data object PeripheralAdd : RootRoute
    @Serializable data class PeripheralDetail(val id: String) : RootRoute
    @Serializable data class ConnectionDetail(val connectionId: String) : RootRoute
    @Serializable data class AgentEditor(val agentId: String? = null) : RootRoute
}

private enum class HomeTab(val label: String, val icon: ImageVector, val testTag: String) {
    Chat("Chat", Icons.Filled.ChatBubble, "nav-chat"),
    Peripherals("外设", Icons.Filled.DevicesOther, "nav-peripherals"),
    Connections("连接", Icons.Filled.Memory, "nav-connections"),
    Agents("Agents", Icons.Filled.SmartToy, "nav-agents"),
    Settings("Settings", Icons.Filled.Settings, "nav-settings"),
}

@Composable
fun AndroidAgentApp(initialThreadId: String? = null, bubbleMode: Boolean = false) {
    val backStack = rememberNavBackStack(RootRoute.Home)
    val navigate: (RootRoute) -> Unit = { route -> backStack.add(route) }
    val goBack: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }

    LaunchedEffect(initialThreadId) {
        initialThreadId?.let {
            backStack.clear()
            backStack.add(RootRoute.Home)
            backStack.add(RootRoute.ChatThread(it))
        }
    }

    DushTheme {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            NavDisplay(
                backStack = backStack,
                onBack = { goBack() },
                entryProvider = entryProvider<NavKey> {
                    entry<RootRoute.Home> { HomeScreen(bubbleMode = bubbleMode, navigate = navigate) }
                    entry<RootRoute.ChatThread> { key -> ChatThreadScreen(key.threadId, navigate = navigate) }
                    entry<RootRoute.ChatThreadSettings> { key -> ChatThreadSettingsScreen(key.threadId, navigate) }
                    entry<RootRoute.Peripherals> { PeripheralsScreen(open = { navigate(RootRoute.PeripheralDetail(it)) }, add = { navigate(RootRoute.PeripheralAdd) }, back = goBack) }
                    entry<RootRoute.PeripheralAdd> { PeripheralAddScreen(added = { backStack.removeLastOrNull(); navigate(RootRoute.PeripheralDetail(it)) }, back = goBack) }
                    entry<RootRoute.PeripheralDetail> { key -> PeripheralDetailScreen(key.id, back = goBack, openThread = { navigate(RootRoute.ChatThread(it)) }) }
                    entry<RootRoute.ConnectionDetail> { key -> ConnectionEditorScreen(key.connectionId) }
                    entry<RootRoute.AgentEditor> { key -> AgentEditorScreen(key.agentId, done = goBack) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun HomeScreen(bubbleMode: Boolean, navigate: (RootRoute) -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Chat) }
    Scaffold(
        bottomBar = {
            if (!bubbleMode) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    HomeTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(tab.testTag),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                HomeTab.Chat -> ChatListScreen(onOpenThread = { navigate(RootRoute.ChatThread(it)) })
                HomeTab.Peripherals -> PeripheralsScreen(open = { navigate(RootRoute.PeripheralDetail(it)) }, add = { navigate(RootRoute.PeripheralAdd) })
                HomeTab.Connections -> ConnectionsScreen(onOpenConnection = { navigate(RootRoute.ConnectionDetail(it)) })
                HomeTab.Agents -> AgentsScreen(onOpenAgent = { navigate(RootRoute.AgentEditor(it)) })
                HomeTab.Settings -> SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(onOpenThread: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val threads by AppGraph.chatRepository.observeThreads().collectAsState(initial = emptyList())
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Chats") },
                scrollBehavior = scrollBehavior,
                colors = dushTopAppBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        val agent = AppGraph.agentRepository.defaultAgent()
                        val threadId = AppGraph.chatRepository.createThread(agent.id)
                        onOpenThread(threadId)
                    }
                },
                modifier = Modifier.testTag("new-chat"),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        },
    ) { padding ->
        if (threads.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.ChatBubble,
                message = "No conversations yet",
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(threads, key = { it.id }) { thread ->
                    ListCard(
                        title = thread.title,
                        subtitle = "Updated ${thread.updatedAt}",
                        onClick = { onOpenThread(thread.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatThreadScreen(threadId: String, navigate: (RootRoute) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by AppGraph.chatRepository.observeMessages(threadId).collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= messages.size - 2) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                colors = dushTopAppBarColors(),
                actions = {
                    IconButton(onClick = { navigate(RootRoute.ChatThreadSettings(threadId)) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Thread settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).imePadding().fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageRow(message) }
            }
            if (messages.any { it.status == MessageStatus.Streaming }) TextButton(onClick = {
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("reply-$threadId")
            }, modifier = Modifier.testTag("chat-cancel")) { Text("停止回复") }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...") },
                        shape = ShapeInput,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        maxLines = 4,
                    )
                    FilledTonalButton(
                        onClick = {
                            val content = input.trim()
                            if (content.isNotEmpty()) {
                                input = ""
                                scope.launch {
                                    val thread = AppGraph.chatRepository.getThread(threadId)
                                    val agent = thread?.agentId?.let { AppGraph.agentRepository.getAgent(it) }
                                        ?: AppGraph.agentRepository.defaultAgent()
                                    val userMessage = AppGraph.chatRepository.appendUserMessage(threadId, content)
                                    ReplyWorker.enqueue(context, threadId, agent.id, userMessage.id)
                                }
                            }
                        },
                        enabled = messages.none { it.status == MessageStatus.Streaming },
                        modifier = Modifier.size(48.dp).testTag("send-button"),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
            }
        }
    }
}

@Composable
internal fun MessageRow(message: ChatMessageEntity) {
    val isUser = message.role == MessageRole.User
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) Spacer(Modifier.weight(0.2f))
        Column(
            modifier = Modifier.weight(0.8f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                if (isUser) "You" else "Agent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Surface(
                color = bubbleColor,
                shape = if (isUser) UserBubbleShape else AgentBubbleShape,
                tonalElevation = if (isUser) 0.dp else 1.dp,
            ) {
                Text(
                    message.content.ifBlank { message.status.name } + if (message.status in listOf(MessageStatus.Canceled, MessageStatus.Failed) && message.content.isNotBlank()) "\n[${message.status}]" else "",
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (!isUser) Spacer(Modifier.weight(0.2f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsScreen(onOpenConnection: (String) -> Unit) {
    val items by AppGraph.connectionRepository.observeConnections().collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("推理服务连接") }) },
        floatingActionButton = { FloatingActionButton(onClick = { onOpenConnection("") }, modifier = Modifier.testTag("connection-add")) { Icon(Icons.Filled.Add, "添加连接") } }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("管理推理服务地址、模型 ID 和授权。dush 不再导入或下载语言模型。旧对话已保留，请重新选择连接。") }
            if (items.isEmpty()) item { Text("还没有连接，点击 + 添加。") }
            items(items, key = { it.id }) { item ->
                ListCard(title = item.name + if (item.isDefault) " · 默认" else "", subtitle = "${item.provider} · ${item.model}", onClick = { onOpenConnection(item.id) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditorScreen(connectionId: String) {
    val host = remember(connectionId) { ConnectionHost(AppGraph.connectionRepository, AppGraph.inferenceRuntime, connectionId, AppGraph.coordination) }
    DisposableEffect(host) { onDispose { host.close() } }
    val state by host.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val draft = state.draft
    val enabled = state.ready && !state.busy
    Scaffold(topBar = { TopAppBar(title = { Text("连接配置") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draft.name, { value -> host.edit { it.copy(name = value) } }, label = { Text("名称") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("connection-name"))
            var choosingProvider by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { choosingProvider = true }, enabled = enabled, modifier = Modifier.testTag("connection-provider")) { Text(draft.provider) }
            if (choosingProvider) androidx.compose.material3.AlertDialog(
                onDismissRequest = { choosingProvider = false }, title = { Text("推理服务类型") },
                text = { Column { InferenceProvider.entries.forEach { provider ->
                    TextButton(onClick = {
                        choosingProvider = false
                        host.edit { it.copy(provider = provider.name, endpoint = when (provider) {
                            InferenceProvider.OpenRouter -> "https://openrouter.ai/api/v1"
                            InferenceProvider.OpenAI -> "https://api.openai.com/v1"
                            InferenceProvider.LlmdHttp -> "http://127.0.0.1:11435/v1"
                            InferenceProvider.LlmdAndroid -> "com.storytellerf.llmd"
                            InferenceProvider.OpenAICompatible -> ""
                        }) }
                    }) { Text(provider.name) }
                } } }, confirmButton = { TextButton(onClick = { choosingProvider = false }) { Text("取消") } },
            )
            OutlinedTextField(draft.endpoint, { value -> host.edit { it.copy(endpoint = value) } }, label = { Text(if (draft.provider == InferenceProvider.LlmdAndroid.name) "llmd 安装包名" else "API base URL（含 /v1）") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("connection-endpoint"))
            OutlinedTextField(draft.model, { value -> host.edit { it.copy(model = value) } }, label = { Text("精确模型 ID") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("connection-model"))
            if (draft.provider == InferenceProvider.LlmdAndroid.name) {
                Text("支持 release / .debug / .alpha / .e2e 包名。先授权，再在 llmd 中开启此模型并等待 Ready。此接口仅返回完整回复；取消后 dush 忽略结果，llmd 可能继续推理。")
                OutlinedButton(onClick = {
                    try { context.startActivity(android.content.Intent("com.storytellerf.llmd.action.AUTHORIZE_CALLER").setPackage(draft.endpoint).putExtra("caller_package", context.packageName)) }
                    catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "未找到所选 llmd 安装版本", android.widget.Toast.LENGTH_LONG).show() }
                }, enabled = enabled) { Text("授权 llmd") }
            } else {
                OutlinedTextField(state.credential ?: "", host::credential, label = { Text("API key（留空保留已有密钥）") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("connection-key"))
                TextButton(onClick = host::clearCredential, enabled = enabled) { Text("清除已有密钥") }
                Text("HTTP 仅用于可信的本地兼容服务；远程服务请使用 HTTPS。Android 访问电脑服务需填写电脑局域网地址。")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = host::save, enabled = enabled, modifier = Modifier.testTag("connection-save")) { Text("保存") }
                OutlinedButton(onClick = host::test, enabled = enabled, modifier = Modifier.testTag("connection-test")) { Text("测试连接") }
                if (state.busy) TextButton(onClick = host::cancel) { Text("取消") }
            }
            if (state.items.any { it.id == draft.id }) Row {
                TextButton(onClick = host::selectDefault, enabled = enabled) { Text("设为默认") }
                TextButton(onClick = host::delete, enabled = enabled) { Text("删除连接") }
            }
            state.message?.let { Text(it, modifier = Modifier.testTag("connection-result")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentsScreen(onOpenAgent: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val agents by AppGraph.agentRepository.observeAgents().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { AppGraph.agentRepository.defaultAgent() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Agents") },
                scrollBehavior = scrollBehavior,
                colors = dushTopAppBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onOpenAgent(null) },
                modifier = Modifier.testTag("new-agent"),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New agent")
            }
        },
    ) { padding ->
        if (agents.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.SmartToy,
                message = "No agents configured",
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(agents, key = { it.id }) { agent ->
                    ListCard(
                        title = agent.name,
                        subtitle = "Temp ${agent.temperature} | Max ${agent.maxTokens}",
                        onClick = { onOpenAgent(agent.id) },
                        trailing = {
                            if (agent.isDefault) {
                                StatusChip("Default")
                            } else {
                                OutlinedButton(
                                    onClick = { scope.launch { AppGraph.agentRepository.selectDefault(agent.id) } },
                                    shape = ShapeMedium,
                                ) { Text("Use") }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentEditorScreen(agentId: String?, done: () -> Unit) {
    val host = remember(agentId) { AgentEditorHost(agentId, AppGraph.agentRepository, AppGraph.connectionRepository, AppGraph.coordination) }
    DisposableEffect(host) { onDispose { host.close() } }
    val state by host.state.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(host, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            host.effects.collect { done() }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (agentId == null) "New agent" else "Edit agent") },
                colors = dushTopAppBarColors(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(
                    state.name, { value -> host.edit { it.copy(name = value) } },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeSmall,
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    state.prompt, { value -> host.edit { it.copy(prompt = value) } },
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeSmall,
                    minLines = 4,
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = ShapeMedium,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Temperature", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "%.2f".format(state.temperature),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Slider(
                            value = state.temperature.toFloat(),
                            onValueChange = { value -> host.edit { it.copy(temperature = value.toDouble()) } },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    state.maxTokens,
                    { value -> host.edit { it.copy(maxTokens = value.filter(Char::isDigit)) } },
                    label = { Text("Max tokens") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeSmall,
                    singleLine = true,
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = ShapeMedium,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("推理服务连接", style = MaterialTheme.typography.titleSmall)
                        Text(
                            state.connections.firstOrNull { it.id == state.connectionId }?.name ?: "使用默认连接",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Column {
                    TextButton(onClick = { host.edit { it.copy(connectionId = null) } }) { Text("使用默认连接") }
                    state.connections.forEach { connection ->
                        TextButton(onClick = { host.edit { it.copy(connectionId = connection.id) } }) { Text((if (state.connectionId == connection.id) "✓ " else "") + connection.name) }
                    }
                }
            }
            item { state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                FilledTonalButton(
                    onClick = host::save,
                    enabled = state.ready && !state.saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = ShapeSmall,
                ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                scrollBehavior = scrollBehavior,
                colors = dushTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsSection(title = "Notifications") {
                Text(
                    "Notifications and bubbles are used when replies finish in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Enable notifications")
                    }
                }
            }
            SettingsSection(title = "Storage") {
                Text(
                    "推理服务密钥在本机加密保存，语言模型由服务管理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = ShapeMedium,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
internal fun ListCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun StatusChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = ShapeMedium,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatThreadSettingsScreen(threadId: String, navigate: (RootRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val thread by AppGraph.chatRepository.observeThread(threadId).collectAsState(initial = null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thread settings") },
                colors = dushTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThreadAgentSettingsCard(threadId) { navigate(RootRoute.AgentEditor(it)) }
            ThreadPeripheralCard(threadId, manage = { navigate(RootRoute.Peripherals) }, detail = { navigate(RootRoute.PeripheralDetail(it)) })
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = ShapeMedium,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Show as bubble", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Reply notifications will automatically expand as a chat bubble",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = thread?.bubbleEnabled ?: false,
                        onCheckedChange = { enabled ->
                            scope.launch { AppGraph.chatRepository.setBubbleEnabled(threadId, enabled) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
