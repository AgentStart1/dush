package com.storyteller_f.dush

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.storyteller_f.dush.agent.AgentServices
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.runtime.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

class DesktopGraph private constructor(val directory: Path, private val database: AgentDatabase,
    private val lockChannel: FileChannel, private val lock: FileLock) {
    val coordination = Dispatchers.Default.limitedParallelism(1)
    val services = AgentServices(database, DesktopConnectionSecrets(directory.resolve("credentials")),
        KoogInferenceRuntime { HttpClient(OkHttp) })
    val replies = ForegroundReplies(services.chats, services.agents, services.runner, coordination)

    suspend fun close() {
        try { replies.close() }
        finally { withContext(Dispatchers.IO) {
            try { database.close() } finally { lock.release(); lockChannel.close() }
        } }
        Logger.i(tag = "Desktop") { "operation=shutdown outcome=complete" }
    }

    companion object {
        /** Stable per-user data location; override only for isolated test/development runs. */
        fun dataDirectory(): Path {
            System.getenv("DUSH_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath().normalize() }
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") -> Path.of(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "dush")
                os.contains("mac") -> Path.of(home, "Library", "Application Support", "dush")
                else -> Path.of(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "dush")
            }
        }

        suspend fun open(directory: Path = dataDirectory()): DesktopGraph = withContext(Dispatchers.IO) {
            Logger.setMinSeverity(Severity.Info)
            Files.createDirectories(directory)
            val canonical = directory.toRealPath()
            val channel = FileChannel.open(canonical.resolve("application.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() ?: error("dush 已在使用此数据目录。") }
            catch (_: Exception) { channel.close(); error("dush 已在使用此数据目录，或无法锁定目录。") }
            val graph = try {
                val database = Room.databaseBuilder<AgentDatabase>(canonical.resolve("on-device-agent.db").toString())
                    .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                DesktopGraph(canonical, database, channel, lock)
            } catch (failure: Throwable) { lock.release(); channel.close(); throw failure }
            try {
                graph.replies.awaitReady()
                Logger.i(tag = "Desktop") { "operation=startup outcome=complete" }
                graph
            } catch (failure: Throwable) {
                withContext(NonCancellable) { graph.close() }
                throw failure
            }
        }
    }
}
