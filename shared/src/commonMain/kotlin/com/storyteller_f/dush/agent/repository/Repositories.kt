package com.storyteller_f.dush.agent.repository

import com.storyteller_f.dush.agent.data.*
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

private fun newId(): String = Uuid.random().toString()

class AgentRepository(
    private val dao: AgentDao,
    private val connectionDao: ConnectionDao,
) {
    fun observeAgents(): Flow<List<AgentEntity>> = dao.observeAll()

    suspend fun getAgent(id: String): AgentEntity? = dao.get(id)

    suspend fun defaultAgent(): AgentEntity {
        dao.defaultAgent()?.let { return it }
        val agent = AgentEntity(
            id = newId(),
            name = "Assistant",
            systemPrompt = "You are a helpful assistant. Keep replies concise and useful.",
            modelId = null,
            connectionId = connectionDao.defaultConnection()?.id,
            temperature = 0.7,
            maxTokens = 512,
            enabledTools = "",
            createdAt = now(),
            isDefault = true,
        )
        connectionDao.saveAgent(agent)
        return agent
    }

    suspend fun save(
        id: String?,
        name: String,
        systemPrompt: String,
        temperature: Double,
        maxTokens: Int,
        connectionId: String? = null,
    ): String {
        require(temperature.isFinite() && temperature in 0.0..2.0)
        require(maxTokens > 0)
        val finalId = id ?: newId()
        val existing = id?.let { dao.get(it) }
        val isDefault = dao.defaultAgent() == null
        connectionDao.saveAgent(
            AgentEntity(
                id = finalId,
                name = name.ifBlank { "Untitled Agent" },
                systemPrompt = systemPrompt,
                modelId = null,
                connectionId = connectionId,
                temperature = temperature,
                maxTokens = maxTokens,
                enabledTools = existing?.enabledTools.orEmpty(),
                createdAt = existing?.createdAt ?: now(),
                isDefault = existing?.isDefault ?: isDefault,
            )
        )
        return finalId
    }

    suspend fun selectDefault(id: String) = dao.selectDefault(id)

    suspend fun delete(id: String) = dao.delete(id)
}

class ChatRepository(
    private val chatDao: ChatDao,
    private val agentDao: AgentDao,
) {
    suspend fun cancelReplies(workerId: String) = chatDao.cancelReplies(workerId, now())

    fun observeThreads(): Flow<List<ChatThreadEntity>> = chatDao.observeThreads()

    fun observeThread(threadId: String): Flow<ChatThreadEntity?> = chatDao.observeThread(threadId)

    fun observeMessages(threadId: String): Flow<List<ChatMessageEntity>> = chatDao.observeMessages(threadId)

    suspend fun getThread(id: String): ChatThreadEntity? = chatDao.getThread(id)

    suspend fun setBubbleEnabled(threadId: String, enabled: Boolean) = chatDao.updateBubbleEnabled(threadId, enabled)

    suspend fun selectAgent(threadId: String, agentId: String) {
        requireNotNull(agentDao.get(agentId)) { "Agent no longer exists" }
        chatDao.updateAgent(threadId, agentId)
    }

    suspend fun messages(threadId: String): List<ChatMessageEntity> = chatDao.messages(threadId)

    suspend fun createThread(agentId: String? = null): String {
        val id = newId()
        val selectedAgentId = agentId ?: agentDao.defaultAgent()?.id
        val timestamp = now()
        chatDao.upsertThread(
            ChatThreadEntity(
                id = id,
                title = "New chat",
                agentId = selectedAgentId,
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        )
        return id
    }

    suspend fun appendUserMessage(threadId: String, content: String): ChatMessageEntity {
        val timestamp = now()
        val title = content.take(48).ifBlank { "New chat" }
        val thread = requireNotNull(chatDao.getThread(threadId)) { "Missing thread $threadId" }
        chatDao.upsertThread(thread.copy(title = if (thread.title == "New chat") title else thread.title, updatedAt = timestamp))
        val message = ChatMessageEntity(
            id = newId(),
            threadId = threadId,
            role = MessageRole.User,
            content = content,
            status = MessageStatus.Complete,
            createdAt = timestamp,
            updatedAt = timestamp,
            workerId = null,
        )
        chatDao.upsertMessage(message)
        return message
    }

    suspend fun createAssistantMessage(threadId: String, workerId: String): ChatMessageEntity {
        val timestamp = now()
        val message = ChatMessageEntity(
            id = newId(),
            threadId = threadId,
            role = MessageRole.Assistant,
            content = "",
            status = MessageStatus.Streaming,
            createdAt = timestamp,
            updatedAt = timestamp,
            workerId = workerId,
        )
        chatDao.beginReply(message)
        return message
    }

    suspend fun updateAssistantMessage(messageId: String, content: String, status: MessageStatus) {
        val current = requireNotNull(chatDao.getMessage(messageId)) { "Missing message $messageId" }
        val timestamp = now()
        chatDao.upsertMessage(current.copy(content = content, status = status, updatedAt = timestamp))
        chatDao.touchThread(current.threadId, timestamp)
    }
}

fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

