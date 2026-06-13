package com.storyteller_f.project.agent.runtime

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.storyteller_f.project.agent.data.MessageRole
import com.storyteller_f.project.agent.data.MessageStatus
import com.storyteller_f.project.agent.repository.AgentRepository
import com.storyteller_f.project.agent.repository.ChatRepository
import com.storyteller_f.project.agent.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

data class ConversationMessage(
    val role: MessageRole,
    val content: String,
)

interface LocalLlmRuntime {
    suspend fun initialize(modelPath: String, backend: String)
    fun generate(
        systemPrompt: String,
        history: List<ConversationMessage>,
        temperature: Double,
        maxTokens: Int,
    ): Flow<String>
    suspend fun close()
}

class LiteRtLocalLlmRuntime(
    private val context: Context,
) : LocalLlmRuntime {
    private var loadedModelPath: String? = null
    private var engine: Engine? = null

    override suspend fun initialize(modelPath: String, backend: String) {
        val file = File(modelPath)
        require(file.exists()) { "Model file does not exist: $modelPath" }
        require(file.length() > 0L) { "Model file is empty: $modelPath" }
        if (loadedModelPath == file.absolutePath && engine?.isInitialized() == true) return
        close()
        val selectedBackend = when (backend.lowercase()) {
            "gpu" -> Backend.GPU()
            else -> Backend.CPU()
        }
        engine = Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = selectedBackend,
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = null,
                maxNumImages = null,
                cacheDir = context.cacheDir.absolutePath,
            )
        ).also { it.initialize() }
        loadedModelPath = file.absolutePath
    }

    override fun generate(
        systemPrompt: String,
        history: List<ConversationMessage>,
        temperature: Double,
        maxTokens: Int,
    ): Flow<String> = flow {
        val activeEngine = requireNotNull(engine) { "LiteRT-LM engine is not initialized." }
        val lastUserMessage = history.lastOrNull { it.role == MessageRole.User }?.content
            ?: error("No user message to send.")
        val initialMessages = history.dropLast(1).mapNotNull { it.toLiteRtMessage() }
        activeEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.Companion.of(systemPrompt),
                initialMessages = initialMessages,
                tools = emptyList(),
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = temperature,
                    seed = 0,
                ),
            )
        ).use { conversation ->
            var previous = ""
            conversation.sendMessageAsync(lastUserMessage).collect { message ->
                val rendered = message.textContent().ifBlank { conversation.safeRender(message) }
                val delta = if (rendered.startsWith(previous)) rendered.removePrefix(previous) else rendered
                previous = rendered
                if (delta.isNotEmpty()) emit(delta)
            }
        }
    }

    override suspend fun close() {
        engine?.close()
        engine = null
        loadedModelPath = null
    }

    private fun ConversationMessage.toLiteRtMessage(): Message? {
        return when (role) {
            MessageRole.User -> Message.Companion.user(content)
            MessageRole.Assistant -> Message.Companion.model(Contents.Companion.of(content))
        }
    }

    private fun Message.textContent(): String {
        return contents.contents.joinToString(separator = "") { content ->
            when (content) {
                is Content.Text -> content.text
                else -> content.toString()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun Conversation.safeRender(message: Message): String {
        return runCatching { renderMessageIntoString(message) }.getOrDefault(message.toString())
    }
}

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
