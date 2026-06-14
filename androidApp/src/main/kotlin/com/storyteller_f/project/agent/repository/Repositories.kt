package com.storyteller_f.project.agent.repository

import android.content.Context
import android.net.Uri
import com.storyteller_f.project.agent.data.AgentDao
import com.storyteller_f.project.agent.data.AgentEntity
import com.storyteller_f.project.agent.data.ChatDao
import com.storyteller_f.project.agent.data.ChatMessageEntity
import com.storyteller_f.project.agent.data.ChatThreadEntity
import com.storyteller_f.project.agent.data.MessageRole
import com.storyteller_f.project.agent.data.MessageStatus
import com.storyteller_f.project.agent.data.ModelDao
import com.storyteller_f.project.agent.data.ModelEntity
import com.storyteller_f.project.agent.data.ModelSource
import com.storyteller_f.project.agent.data.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.UUID

data class ModelCatalogItem(
    val id: String,
    val name: String,
    val url: String,
    val fileName: String,
)

class ModelRepository(
    private val context: Context,
    private val dao: ModelDao,
) {
    val catalog = listOf(
        ModelCatalogItem(
            id = "gemma-4-e2b-it-litert",
            name = "Gemma 4 E2B IT LiteRT-LM",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
        )
    )

    fun observeModels(): Flow<List<ModelEntity>> = dao.observeAll()

    suspend fun getModel(id: String): ModelEntity? = dao.get(id)

    suspend fun defaultModel(): ModelEntity? = dao.defaultModel()

    suspend fun importModel(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex("_display_name")
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "Imported Gemma model"
        val destination = File(modelDir(), "$id-${displayName.sanitizeFileName()}")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected model" }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        val isFirst = dao.defaultModel() == null
        dao.upsert(
            ModelEntity(
                id = id,
                name = displayName,
                source = ModelSource.Imported,
                localPath = destination.absolutePath,
                sizeBytes = destination.length(),
                status = ModelStatus.Available,
                backend = "LiteRT-LM",
                downloadUrl = null,
                createdAt = now(),
                isDefault = isFirst,
            )
        )
        id
    }

    suspend fun downloadCatalogModel(item: ModelCatalogItem): String = withContext(Dispatchers.IO) {
        val id = item.id
        val destination = File(modelDir(), item.fileName)
        val isFirst = dao.defaultModel() == null
        dao.upsert(
            ModelEntity(
                id = id,
                name = item.name,
                source = ModelSource.Downloaded,
                localPath = destination.absolutePath,
                sizeBytes = 0,
                status = ModelStatus.Downloading,
                backend = "LiteRT-LM",
                downloadUrl = item.url,
                createdAt = now(),
                isDefault = isFirst,
            )
        )
        try {
            URL(item.url).openStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            dao.upsert(
                requireNotNull(dao.get(id)).copy(
                    sizeBytes = destination.length(),
                    status = ModelStatus.Available,
                )
            )
        } catch (throwable: Throwable) {
            dao.upsert(requireNotNull(dao.get(id)).copy(status = ModelStatus.Failed))
            throw throwable
        }
        id
    }

    suspend fun selectDefault(id: String) = dao.selectDefault(id)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.get(id)?.localPath?.let { File(it).delete() }
        dao.delete(id)
    }

    private fun modelDir(): File = File(context.filesDir, "models").also { it.mkdirs() }
}

class AgentRepository(
    private val dao: AgentDao,
    private val modelDao: ModelDao,
) {
    fun observeAgents(): Flow<List<AgentEntity>> = dao.observeAll()

    suspend fun getAgent(id: String): AgentEntity? = dao.get(id)

    suspend fun defaultAgent(): AgentEntity {
        dao.defaultAgent()?.let { return it }
        val agent = AgentEntity(
            id = UUID.randomUUID().toString(),
            name = "Gemma Assistant",
            systemPrompt = "You are a helpful on-device assistant. Keep replies concise and useful.",
            modelId = modelDao.defaultModel()?.id,
            temperature = 0.7,
            maxTokens = 512,
            enabledTools = "",
            createdAt = now(),
            isDefault = true,
        )
        dao.upsert(agent)
        return agent
    }

    suspend fun save(
        id: String?,
        name: String,
        systemPrompt: String,
        modelId: String?,
        temperature: Double,
        maxTokens: Int,
    ): String {
        val finalId = id ?: UUID.randomUUID().toString()
        val isDefault = dao.defaultAgent() == null
        dao.upsert(
            AgentEntity(
                id = finalId,
                name = name.ifBlank { "Untitled Agent" },
                systemPrompt = systemPrompt,
                modelId = modelId,
                temperature = temperature,
                maxTokens = maxTokens,
                enabledTools = "",
                createdAt = now(),
                isDefault = isDefault,
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
    fun observeThreads(): Flow<List<ChatThreadEntity>> = chatDao.observeThreads()

    fun observeMessages(threadId: String): Flow<List<ChatMessageEntity>> = chatDao.observeMessages(threadId)

    suspend fun getThread(id: String): ChatThreadEntity? = chatDao.getThread(id)

    suspend fun messages(threadId: String): List<ChatMessageEntity> = chatDao.messages(threadId)

    suspend fun createThread(agentId: String? = null): String {
        val id = UUID.randomUUID().toString()
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
            id = UUID.randomUUID().toString(),
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
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            role = MessageRole.Assistant,
            content = "",
            status = MessageStatus.Streaming,
            createdAt = timestamp,
            updatedAt = timestamp,
            workerId = workerId,
        )
        chatDao.upsertMessage(message)
        chatDao.touchThread(threadId, timestamp)
        return message
    }

    suspend fun updateAssistantMessage(messageId: String, content: String, status: MessageStatus) {
        val current = requireNotNull(chatDao.getMessage(messageId)) { "Missing message $messageId" }
        val timestamp = now()
        chatDao.upsertMessage(current.copy(content = content, status = status, updatedAt = timestamp))
        chatDao.touchThread(current.threadId, timestamp)
    }
}

fun now(): Long = System.currentTimeMillis()

private fun String.sanitizeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
