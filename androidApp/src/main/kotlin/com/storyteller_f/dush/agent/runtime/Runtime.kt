package com.storyteller_f.dush.agent.runtime

import com.storyteller_f.dush.agent.data.MessageStatus
import com.storyteller_f.dush.agent.repository.AgentRepository
import com.storyteller_f.dush.agent.repository.ChatRepository
import com.storyteller_f.dush.agent.repository.ModelRepository

interface AgentRunner {
    suspend fun run(threadId: String, agentId: String, workerId: String): Result<Unit>
}

class KoogAgentRunner(
    private val chatRepository: ChatRepository,
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
    private val runtime: LocalLlmRuntime,
) : AgentRunner {
    override suspend fun run(threadId: String, agentId: String, workerId: String): Result<Unit> = runCatching {
        val agent = requireNotNull(agentRepository.getAgent(agentId)) { "Missing agent $agentId" }
        val modelId = agent.modelId ?: modelRepository.defaultModel()?.id
        val model = requireNotNull(modelId?.let { modelRepository.getModel(it) }) {
            "Select or import a local Gemma model before chatting."
        }
        runtime.initialize(model.localPath, model.backend)
        val assistantMessage = chatRepository.createAssistantMessage(threadId, workerId)
        val history = chatRepository.messages(threadId).map {
            ConversationMessage(role = it.role, content = it.content)
        }

        var content = ""
        runtime.generate(
            systemPrompt = agent.systemPrompt,
            history = history,
            temperature = agent.temperature,
            maxTokens = agent.maxTokens,
        ).collect { delta ->
            content += delta
            chatRepository.updateAssistantMessage(assistantMessage.id, content, MessageStatus.Streaming)
        }
        chatRepository.updateAssistantMessage(assistantMessage.id, content, MessageStatus.Complete)
    }.onFailure { throwable ->
        val message = "Unable to generate reply: ${throwable.message ?: throwable::class.simpleName}"
        val assistant = chatRepository.createAssistantMessage(threadId, workerId)
        chatRepository.updateAssistantMessage(assistant.id, message, MessageStatus.Failed)
    }
}
