package com.storyteller_f.dush.agent.runtime

import com.storyteller_f.dush.agent.data.MessageRole
import kotlinx.coroutines.flow.Flow

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

/**
 * Creates a LiteRT-LM backed [LocalLlmRuntime]. [cacheDir] is the absolute path the engine
 * may use for scratch files (Android: context.cacheDir; JVM: any writable directory).
 */
expect fun createLocalLlmRuntime(cacheDir: String): LocalLlmRuntime
