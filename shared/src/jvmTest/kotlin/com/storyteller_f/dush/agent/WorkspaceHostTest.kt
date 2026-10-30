package com.storyteller_f.dush.agent

import com.storyteller_f.dush.agent.runtime.*
import com.storyteller_f.dush.agent.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class WorkspaceHostTest {
    @Test fun acceptedMessageClearsDraftWhenReturningToThread() = runTest {
        val path = Files.createTempFile("dush-workspace-send", ".db")
        val db = openDatabase(path.toString())
        val services = AgentServices(db, MemorySecrets(), InferenceRuntime { _, _, _, _, _, _ -> flowOf("reply") })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replies = ForegroundReplies(services.chats, services.agents, services.runner, dispatcher)
        val host = WorkspaceHost(services, replies, dispatcher)
        try {
            services.connections.save(connection())
            val thread = WorkspaceRoute.Thread(services.chats.createThread())
            host.navigate(thread).join()
            host.input("synthetic message").join()

            host.send().join()
            host.navigate(WorkspaceRoute.Chats).join()
            host.navigate(thread).join()

            assertEquals("", host.state.value.input)
            assertEquals("synthetic message", services.chats.messages(thread.id).first().content)
        } finally { host.close(); replies.close(); db.close(); Files.deleteIfExists(path) }
    }

    @Test fun switchingPagesPreservesEachThreadsUnsentDraft() = runTest {
        val path = Files.createTempFile("dush-workspace-draft", ".db")
        val db = openDatabase(path.toString())
        val services = AgentServices(db, MemorySecrets(), InferenceRuntime { _, _, _, _, _, _ -> emptyFlow() })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replies = ForegroundReplies(services.chats, services.agents, services.runner, dispatcher)
        val host = WorkspaceHost(services, replies, dispatcher)
        try {
            val first = WorkspaceRoute.Thread(services.chats.createThread())
            val second = WorkspaceRoute.Thread(services.chats.createThread())
            host.navigate(first).join()
            host.input("first draft").join()
            host.navigate(second).join()
            assertEquals("", host.state.value.input)
            host.input("second draft").join()
            host.navigate(WorkspaceRoute.Connections).join()

            host.navigate(first).join()
            assertEquals("first draft", host.state.value.input)
            host.navigate(second).join()
            assertEquals("second draft", host.state.value.input)
            assertTrue(services.chats.messages(first.id).isEmpty())
            assertTrue(services.chats.messages(second.id).isEmpty())
        } finally { host.close(); replies.close(); db.close(); Files.deleteIfExists(path) }
    }

    @Test fun closingWorkspaceJoinsAnOpenEditorsTransportCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val path = Files.createTempFile("dush-workspace", ".db")
        val db = openDatabase(path.toString())
        val services = AgentServices(db, MemorySecrets(), InferenceRuntime { _, _, _, _, _, _ -> flow {
            try { started.complete(Unit); awaitCancellation() }
            finally { closed.complete(Unit) }
        } })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replies = ForegroundReplies(services.chats, services.agents, services.runner, dispatcher)
        val host = WorkspaceHost(services, replies, dispatcher)
        try {
            services.connections.save(connection())
            val editor = host.connectionEditor("service")
            editor.state.first { it.ready }
            editor.test()
            started.await()

            host.close()

            assertTrue(closed.isCompleted)
            assertFalse(editor.state.value.busy)
        } finally { host.close(); replies.close(); db.close(); Files.deleteIfExists(path) }
    }
}
