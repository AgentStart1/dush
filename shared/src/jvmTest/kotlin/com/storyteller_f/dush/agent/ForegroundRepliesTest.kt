package com.storyteller_f.dush.agent

import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.runtime.*
import com.storyteller_f.dush.agent.repository.ChatRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class ForegroundRepliesTest {
    @Test fun nativeStartupFailureFailsReadinessInsteadOfHanging() = runTest {
        val path = Files.createTempFile("dush-startup-failure", ".db")
        val db = openDatabase(path.toString())
        val services = AgentServices(db, MemorySecrets(), streaming)
        val failingDao = object : ChatDao by db.chatDao() {
            override suspend fun recoverInterruptedReplies(timestamp: Long) {
                throw UnsatisfiedLinkError("Synthetic missing native library")
            }
        }
        val replies = ForegroundReplies(ChatRepository(failingDao, db.agentDao()), services.agents,
            services.runner, StandardTestDispatcher(testScheduler))
        try {
            assertFailsWith<InferenceException> { withTimeout(1_000) { replies.awaitReady() } }
            assertTrue(replies.active.value.isEmpty())
        } finally { replies.close(); db.close(); Files.deleteIfExists(path) }
    }

    private fun scenario(runtime: InferenceRuntime, block: suspend TestScope.(AgentServices, ForegroundReplies, String) -> Unit) = runTest {
        val path = Files.createTempFile("dush-foreground", ".db")
        val db = openDatabase(path.toString())
        val services = AgentServices(db, MemorySecrets(), runtime)
        val replies = ForegroundReplies(services.chats, services.agents, services.runner, StandardTestDispatcher(testScheduler))
        try {
            services.connections.save(connection())
            val agent = services.agents.defaultAgent()
            val thread = services.chats.createThread(agent.id)
            replies.awaitReady()
            block(services, replies, thread)
        } finally { replies.close(); db.close(); Files.deleteIfExists(path) }
    }

    private val streaming = InferenceRuntime { _, _, _, _, _, _ -> flow { emit("partial"); awaitCancellation() } }

    private suspend fun AgentServices.awaitPartial(thread: String) = chats.observeMessages(thread).first { rows ->
        rows.any { it.role == MessageRole.Assistant && it.content == "partial" }
    }

    @Test fun stopPreservesPartialReplyAndMarksItCanceled() = scenario(streaming) { services, replies, thread ->
        replies.send(thread, "synthetic prompt")
        services.awaitPartial(thread)

        replies.stop(thread)

        val assistant = services.chats.messages(thread).single { it.role == MessageRole.Assistant }
        assertEquals("partial", assistant.content)
        assertEquals(MessageStatus.Canceled, assistant.status)
        assertTrue(replies.active.value.isEmpty())
    }

    @Test fun shutdownJoinsReplyCleanupBeforeReturning() = scenario(streaming) { services, replies, thread ->
        replies.send(thread, "synthetic prompt")
        services.awaitPartial(thread)

        replies.close()

        assertEquals(MessageStatus.Canceled, services.chats.messages(thread).last().status)
        assertTrue(replies.active.value.isEmpty())
    }

    @Test fun replacementWaitsForOldReplyBeforeStartingNext() {
        var calls = 0
        scenario(InferenceRuntime { _, _, _, _, _, _ -> flow {
            calls++
            if (calls == 1) { emit("partial"); awaitCancellation() } else emit("second reply")
        } }) { services, replies, thread ->
            replies.send(thread, "first prompt")
            services.awaitPartial(thread)

            replies.send(thread, "second prompt")
            services.chats.observeMessages(thread).first { rows -> rows.any { it.content == "second reply" && it.status == MessageStatus.Complete } }
            replies.active.first { it.isEmpty() }

            val assistants = services.chats.messages(thread).filter { it.role == MessageRole.Assistant }
            assertEquals(listOf(MessageStatus.Canceled, MessageStatus.Complete), assistants.map { it.status })
            assertEquals(listOf("partial", "second reply"), assistants.map { it.content })
        }
    }

    @Test fun failedReplyReleasesActiveTask() = scenario(InferenceRuntime { _, _, _, _, _, _ -> flow {
        throw InferenceException("synthetic failure")
    } }) { services, replies, thread ->
        replies.send(thread, "synthetic prompt")
        services.chats.observeMessages(thread).first { rows -> rows.any { it.status == MessageStatus.Failed } }
        replies.active.first { it.isEmpty() }

        assertEquals("synthetic failure", services.chats.messages(thread).last().content)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun shutdownDuringReplacementStillJoinsTheOldOwner() {
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario(InferenceRuntime { _, _, _, _, _, _ -> flow {
            try { emit("partial"); awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        } }) { services, replies, thread ->
            replies.send(thread, "first prompt")
            services.awaitPartial(thread)
            val replacement = async { replies.send(thread, "second prompt") }
            cleaning.await()

            val shutdown = async { replies.close() }
            runCurrent()
            assertFalse(shutdown.isCompleted)
            release.complete(Unit)
            shutdown.await()

            assertFailsWith<CancellationException> { replacement.await() }
            assertEquals(MessageStatus.Canceled, services.chats.messages(thread).last().status)
        }
    }

    @Test fun startupRecoversInterruptedReplyWithoutStartingInference() = runTest {
        val path = Files.createTempFile("dush-recovery", ".db")
        try {
            val thread = openDatabase(path.toString()).let { db ->
                try {
                    val services = AgentServices(db, MemorySecrets(), streaming)
                    val agent = services.agents.defaultAgent()
                    val id = services.chats.createThread(agent.id)
                    val message = services.chats.createAssistantMessage(id, "dead-process")
                    services.chats.updateAssistantMessage(message.id, "saved partial", MessageStatus.Streaming)
                    id
                } finally { db.close() }
            }
            val db = openDatabase(path.toString())
            val services = AgentServices(db, MemorySecrets(), InferenceRuntime { _, _, _, _, _, _ -> error("Must not generate") })
            val replies = ForegroundReplies(services.chats, services.agents, services.runner, StandardTestDispatcher(testScheduler))
            try {
                replies.awaitReady()
                val recovered = services.chats.messages(thread).single()
                assertEquals(MessageStatus.Canceled, recovered.status)
                assertEquals("saved partial", recovered.content)
                assertTrue(replies.active.value.isEmpty())
            } finally { replies.close(); db.close() }
        } finally { Files.deleteIfExists(path) }
    }
}
