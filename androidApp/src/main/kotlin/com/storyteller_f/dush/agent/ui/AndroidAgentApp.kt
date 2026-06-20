package com.storyteller_f.dush.agent.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.storyteller_f.dush.agent.AppGraph
import com.storyteller_f.dush.agent.data.AgentEntity
import com.storyteller_f.dush.agent.data.ChatMessageEntity
import com.storyteller_f.dush.agent.data.MessageRole
import com.storyteller_f.dush.agent.data.ModelEntity
import com.storyteller_f.dush.agent.data.ModelStatus
import com.storyteller_f.dush.agent.worker.ReplyWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

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

    MaterialTheme {
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
                        NavigationBar {
                            topLevelRoutes.forEach { route ->
                                NavigationBarItem(
                                    selected = backStack.lastOrNull() == route,
                                    onClick = { navigate(route) },
                                    icon = { Text(route.shortLabel()) },
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
private fun AppRoute.shortLabel(): String = when (this) {
    AppRoute.ChatList -> "C"
    AppRoute.Models -> "M"
    AppRoute.Agents -> "A"
    AppRoute.Settings -> "S"
    else -> ""
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
    Scaffold(
        topBar = { TopAppBar(title = { Text("Chats") }) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val agent = AppGraph.agentRepository.defaultAgent()
                        val threadId = AppGraph.chatRepository.createThread(agent.id)
                        navigate(AppRoute.ChatThread(threadId))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("New chat") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Scaffold(
        topBar = { TopAppBar(title = { Text("Chat") }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageRow(message) }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                label = { Text("Message") },
            )
            Button(
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
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessageEntity) {
    val color = if (message.role == MessageRole.User) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (message.role == MessageRole.User) "You" else "Agent", style = MaterialTheme.typography.labelMedium)
            Text(message.content.ifBlank { message.status.name })
        }
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
    Scaffold(topBar = { TopAppBar(title = { Text("Models") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).testTag("models-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("models-import")) { Text("Import") }
                OutlinedButton(
                    onClick = { scope.launch { AppGraph.modelRepository.downloadCatalogModel(AppGraph.modelRepository.catalog.first()) } },
                    modifier = Modifier.testTag("models-download-gemma"),
                ) {
                    Text("Download Gemma")
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models, key = { it.id }) { model ->
                    ModelCard(model, onClick = { navigate(AppRoute.ModelDetail(model.id)) })
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
    Scaffold(topBar = { TopAppBar(title = { Text("Model detail") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model == null) {
                Text("Model not found")
            } else {
                Text(model.name, style = MaterialTheme.typography.titleLarge)
                Text(model.localPath)
                Text("Status: ${model.status}")
                Button(onClick = { scope.launch { AppGraph.modelRepository.selectDefault(model.id) } }) { Text("Use as default") }
                OutlinedButton(onClick = { scope.launch { AppGraph.modelRepository.delete(model.id) } }) { Text("Delete") }
                if (model.status == ModelStatus.Failed && model.downloadUrl != null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            AppGraph.modelRepository.catalog.firstOrNull { it.id == model.id }
                                ?.let { AppGraph.modelRepository.downloadCatalogModel(it) }
                        }
                    }) { Text("Retry download") }
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
    Scaffold(topBar = { TopAppBar(title = { Text("Agents") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { navigate(AppRoute.AgentEditor(null)) }, modifier = Modifier.fillMaxWidth()) { Text("New agent") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(agents, key = { it.id }) { agent ->
                    ListCard(
                        title = agent.name,
                        subtitle = "Temp ${agent.temperature} | Max ${agent.maxTokens}",
                        onClick = { navigate(AppRoute.AgentEditor(agent.id)) },
                        trailing = {
                            OutlinedButton(onClick = { scope.launch { AppGraph.agentRepository.selectDefault(agent.id) } }) {
                                Text(if (agent.isDefault) "Default" else "Use")
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
    Scaffold(topBar = { TopAppBar(title = { Text(if (agentId == null) "New agent" else "Edit agent") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it }, label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            Text("Temperature ${"%.2f".format(temperature)}")
            Slider(value = temperature.toFloat(), onValueChange = { temperature = it.toDouble() }, valueRange = 0f..1.5f)
            OutlinedTextField(maxTokens, { maxTokens = it.filter(Char::isDigit) }, label = { Text("Max tokens") }, modifier = Modifier.fillMaxWidth())
            Text("Model: ${models.firstOrNull { it.id == selectedModel }?.name ?: "None"}")
            Button(
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
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Notifications and bubbles are used when replies finish in the background.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Enable notifications")
                }
            }
            Text("Models are stored in private app storage.")
        }
    }
}

@Composable
private fun ListCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            trailing?.invoke()
        }
    }
}
