package com.storyteller_f.dush.agent.runtime

import android.content.*
import androidx.test.platform.app.InstrumentationRegistry
import com.storyteller_f.dush.agent.data.*
import com.storytellerf.llmd.ipc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AndroidInferenceRuntimeDeviceTest {
    private val config = ConnectionEntity("test", "Test", "LlmdAndroid", "com.storytellerf.llmd.debug", "test-model", 0, true)
    private class FakeBinding(val health: String? = """{"engineReady":true,"activeModel":"test-model"}""") : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        lateinit var link: ServiceConnection
        val checking = CompletableDeferred<Unit>()
        val requested = CompletableDeferred<ILlmdChatCallback>()
        var unbound = false
        var important = false
        val service = object : ILlmdService.Stub() {
            override fun healthAsync(callback: ILlmdChatCallback) { checking.complete(Unit); health?.let(callback::onComplete) }
            override fun listModelsAsync(callback: ILlmdChatCallback) = Unit
            override fun chatCompletionAsync(requestJson: String, callback: ILlmdChatCallback) { requested.complete(callback) }
        }
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            link = connection
            important = flags and BIND_IMPORTANT != 0
            connection.onServiceConnected(ComponentName("com.storytellerf.llmd.debug", "Service"), service)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound = true }
    }
    private fun runtime(binding: FakeBinding) = AndroidInferenceRuntime(binding, InferenceRuntime { _, _, _, _, _, _ -> error("HTTP must not be used") })

    @Test fun binderReturnsCompleteReplyAndUnbinds() = runBlocking {
        val binding = FakeBinding()
        val reply = async { runtime(binding).generate(config, "", "prompt", listOf(ConversationMessage(MessageRole.User, "hello")), 0.7, 32).toList() }
        binding.requested.await().onComplete("""{"choices":[{"message":{"content":"OK"}}]}""")
        assertEquals(listOf("OK"), reply.await())
        assertTrue(binding.unbound)
        assertTrue(binding.important)
    }
    @Test fun binderCancellationUnbindsAndIgnoresLateCallback() = runBlocking {
        val binding = FakeBinding()
        val output = mutableListOf<String>()
        val reply = launch { runtime(binding).generate(config, "", "prompt", emptyList(), 0.7, 32).collect { output += it } }
        val callback = binding.requested.await()
        reply.cancelAndJoin()
        callback.onComplete("""{"choices":[{"message":{"content":"late"}}]}""")
        assertTrue(binding.unbound)
        assertTrue(output.isEmpty())
    }
    @Test fun binderDisconnectionDuringHealthFailsAndUnbinds() = runBlocking {
        val binding = FakeBinding(null)
        val failure = async {
            try { runtime(binding).generate(config, "", "prompt", emptyList(), 0.7, 32).toList(); null }
            catch (error: InferenceException) { error }
        }
        binding.checking.await()
        binding.link.onServiceDisconnected(ComponentName("com.storytellerf.llmd.debug", "Service"))
        assertNotNull(failure.await())
        assertTrue(binding.unbound)
    }
    @Test fun callerTimeoutRemainsCancellationAndUnbinds() = runBlocking {
        val binding = FakeBinding()
        val failure = async {
            try {
                withTimeout(1_000) { runtime(binding).generate(config, "", "prompt", emptyList(), 0.7, 32).toList() }
                null
            } catch (error: Exception) { error }
        }
        binding.requested.await()
        assertTrue("The caller deadline must not become a provider failure", failure.await() is TimeoutCancellationException)
        assertTrue(binding.unbound)
    }
    @Test fun binderAuthorizationErrorIsSafeAndUnbinds() = runBlocking {
        val binding = FakeBinding("""{"error":{"type":"authorization_required","message":"private provider detail"}}""")
        val failure = try { runtime(binding).generate(config, "", "prompt", emptyList(), 0.7, 32).toList(); null }
            catch (error: InferenceException) { error }
        assertNotNull(failure)
        assertFalse(failure!!.message.orEmpty().contains("private"))
        assertTrue(binding.unbound)
    }
}
