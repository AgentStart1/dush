package com.storyteller_f.dush.agent.runtime

import android.content.*
import android.os.IBinder
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.repository.*
import com.storytellerf.llmd.ipc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with llmdPackage/llmdModel instrumentation arguments after granting dush access in llmd. */
class RealLlmdCrossAppDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun configuration(): ConnectionEntity {
        val arguments = InstrumentationRegistry.getArguments()
        val target = arguments.getString("llmdPackage")
        val model = arguments.getString("llmdModel")
        assumeTrue("Real llmd tests require explicit package/model arguments", !target.isNullOrBlank() && !model.isNullOrBlank())
        return ConnectionEntity("real-ipc-test", "Real IPC test", "LlmdAndroid", target.orEmpty(), model.orEmpty(), 0, true)
    }
    private fun runtime() = AndroidInferenceRuntime(context, InferenceRuntime { _, _, _, _, _, _ -> error("Real Android llmd must use Binder") })

    @Test fun authorizedRealServiceReturnsAssistantText() = runBlocking {
        val config = configuration()
        val output = withTimeout(120_000) {
            runtime().generate(config, "", "Reply briefly.", listOf(ConversationMessage(MessageRole.User, "Reply exactly REAL_IPC_OK.")), 0.0, 32).toList()
        }
        assertTrue("The real service must return independent assistant text", output.joinToString("").contains("REAL_IPC_OK"))
    }

    @Test fun cancellationIgnoresLateRealReplyAndAllowsNextInference() = runBlocking {
        val config = configuration()
        withService(config.endpoint) { service ->
            // A second binding keeps LlmdIpcService alive after the runtime unbinds. Its old request
            // can therefore finish and deliver the late callback instead of being canceled by onDestroy.
            val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
            try {
                val secrets = object : ConnectionSecrets {
                    override suspend fun read(id: String) = ""
                    override suspend fun write(id: String, secret: String) = Unit
                    override suspend fun delete(id: String) = Unit
                }
                val connections = ConnectionRepository(database.connectionDao(), secrets)
                connections.save(config)
                val agents = AgentRepository(database.agentDao(), database.connectionDao())
                val agent = agents.save(null, "Test", "Answer the user directly.", 0.0, 1024, config.id)
                val chats = ChatRepository(database.chatDao(), database.agentDao())
                val thread = chats.createThread(agent)
                chats.appendUserMessage(thread, "List every integer from 1 through 40, separated by commas.")
                val job = launch(Dispatchers.Default) { KoogAgentRunner(chats, agents, connections, runtime()).run(thread, agent, "real-cancel") }
                withTimeout(15_000) { chats.observeMessages(thread).first { items -> items.any { it.workerId == "real-cancel" && it.status == MessageStatus.Streaming } } }
                delay(1_000)
                assertTrue("Cancel while the real request is still running", job.isActive)
                job.cancelAndJoin()
                val canceled = chats.messages(thread).single { it.workerId == "real-cancel" }
                assertEquals(MessageStatus.Canceled, canceled.status)

                // The provider serializes requests. Completion of this next inference establishes
                // that the old request/callback has drained while our independent binding stayed alive.
                val next = withTimeout(120_000) { completion(service, config.model, "Reply exactly AFTER_REAL_CANCEL_OK.") }
                assertFalse("The next inference must succeed", next.has("error"))
                val text = next.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                assertTrue("The next inference must return assistant text", text.isNotBlank())
                delay(100)
                val after = chats.messages(thread).single { it.workerId == "real-cancel" }
                assertEquals(canceled, after)
            } finally { database.close() }
        }
    }

    private suspend fun completion(service: ILlmdService, model: String, prompt: String): JSONObject {
        val result = CompletableDeferred<String>()
        val request = JSONObject().put("model", model).put("temperature", 0).put("max_tokens", 32)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        service.chatCompletionAsync(request.toString(), object : ILlmdChatCallback.Stub() {
            override fun onComplete(responseJson: String) { result.complete(responseJson) }
        })
        return JSONObject(result.await())
    }

    private suspend fun <T> withService(target: String, block: suspend (ILlmdService) -> T): T {
        val ready = CompletableDeferred<ILlmdService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(ILlmdService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(IllegalStateException("llmd disconnected")) }
        }
        check(context.bindService(Intent("com.storytellerf.llmd.action.BIND_IPC").setPackage(target), binding, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
        return try { withTimeout(180_000) { block(ready.await()) } } finally { context.unbindService(binding) }
    }
}
