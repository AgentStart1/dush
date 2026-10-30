package com.storyteller_f.dush.agent.runtime

import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.repository.*
import kotlinx.coroutines.*

interface AgentRunner {
    suspend fun run(threadId: String, agentId: String, workerId: String): Result<Unit>
}

class KoogAgentRunner(
    private val chatRepository: ChatRepository,
    private val agentRepository: AgentRepository,
    private val connectionRepository: ConnectionRepository,
    private val runtime: InferenceRuntime,
    private val healthContext: suspend (String) -> String? = { null },
) : AgentRunner {
    override suspend fun run(threadId: String, agentId: String, workerId: String): Result<Unit> = try {
        execute(threadId, agentId, workerId)
    } catch (cancelled: CancellationException) {
        // Query by owner as cancellation can happen after INSERT commits but before its result resumes.
        try {
            // Room owns its I/O dispatcher. Changing dispatcher here would rethrow the
            // caller's cancellation on return and misreport successful cleanup as failed.
            withContext(NonCancellable) { withTimeout(5_000) { chatRepository.cancelReplies(workerId) } }
        } catch (_: Exception) {
            co.touchlab.kermit.Logger.w(tag = "AgentRunner") { "operation=cancel_reply outcome=cleanup_failed" }
        }
        throw cancelled
    }

    private suspend fun execute(threadId: String, agentId: String, workerId: String): Result<Unit> {
        val assistant = chatRepository.createAssistantMessage(threadId, workerId)
        var content = ""
        return try {
            val agent = agentRepository.getAgent(agentId) ?: throw InferenceException("请选择有效的 Agent。")
            val connection = (agent.connectionId?.let { connectionRepository.getConnection(it) }
                ?: if (agent.connectionId == null) connectionRepository.defaultConnection() else null)
                ?: throw InferenceException("请先配置推理服务连接，并为 Agent 选择连接。")
            val history = chatRepository.messages(threadId).filter {
                it.id != assistant.id && it.status == MessageStatus.Complete && it.content.isNotBlank()
            }.map { ConversationMessage(it.role, it.content) }
            val peripheralContext = try { healthContext(threadId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "本地外设健康数据本次读取失败，不能推断数值；可在外设详情重新同步。" }
            runtime.generate(connection, connectionRepository.credential(connection.id),
                listOfNotNull(agent.systemPrompt, peripheralContext).joinToString("\n\n"), history, agent.temperature, agent.maxTokens).collect { delta ->
                content += delta
                chatRepository.updateAssistantMessage(assistant.id, content, MessageStatus.Streaming)
            }
            chatRepository.updateAssistantMessage(assistant.id, content, MessageStatus.Complete)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val safe = failure as? InferenceException ?: InferenceException()
            chatRepository.updateAssistantMessage(assistant.id, content.ifBlank { safe.message.orEmpty() }, MessageStatus.Failed)
            Result.failure(safe)
        }
    }
}
