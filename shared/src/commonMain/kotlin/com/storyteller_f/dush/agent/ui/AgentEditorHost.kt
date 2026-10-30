package com.storyteller_f.dush.agent.ui

import com.storyteller_f.dush.agent.data.ConnectionEntity
import com.storyteller_f.dush.agent.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class AgentEditorState(val name: String = "Assistant", val prompt: String = "You are a helpful assistant.",
    val temperature: Double = 0.7, val maxTokens: String = "512", val connectionId: String? = null,
    val connections: List<ConnectionEntity> = emptyList(), val ready: Boolean = false,
    val saving: Boolean = false, val error: String? = null)

class AgentEditorHost(private val id: String?, private val agents: AgentRepository,
    connections: ConnectionRepository, dispatcher: CoroutineDispatcher, parent: Job? = null) {
    private val scope = CoroutineScope(SupervisorJob(parent) + dispatcher)
    private val mutable = MutableStateFlow(AgentEditorState())
    val state = mutable.asStateFlow()
    private val saved = Channel<Unit>(Channel.BUFFERED)
    val effects = saved.receiveAsFlow()
    init {
        scope.launch { connections.observeConnections().collect { items -> mutable.update { it.copy(connections = items) } } }
        scope.launch {
            val agent = id?.let { agents.getAgent(it) }
            mutable.update { if (agent == null) it.copy(ready = id == null, error = if (id == null) null else "Agent 已不存在")
                else it.copy(name = agent.name, prompt = agent.systemPrompt, temperature = agent.temperature,
                    maxTokens = agent.maxTokens.toString(), connectionId = agent.connectionId, ready = true) }
        }
    }
    fun edit(transform: (AgentEditorState) -> AgentEditorState) = scope.launch {
        if (mutable.value.ready && !mutable.value.saving) mutable.update { transform(it).copy(error = null) }
    }
    fun save() = scope.launch {
        val draft = mutable.value
        if (!draft.ready || draft.saving) return@launch
        val tokens = draft.maxTokens.toIntOrNull()
        if (tokens == null || tokens <= 0) { mutable.update { it.copy(error = "Max tokens 必须是正整数") }; return@launch }
        mutable.update { it.copy(saving = true, error = null) }
        try {
            agents.save(id, draft.name, draft.prompt, draft.temperature, tokens, draft.connectionId)
            saved.send(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error = "保存失败，请检查连接是否仍然存在。") } }
        finally { mutable.update { it.copy(saving = false) } }
    }
    fun close() = scope.cancel()
}
