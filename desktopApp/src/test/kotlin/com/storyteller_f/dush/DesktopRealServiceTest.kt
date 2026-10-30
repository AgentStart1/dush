package com.storyteller_f.dush

import com.storyteller_f.dush.agent.data.MessageStatus
import com.storyteller_f.dush.agent.ui.WorkspaceHost
import com.storyteller_f.dush.agent.ui.WorkspaceRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/** Opt-in paid-service acceptance. Only synthetic prompts leave the test machine. */
class DesktopRealServiceTest {
    private suspend fun scenario(block: suspend (Path, String) -> Unit) {
        assumeTrue(System.getenv("DUSH_RUN_OPENROUTER_TEST") == "1")
        val key = System.getenv("OPENROUTER_API_KEY")
        require(!key.isNullOrBlank()) { "OPENROUTER_API_KEY is required for opt-in acceptance" }
        val directory = Files.createTempDirectory("dush-real-desktop-")
        try { block(directory, key) }
        finally {
            DesktopConnectionSecrets(directory.toRealPath().resolve("credentials")).delete("acceptance")
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun configured(directory: Path, key: String): DesktopGraph {
        val graph = DesktopGraph.open(directory)
        try {
            val draft = graph.services.connections.draft().copy(id = "acceptance", name = "Acceptance",
                provider = "OpenRouter", endpoint = "https://openrouter.ai/api/v1", model = "openai/gpt-4.1-nano")
            graph.services.connections.save(draft, key)
            graph.services.agents.save("acceptance-agent", "Acceptance Agent", "Follow the user's instructions.", 0.0, 1024, draft.id)
            return graph
        } catch (failure: Throwable) { graph.close(); throw failure }
    }

    @Test fun realReplyContinuesAcrossNavigationAndSurvivesRestart() = runTest(timeout = 3.minutes) {
        scenario { directory, key ->
            val graph = configured(directory, key)
            val host = WorkspaceHost(graph.services, graph.replies, graph.coordination)
            val thread = try {
                val editor = host.connectionEditor("acceptance")
                try {
                    editor.state.first { it.ready }
                    editor.test().join()
                    assertEquals("连接测试成功", editor.state.first { !it.busy && it.message != null }.message)
                } finally { editor.close() }
                host.newThread().join()
                val thread = (host.state.value.route as WorkspaceRoute.Thread).id
                host.input("Write twelve short numbered sentences about geometric shapes.").join()
                host.send().join()
                graph.services.chats.observeMessages(thread).first { rows -> rows.any { it.status == MessageStatus.Streaming && it.content.isNotBlank() } }
                host.navigate(WorkspaceRoute.Connections).join()
                graph.replies.active.first { it.isEmpty() }
                val reply = graph.services.chats.messages(thread).last()
                assertEquals(MessageStatus.Complete, reply.status)
                assertTrue(reply.content.isNotBlank())
                thread to reply.content
            } finally { host.close(); graph.close() }
            val reopened = DesktopGraph.open(directory)
            try {
                assertTrue(reopened.services.connections.credential("acceptance") == key, "Native credential must survive reopen")
                assertEquals("acceptance", reopened.services.agents.getAgent("acceptance-agent")?.connectionId)
                val reply = reopened.services.chats.messages(thread.first).last()
                assertEquals(MessageStatus.Complete, reply.status)
                assertTrue(thread.second == reply.content, "Reply must survive reopen")
            } finally { reopened.close() }
        }
    }

    @Test fun stoppingRealStreamPreservesPartialAndAllowsNextReply() = runTest(timeout = 3.minutes) {
        scenario { directory, key ->
            val graph = configured(directory, key)
            try {
                val thread = graph.services.chats.createThread("acceptance-agent")
                graph.replies.send(thread, "Write 100 numbered sentences about geometric shapes. Do not summarize.")
                graph.services.chats.observeMessages(thread).first { rows -> rows.any { it.status == MessageStatus.Streaming && it.content.isNotBlank() } }
                graph.replies.stop(thread)
                val canceled = graph.services.chats.messages(thread).last()
                assertEquals(MessageStatus.Canceled, canceled.status)
                assertTrue(canceled.content.isNotBlank())
                assertFalse(thread in graph.replies.active.value)
                graph.replies.send(thread, "Reply with the word OK.")
                graph.replies.active.first { it.isEmpty() }
                val rows = graph.services.chats.messages(thread)
                assertEquals(MessageStatus.Complete, rows.last().status)
                assertTrue(rows.last().content.isNotBlank())
                assertEquals(canceled, rows.first { it.id == canceled.id })
            } finally { graph.close() }
        }
    }

    @Test fun shuttingDownRealStreamPersistsCanceledPartial() = runTest(timeout = 3.minutes) {
        scenario { directory, key ->
            val graph = configured(directory, key)
            val thread = try {
                val thread = graph.services.chats.createThread("acceptance-agent")
                graph.replies.send(thread, "Write 100 numbered sentences about geometric shapes. Do not summarize.")
                graph.services.chats.observeMessages(thread).first { rows -> rows.any { it.status == MessageStatus.Streaming && it.content.isNotBlank() } }
                thread
            } finally { graph.close() }
            val reopened = DesktopGraph.open(directory)
            try {
                val reply = reopened.services.chats.messages(thread).last()
                assertEquals(MessageStatus.Canceled, reply.status)
                assertTrue(reply.content.isNotBlank())
                assertTrue(reopened.replies.active.value.isEmpty())
            } finally { reopened.close() }
        }
    }
}
