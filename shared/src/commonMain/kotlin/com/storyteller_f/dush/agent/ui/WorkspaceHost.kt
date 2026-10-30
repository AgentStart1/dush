package com.storyteller_f.dush.agent.ui

import com.storyteller_f.dush.agent.AgentServices
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.runtime.ForegroundReplies
import androidx.paging.PagingSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

sealed interface WorkspaceRoute {
    data object Chats : WorkspaceRoute
    data class Thread(val id: String) : WorkspaceRoute
    data object Connections : WorkspaceRoute
    data class Connection(val id: String?) : WorkspaceRoute
    data object Agents : WorkspaceRoute
    data class Agent(val id: String?) : WorkspaceRoute
    data object Settings : WorkspaceRoute
}

data class WorkspaceState(val route: WorkspaceRoute = WorkspaceRoute.Chats,
    val threads: List<ChatThreadEntity> = emptyList(), val agents: List<AgentEntity> = emptyList(),
    val connections: List<ConnectionEntity> = emptyList(), val threadHost: ThreadHost? = null,
    val active: Set<String> = emptySet(), val input: String = "", val busy: Boolean = false)

/** Window-owned navigation and commands; replies belong to the application scheduler. */
class WorkspaceHost(private val services: AgentServices, private val replies: ForegroundReplies,
    private val dispatcher: CoroutineDispatcher,
    private val messageSource: (String) -> PagingSource<Int, ChatMessageEntity> = services.chats::messagePagingSource) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private val notices = Channel<String>(Channel.BUFFERED)
    val effects = notices.receiveAsFlow()
    private val drafts = mutableMapOf<String, String>()

    fun connectionEditor(id: String?) = ConnectionHost(services.connections, services.runtime, id, dispatcher, scope.coroutineContext.job)
    fun agentEditor(id: String?) = AgentEditorHost(id, services.agents, services.connections, dispatcher, scope.coroutineContext.job)

    init {
        scope.launch { services.chats.observeThreads().collect { list -> mutable.update { it.copy(threads = list) } } }
        scope.launch { services.agents.observeAgents().collect { list -> mutable.update { it.copy(agents = list) } } }
        scope.launch { services.connections.observeConnections().collect { list -> mutable.update { it.copy(connections = list) } } }
        scope.launch { replies.active.collect { list -> mutable.update { it.copy(active = list) } } }
    }

    fun navigate(route: WorkspaceRoute) = scope.launch { open(route) }
    private fun open(route: WorkspaceRoute) {
        val previous = mutable.value.threadHost
        val next = (route as? WorkspaceRoute.Thread)?.let { thread ->
            ThreadHost(thread.id, dispatcher, scope.coroutineContext.job) { messageSource(thread.id) }
        }
        mutable.update { it.copy(route = route, threadHost = next,
            input = (route as? WorkspaceRoute.Thread)?.let { drafts[it.id] }.orEmpty()) }
        previous?.cancel()
    }

    private fun command(action: suspend () -> Unit) = scope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true) }
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notices.send("操作失败，请检查配置后重试。") }
        finally { mutable.update { it.copy(busy = false) } }
    }
    fun newThread() = command {
        val agent = services.agents.defaultAgent()
        open(WorkspaceRoute.Thread(services.chats.createThread(agent.id)))
    }
    fun input(value: String) = scope.launch {
        val thread = mutable.value.route as? WorkspaceRoute.Thread ?: return@launch
        if (!mutable.value.busy) {
            if (value.isEmpty()) drafts.remove(thread.id) else drafts[thread.id] = value
            mutable.update { it.copy(input = value) }
        }
    }
    fun send() = command {
        val snapshot = mutable.value
        val thread = snapshot.route as? WorkspaceRoute.Thread ?: return@command
        val content = snapshot.input.trim()
        if (content.isBlank()) return@command
        replies.send(thread.id, content)
        drafts.remove(thread.id)
        if (mutable.value.route == thread) mutable.update { it.copy(input = "") }
    }
    fun stop(threadId: String) = command { replies.stop(threadId) }
    fun selectAgent(threadId: String, agentId: String) = command { services.chats.selectAgent(threadId, agentId) }
    fun defaultAgent(id: String) = command { services.agents.selectDefault(id) }
    fun deleteAgent(id: String) = command { services.agents.delete(id) }
    suspend fun close() { scope.coroutineContext.job.cancelAndJoin(); notices.close() }
}
