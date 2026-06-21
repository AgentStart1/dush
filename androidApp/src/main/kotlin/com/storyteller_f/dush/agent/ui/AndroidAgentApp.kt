package com.storyteller_f.dush.agent.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
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
import com.storyteller_f.dush.agent.data.ModelEntity
import com.storyteller_f.dush.agent.data.ModelStatus
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
sealed interface AppRoute : NavKey {
    @Serializable data object ChatList : AppRoute
    @Serializable data class ChatThread(val threadId: String) : AppRoute
    @Serializable data object Models : AppRoute
    @Serializable data class ModelDetail(val modelId: String) : AppRoute
    @Serializable data object Agents : AppRoute
    @Serializable data class AgentEditor(val agentId: String? = null) : AppRoute
    @Serializable data object Settings : AppRoute
}

private val topLevelRoutes = listOf(
    AppRoute.ChatList,
    AppRoute.Models,
    AppRoute.Agents,
    AppRoute.Settings,
)

@Composable
fun AndroidAgentApp(initialThreadId: String? = null, bubbleMode: Boolean = false) {
    val start = initialThreadId?.let { AppRoute.ChatThread(it) } ?: AppRoute.ChatList
    val backStack = rememberNavBackStack(start)
    val navigate: (AppRoute) -> Unit = { route ->
        if (route in topLevelRoutes) {
            backStack.clear()
            backStack.add(route)
        } else {
            backStack.add(route)
        }
    }

    LaunchedEffect(initialThreadId) {
        initialThreadId?.let {
            backStack.clear()
            backStack.add(AppRoute.ChatThread(it))
        }
    }

    DushTheme {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            val routeEntryProvider = entryProvider<NavKey> {
                entry<AppRoute.ChatList> { ChatListScreen(navigate) }
                entry<AppRoute.ChatThread> { key -> ChatThreadScreen(key.threadId) }
                entry<AppRoute.Models> { ModelsScreen(navigate) }
                entry<AppRoute.ModelDetail> { key -> ModelDetailScreen(key.modelId) }
                entry<AppRoute.Agents> { AgentsScreen(navigate) }
                entry<AppRoute.AgentEditor> { key -> AgentEditorScreen(key.agentId) { navigate(AppRoute.Agents) } }
                entry<AppRoute.Settings> { SettingsScreen() }
            }
            Scaffold(
                bottomBar = {
                    if (!bubbleMode) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp,
                        ) {
                            topLevelRoutes.forEach { route ->
                                NavigationBarItem(
                                    selected = backStack.lastOrNull() == route,
                                    onClick = { navigate(route) },
                                    icon = { Icon(route.icon(), contentDescription = route.label()) },
                                    label = { Text(route.label()) },
                                    modifier = Modifier.testTag(route.testTag()),
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                NavDisplay(
                    backStack = backStack,
                    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    entryProvider = routeEntryProvider,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun AppRoute.label(): String = when (this) {
    AppRoute.ChatList -> "Chat"
    AppRoute.Models -> "Models"
    AppRoute.Agents -> "Agents"
    AppRoute.Settings -> "Settings"
    else -> ""
}

@Composable
private fun AppRoute.icon(): ImageVector = when (this) {
    AppRoute.ChatList -> Icons.Filled.ChatBubble
    AppRoute.Models -> Icons.Filled.Memory
    AppRoute.Agents -> Icons.Filled.SmartToy
    AppRoute.Settings -> Icons.Filled.Settings
    else -> Icons.Filled.ChatBubble
}

private fun AppRoute.testTag(): String = when (this) {
    AppRoute.ChatList -> "nav-chat"
    AppRoute.Models -> "nav-models"
    AppRoute.Agents -> "nav-agents"
    AppRoute.Settings -> "nav-settings"
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(navigate: (AppRoute) -> Unit) {
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
                        navigate(AppRoute.ChatThread(threadId))
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
                        onClick = { navigate(AppRoute.ChatThread(thread.id)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatThreadScreen(threadId: String) {
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
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                colors = dushTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageRow(message) }
            }
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
            ) {
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
                    message.content.ifBlank { message.status.name },
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
private fun ModelsScreen(navigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val models by AppGraph.modelRepository.observeModels().collectAsState(initial = emptyList())
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { AppGraph.modelRepository.importModel(it) } }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Models") },
                scrollBehavior = scrollBehavior,
                colors = dushTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.testTag("models-import"),
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import")
                }
                OutlinedButton(
                    onClick = { scope.launch { AppGraph.modelRepository.downloadCatalogModel(AppGraph.modelRepository.catalog.first()) } },
                    modifier = Modifier.testTag("models-download-gemma"),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Gemma")
                }
            }
            if (models.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Memory,
                    message = "No models imported",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(models, key = { it.id }) { model ->
                        ModelCard(model, onClick = { navigate(AppRoute.ModelDetail(model.id)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelEntity, onClick: () -> Unit) {
    ListCard(
        title = model.name,
        subtitle = "${model.status} | ${model.backend} | ${model.sizeBytes / 1024 / 1024} MB",
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDetailScreen(modelId: String) {
    val scope = rememberCoroutineScope()
    val models by AppGraph.modelRepository.observeModels().collectAsState(initial = emptyList())
    val model = models.firstOrNull { it.id == modelId }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model detail") },
                colors = dushTopAppBarColors(),
            )
        },
    ) { padding ->
        if (model == null) {
            EmptyState(
                icon = Icons.Filled.Memory,
                message = "Model not found",
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            Column(
                Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = ShapeMedium,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(model.name, style = MaterialTheme.typography.headlineSmall)
                        Text(model.localPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusChip(model.status.name)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { scope.launch { AppGraph.modelRepository.selectDefault(model.id) } }) {
                        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Use as default")
                    }
                    OutlinedButton(onClick = { scope.launch { AppGraph.modelRepository.delete(model.id) } }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete")
                    }
                }
                if (model.status == ModelStatus.Failed && model.downloadUrl != null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            AppGraph.modelRepository.catalog.firstOrNull { it.id == model.id }
                                ?.let { AppGraph.modelRepository.downloadCatalogModel(it) }
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Retry download")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentsScreen(navigate: (AppRoute) -> Unit) {
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
                onClick = { navigate(AppRoute.AgentEditor(null)) },
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
                        onClick = { navigate(AppRoute.AgentEditor(agent.id)) },
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
    val scope = rememberCoroutineScope()
    val agents by AppGraph.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val models by AppGraph.modelRepository.observeModels().collectAsState(initial = emptyList())
    val existing = agents.firstOrNull { it.id == agentId }
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "Gemma Assistant") }
    var prompt by remember(existing?.id) { mutableStateOf(existing?.systemPrompt ?: "You are a helpful on-device assistant.") }
    var temperature by remember(existing?.id) { mutableStateOf(existing?.temperature ?: 0.7) }
    var maxTokens by remember(existing?.id) { mutableStateOf((existing?.maxTokens ?: 512).toString()) }
    val selectedModel = existing?.modelId ?: models.firstOrNull { it.isDefault }?.id ?: models.firstOrNull()?.id
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
                    name, { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeSmall,
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    prompt, { prompt = it },
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
                                "%.2f".format(temperature),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Slider(
                            value = temperature.toFloat(),
                            onValueChange = { temperature = it.toDouble() },
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
                    maxTokens,
                    { maxTokens = it.filter(Char::isDigit) },
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
                        Text("Model", style = MaterialTheme.typography.titleSmall)
                        Text(
                            models.firstOrNull { it.id == selectedModel }?.name ?: "None",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            AppGraph.agentRepository.save(
                                id = agentId,
                                name = name,
                                systemPrompt = prompt,
                                modelId = selectedModel,
                                temperature = temperature,
                                maxTokens = maxTokens.toIntOrNull() ?: 512,
                            )
                            done()
                        }
                    },
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
                    "Models are stored in private app storage.",
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
