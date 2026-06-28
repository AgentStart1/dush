package com.storyteller_f.dush.agent.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ModelSource { Imported, Downloaded }
enum class ModelStatus { Available, Downloading, Failed, Paused, Canceled }
enum class MessageRole { User, Assistant }
enum class MessageStatus { Pending, Streaming, Complete, Failed }

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val source: ModelSource,
    val localPath: String,
    val sizeBytes: Long,
    val status: ModelStatus,
    val backend: String,
    val downloadUrl: String?,
    val createdAt: Long,
    val isDefault: Boolean,
    val downloadedBytes: Long = 0,
)

@Entity(
    tableName = "agents",
    foreignKeys = [
        ForeignKey(
            entity = ModelEntity::class,
            parentColumns = ["id"],
            childColumns = ["modelId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [Index("modelId")],
)
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val modelId: String?,
    val temperature: Double,
    val maxTokens: Int,
    val enabledTools: String,
    val createdAt: Long,
    val isDefault: Boolean,
)

@Entity(
    tableName = "chat_threads",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [Index("agentId")],
)
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val agentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val bubbleEnabled: Boolean = false,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("threadId"), Index("workerId")],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val workerId: String?,
)
