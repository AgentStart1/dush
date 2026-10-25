package com.storyteller_f.dush.agent.ui

import com.storyteller_f.dush.agent.repository.AgentRepository
import com.storyteller_f.dush.agent.repository.ChatRepository
import com.storyteller_f.dush.agent.repository.ConnectionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ThreadAgentOption(val id: String, val name: String, val model: String)
data class ThreadAgentState(
    val options: List<ThreadAgentOption> = emptyList(),
    val selectedId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

class ThreadAgentSettingsHost(
    private val threadId: String,
    private val chats: ChatRepository,
    agents: AgentRepository,
    models: ConnectionRepository,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(ThreadAgentState())
    val state = mutableState.asStateFlow()
    init {
        scope.launch {
            combine(chats.observeThread(threadId), agents.observeAgents(), models.observeConnections()) { thread, items, modelItems ->
                val options = items.map { agent ->
                    val model = modelItems.firstOrNull { it.id == agent.connectionId }
                        ?: modelItems.firstOrNull { agent.connectionId == null && it.isDefault }
                    ThreadAgentOption(agent.id, agent.name, model?.name ?: "未配置连接")
                }
                options to thread?.agentId
            }.collect { (options, selected) ->
                mutableState.update { it.copy(options = options, selectedId = selected) }
            }
        }
    }
    fun select(id: String) = scope.launch {
        if (mutableState.value.saving) return@launch
        mutableState.update { it.copy(saving = true, error = null) }
        try { chats.selectAgent(threadId, id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.update { it.copy(error = "切换 Agent 失败，请重试") } }
        finally { mutableState.update { it.copy(saving = false) } }
    }
    fun close() = scope.cancel()
}
