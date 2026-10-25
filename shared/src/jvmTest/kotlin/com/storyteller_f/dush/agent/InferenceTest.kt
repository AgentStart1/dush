package com.storyteller_f.dush.agent

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.repository.*
import com.storyteller_f.dush.agent.runtime.*
import com.storyteller_f.dush.agent.ui.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

internal class MemorySecrets : ConnectionSecrets {
    private val values = mutableMapOf<String, String>()
    override suspend fun read(id: String) = values[id].orEmpty()
    override suspend fun write(id: String, secret: String) { values[id] = secret }
    override suspend fun delete(id: String) { values.remove(id) }
}
internal fun connection(provider: String = "LlmdHttp") = ConnectionEntity("service", "Test", provider,
    if (provider == "OpenRouter") "https://openrouter.ai/api/v1" else "http://localhost:11435/v1", "test-model", 1, true)
internal fun openDatabase(path: String) = Room.databaseBuilder<AgentDatabase>(path)
    .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

private inline fun <T> AgentDatabase.useDatabase(block: (AgentDatabase) -> T): T = try { block(this) } finally { close() }

class InferenceTest {
    @Test fun connectionAndAgentSurviveDatabaseReopen() = runTest {
        val path = Files.createTempFile("dush-persistence", ".db")
        val secrets = MemorySecrets()
        try {
            openDatabase(path.toString()).useDatabase { db ->
                val connections = ConnectionRepository(db.connectionDao(), secrets)
                connections.save(connection(), "synthetic-key")
                AgentRepository(db.agentDao(), db.connectionDao()).save("agent", "Agent", "prompt", 0.3, 128, "service")
            }
            openDatabase(path.toString()).useDatabase { db ->
                assertEquals(connection(), db.connectionDao().get("service"))
                assertEquals("service", db.agentDao().get("agent")?.connectionId)
                assertEquals("synthetic-key", ConnectionRepository(db.connectionDao(), secrets).credential("service"))
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun rejectedOpenRouterKeyClearPreservesSavedConfiguration() = runTest {
        assertRejectedKeyClearPreservesConfiguration("OpenRouter")
    }

    @Test fun rejectedOpenAIKeyClearPreservesSavedConfiguration() = runTest {
        assertRejectedKeyClearPreservesConfiguration("OpenAI")
    }

    private suspend fun assertRejectedKeyClearPreservesConfiguration(provider: String) {
        val path = Files.createTempFile("dush-key-validation", ".db")
        try { openDatabase(path.toString()).useDatabase { db ->
            val repository = ConnectionRepository(db.connectionDao(), MemorySecrets())
            val config = connection(provider).copy(endpoint = "https://example.com/v1")
            repository.save(config, "synthetic-existing-key")
            assertFailsWith<IllegalArgumentException> {
                repository.save(config.copy(name = "Rejected edit"), "   ")
            }
            assertEquals("synthetic-existing-key", repository.credential(config.id))
            assertEquals(config, repository.getConnection(config.id))
        } } finally { Files.deleteIfExists(path) }
    }

    @Test fun migrationPreservesHistoryAndClearsLocalModelAssociation() = runTest {
        val path = Files.createTempFile("dush-migration", ".db")
        try {
            val schema = javaClass.getResourceAsStream("/com.storyteller_f.dush.agent.data.AgentDatabase/3.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject }
            BundledSQLiteDriver().open(path.toString()).use { db ->
                schema["entities"]!!.jsonArray.forEach { entity ->
                    val item = entity.jsonObject
                    db.execSQL(item["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", item["tableName"]!!.jsonPrimitive.content))
                    item["indices"]?.jsonArray?.forEach { index -> db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", item["tableName"]!!.jsonPrimitive.content)) }
                }
                schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO models VALUES ('old','Local','Imported','/unused',1,'Available','cpu',NULL,1,1,0)")
                db.execSQL("INSERT INTO agents VALUES ('agent','Agent','prompt','old',0.7,128,'',1,1)")
                db.execSQL("INSERT INTO chat_threads VALUES ('thread','Chat','agent',1,1,1)")
                db.execSQL("INSERT INTO chat_messages VALUES ('message','thread','Assistant','partial','Streaming',1,1,'worker')")
                db.execSQL("PRAGMA user_version = 3")
            }
            openDatabase(path.toString()).useDatabase { db ->
                assertNull(db.agentDao().get("agent")?.modelId)
                assertNull(db.agentDao().get("agent")?.connectionId)
                assertEquals("prompt", db.agentDao().get("agent")?.systemPrompt)
                assertTrue(db.chatDao().getThread("thread")!!.bubbleEnabled)
                assertEquals("partial", db.chatDao().messages("thread").single().content)
                assertEquals(MessageStatus.Canceled, db.chatDao().messages("thread").single().status)
                assertNull(db.connectionDao().defaultConnection())
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun failureUpdatesSamePartialMessageAndExcludesFailedHistory() = runTest {
        val path = Files.createTempFile("dush-runner", ".db")
        try { openDatabase(path.toString()).useDatabase { db ->
            val connections = ConnectionRepository(db.connectionDao(), MemorySecrets())
            connections.save(connection())
            val agents = AgentRepository(db.agentDao(), db.connectionDao())
            val agent = agents.save("agent", "Agent", "original", 0.7, 128, "service")
            val chats = ChatRepository(db.chatDao(), db.agentDao())
            val thread = chats.createThread(agent)
            chats.appendUserMessage(thread, "hello")
            val failed = chats.createAssistantMessage(thread, "previous")
            chats.updateAssistantMessage(failed.id, "failed history", MessageStatus.Failed)
            val runtime = InferenceRuntime { _, _, prompt, history, _, _ -> flow {
                assertEquals("original\n\nscoped health", prompt)
                assertEquals(listOf("hello"), history.map { it.content })
                emit("partial")
                throw IllegalStateException("private provider data")
            } }
            assertTrue(KoogAgentRunner(chats, agents, connections, runtime) { "scoped health" }.run(thread, agent, "worker").isFailure)
            val reply = chats.messages(thread).filter { it.workerId == "worker" }.single()
            assertEquals("partial", reply.content)
            assertEquals(MessageStatus.Failed, reply.status)
            assertEquals("original", agents.getAgent(agent)?.systemPrompt)
        } } finally { Files.deleteIfExists(path) }
    }

    @Test fun cancellationPreservesPartialReplyAndClosesRuntime() = runTest {
        val path = Files.createTempFile("dush-cancel", ".db")
        try { openDatabase(path.toString()).useDatabase { db ->
            val connections = ConnectionRepository(db.connectionDao(), MemorySecrets()); connections.save(connection())
            val agents = AgentRepository(db.agentDao(), db.connectionDao())
            val agent = agents.save("agent", "Agent", "prompt", 0.7, 128, "service")
            val chats = ChatRepository(db.chatDao(), db.agentDao()); val thread = chats.createThread(agent)
            chats.appendUserMessage(thread, "hello")
            val started = CompletableDeferred<Unit>(); var closed = false
            val runtime = InferenceRuntime { _, _, _, _, _, _ -> flow {
                try { emit("partial"); started.complete(Unit); awaitCancellation() } finally { closed = true }
            } }
            val job = launch(Dispatchers.Default) { KoogAgentRunner(chats, agents, connections, runtime).run(thread, agent, "worker") }
            started.await(); job.cancelAndJoin()
            assertTrue(closed)
            val reply = chats.messages(thread).last()
            assertEquals("partial", reply.content)
            assertEquals(MessageStatus.Canceled, reply.status)
        } } finally { Files.deleteIfExists(path) }
    }

    @Test fun koogStreamsOpenAICompatibleTextDeltas() = runTest {
        withHttpFixture("""data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}

data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

""") { endpoint ->
            val result = KoogInferenceRuntime { HttpClient(OkHttp) }.generate(connection().copy(endpoint = endpoint), "", "prompt", listOf(ConversationMessage(MessageRole.User, "Hello")), 0.7, 32).toList()
            assertEquals("OK", result.joinToString(""))
        }
    }

    @Test fun koogErrorsDoNotExposeProviderBodyOrCredentials() = runTest {
        withHttpFixture("private response synthetic-key", 401) { endpoint ->
            val failure = assertFailsWith<InferenceException> { KoogInferenceRuntime { HttpClient(OkHttp) }.generate(connection().copy(endpoint = endpoint), "synthetic-key", "prompt", emptyList(), 0.7, 32).toList() }
            assertFalse(failure.message.orEmpty().contains("private"))
            assertFalse(failure.message.orEmpty().contains("synthetic-key"))
            assertTrue(failure.message.orEmpty().contains("授权失败"))
            assertNull(failure.cause)
        }
    }

    @Test fun connectionHostCancellationReleasesBusyState() = runTest {
        val path = Files.createTempFile("dush-host", ".db")
        try { openDatabase(path.toString()).useDatabase { db ->
            val connections = ConnectionRepository(db.connectionDao(), MemorySecrets())
            connections.save(connection())
            val started = CompletableDeferred<Unit>()
            var closed = false
            val runtime = InferenceRuntime { _, _, _, _, _, _ -> flow<String> {
                try { started.complete(Unit); awaitCancellation() } finally { closed = true }
            } }
            val host = ConnectionHost(connections, runtime, "service", Dispatchers.Default.limitedParallelism(1))
            try {
                host.state.first { it.ready }
                host.test(); started.await(); host.cancel().join()
                assertFalse(host.state.value.busy)
                assertEquals("已取消", host.state.value.message)
                assertTrue(closed)
            } finally { host.close() }
        } } finally { Files.deleteIfExists(path) }
    }

    @Test fun agentEditorRejectsZeroTokensWithoutSaving() = runTest {
        val path = Files.createTempFile("dush-agent-editor", ".db")
        try { openDatabase(path.toString()).useDatabase { db ->
            val connections = ConnectionRepository(db.connectionDao(), MemorySecrets())
            val host = AgentEditorHost(null, AgentRepository(db.agentDao(), db.connectionDao()), connections, Dispatchers.Default.limitedParallelism(1))
            try {
                host.state.first { it.ready }
                host.edit { it.copy(maxTokens = "0") }.join()
                host.save().join()
                assertNotNull(host.state.value.error)
                assertNull(db.agentDao().defaultAgent())
            } finally { host.close() }
        } } finally { Files.deleteIfExists(path) }
    }

    @Test fun deletingConnectionDetachesAgentAndRemovesCredential() = runTest {
        val path = Files.createTempFile("dush-delete", ".db")
        try { openDatabase(path.toString()).useDatabase { db ->
            val connections = ConnectionRepository(db.connectionDao(), MemorySecrets())
            connections.save(connection(), "synthetic-key")
            val agents = AgentRepository(db.agentDao(), db.connectionDao())
            agents.save("agent", "Agent", "prompt", 0.7, 128, "service")
            connections.delete("service")
            assertNull(connections.getConnection("service"))
            assertEquals("", connections.credential("service"))
            assertNull(agents.getAgent("agent")?.connectionId)
        } } finally { Files.deleteIfExists(path) }
    }

    @Test fun realLlmdStreaming() = runTest(timeout = kotlin.time.Duration.parse("180s")) {
        val endpoint = System.getenv("DUSH_TEST_LLMD_URL")
        assumeTrue(!endpoint.isNullOrBlank())
        val model = System.getenv("DUSH_TEST_LLMD_MODEL")
        assumeTrue(!model.isNullOrBlank())
        val output = KoogInferenceRuntime { HttpClient(OkHttp) }.generate(connection().copy(endpoint = endpoint.orEmpty(), model = model.orEmpty()), "", "Reply briefly.", listOf(ConversationMessage(MessageRole.User, "Say OK.")), 0.0, 32).toList()
        assertTrue(output.joinToString("").isNotBlank())
    }

    @Test fun realOpenRouterStreaming() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        assumeTrue(System.getenv("DUSH_RUN_OPENROUTER_TEST") == "1")
        val key = System.getenv("OPENROUTER_API_KEY")
        assumeTrue(!key.isNullOrBlank())
        val config = connection("OpenRouter").copy(model = "openai/gpt-4.1-nano")
        val output = KoogInferenceRuntime { HttpClient(OkHttp) }.generate(config, key.orEmpty(), "Reply briefly.", listOf(ConversationMessage(MessageRole.User, "Say OK.")), 0.0, 32).toList()
        assertTrue(output.joinToString("").isNotBlank())
    }
}

private suspend fun <T> withHttpFixture(body: String, status: Int = 200, action: suspend (String) -> T): T {
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "text/plain")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return try { action("http://127.0.0.1:${server.address.port}/v1") } finally { server.stop(0) }
}
