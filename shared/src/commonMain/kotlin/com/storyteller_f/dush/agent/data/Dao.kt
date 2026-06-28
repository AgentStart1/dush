package com.storyteller_f.dush.agent.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.ConstructedBy
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("select * from models order by isDefault desc, createdAt desc")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("select * from models where id = :id")
    suspend fun get(id: String): ModelEntity?

    @Query("select * from models where isDefault = 1 limit 1")
    suspend fun defaultModel(): ModelEntity?

    @Upsert
    suspend fun upsert(model: ModelEntity)

    @Query("update models set isDefault = case when id = :id then 1 else 0 end")
    suspend fun selectDefault(id: String)

    @Query("update models set downloadedBytes = :downloadedBytes, sizeBytes = :sizeBytes where id = :id")
    suspend fun updateDownloadProgress(id: String, downloadedBytes: Long, sizeBytes: Long)

    @Query("delete from models where id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AgentDao {
    @Query("select * from agents order by isDefault desc, createdAt desc")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("select * from agents where id = :id")
    suspend fun get(id: String): AgentEntity?

    @Query("select * from agents where isDefault = 1 limit 1")
    suspend fun defaultAgent(): AgentEntity?

    @Upsert
    suspend fun upsert(agent: AgentEntity)

    @Query("update agents set isDefault = case when id = :id then 1 else 0 end")
    suspend fun selectDefault(id: String)

    @Query("delete from agents where id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChatDao {
    @Query("select * from chat_threads order by updatedAt desc")
    fun observeThreads(): Flow<List<ChatThreadEntity>>

    @Query("select * from chat_threads where id = :id")
    suspend fun getThread(id: String): ChatThreadEntity?

    @Query("select * from chat_messages where threadId = :threadId order by createdAt")
    fun observeMessages(threadId: String): Flow<List<ChatMessageEntity>>

    @Query("select * from chat_messages where threadId = :threadId order by createdAt")
    suspend fun messages(threadId: String): List<ChatMessageEntity>

    @Query("select * from chat_messages where id = :id")
    suspend fun getMessage(id: String): ChatMessageEntity?

    @Upsert
    suspend fun upsertThread(thread: ChatThreadEntity)

    @Upsert
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Query("update chat_threads set updatedAt = :updatedAt where id = :threadId")
    suspend fun touchThread(threadId: String, updatedAt: Long)

    @Query("select * from chat_threads where id = :threadId")
    fun observeThread(threadId: String): Flow<ChatThreadEntity?>

    @Query("update chat_threads set bubbleEnabled = :enabled where id = :threadId")
    suspend fun updateBubbleEnabled(threadId: String, enabled: Boolean)
}

class AgentConverters {
    @TypeConverter
    fun modelSource(value: ModelSource): String = value.name

    @TypeConverter
    fun modelSource(value: String): ModelSource = ModelSource.valueOf(value)

    @TypeConverter
    fun modelStatus(value: ModelStatus): String = value.name

    @TypeConverter
    fun modelStatus(value: String): ModelStatus = ModelStatus.valueOf(value)

    @TypeConverter
    fun messageRole(value: MessageRole): String = value.name

    @TypeConverter
    fun messageRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun messageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun messageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}

@Database(
    entities = [
        ModelEntity::class,
        AgentEntity::class,
        ChatThreadEntity::class,
        ChatMessageEntity::class,
    ],
    version = 3,
)
@TypeConverters(AgentConverters::class)
@ConstructedBy(AgentDatabaseConstructor::class)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun agentDao(): AgentDao
    abstract fun chatDao(): ChatDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AgentDatabaseConstructor : RoomDatabaseConstructor<AgentDatabase> {
    override fun initialize(): AgentDatabase
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE models ADD COLUMN downloadedBytes INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chat_threads ADD COLUMN bubbleEnabled INTEGER NOT NULL DEFAULT 0")
    }
}
