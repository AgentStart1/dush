package com.storyteller_f.dush.agent.runtime

import com.storyteller_f.dush.agent.data.ConnectionEntity
import com.storyteller_f.dush.agent.data.MessageRole
import kotlinx.coroutines.flow.Flow

data class ConversationMessage(val role: MessageRole, val content: String)

/** Cold stream of text deltas. Collection owns transport lifetime; cancellation closes it. */
fun interface InferenceRuntime {
    fun generate(connection: ConnectionEntity, credential: String, systemPrompt: String,
        history: List<ConversationMessage>, temperature: Double, maxTokens: Int): Flow<String>
}

/** Deliberately excludes underlying provider errors, which may contain private request data. */
class InferenceException(message: String = "推理服务请求失败，请检查地址、模型、授权和网络。") : Exception(message)
