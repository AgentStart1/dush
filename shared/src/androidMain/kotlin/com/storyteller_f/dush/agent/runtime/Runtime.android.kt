package com.storyteller_f.dush.agent.runtime

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
import com.storyteller_f.dush.agent.data.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

actual fun createLocalLlmRuntime(cacheDir: String): LocalLlmRuntime = LiteRtLocalLlmRuntime(cacheDir)

private class LiteRtLocalLlmRuntime(
    private val cacheDir: String,
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
                cacheDir = cacheDir,
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
