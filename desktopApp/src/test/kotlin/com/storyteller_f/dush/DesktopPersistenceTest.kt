package com.storyteller_f.dush

import com.storyteller_f.dush.agent.data.MessageStatus
import com.storyteller_f.dush.agent.data.ConnectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DesktopPersistenceTest {
    @Test fun desktopGraphRestoresConnectionAgentAndHistory() = runTest {
        val directory = Files.createTempDirectory("dush-desktop-test")
        try {
            val graph = DesktopGraph.open(directory)
            val thread = try {
                graph.services.connections.save(ConnectionEntity("service", "Synthetic", "LlmdHttp", "http://localhost:11435/v1", "test-model", 1, true))
                val agent = graph.services.agents.defaultAgent()
                val thread = graph.services.chats.createThread(agent.id)
                graph.services.chats.appendUserMessage(thread, "synthetic prompt")
                val reply = graph.services.chats.createAssistantMessage(thread, "dead-process")
                graph.services.chats.updateAssistantMessage(reply.id, "saved partial", MessageStatus.Streaming)
                thread
            } finally { graph.close() }

            val reopened = DesktopGraph.open(directory)
            try {
                assertEquals("test-model", reopened.services.connections.defaultConnection()?.model)
                assertEquals("service", reopened.services.agents.defaultAgent().connectionId)
                val rows = reopened.services.chats.messages(thread)
                assertEquals(listOf("synthetic prompt", "saved partial"), rows.map { it.content })
                assertEquals(MessageStatus.Canceled, rows.last().status)
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun secondDesktopOwnerCannotRecoverLiveDatabase() = runTest {
        val directory = Files.createTempDirectory("dush-owner-test")
        try {
            val first = DesktopGraph.open(directory)
            try { assertFailsWith<IllegalStateException> { DesktopGraph.open(directory) } }
            finally { first.close() }
            val reopened = DesktopGraph.open(directory)
            reopened.close()
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun nativeCredentialSurvivesAdapterRecreation() = runTest {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("dush-secret-test")
        val id = UUID.randomUUID().toString()
        try {
            DesktopConnectionSecrets(directory).write(id, "synthetic-test-key")
            assertEquals("synthetic-test-key", DesktopConnectionSecrets(directory).read(id))
            assertFalse(Files.walk(directory).use { files -> files.filter(Files::isRegularFile).anyMatch { Files.readString(it).contains("synthetic-test-key") } })
        } finally {
            DesktopConnectionSecrets(directory).delete(id)
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun clearingNativeCredentialRemovesItFromSubsequentReads() = runTest {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("dush-secret-clear-test")
        val id = UUID.randomUUID().toString()
        val secrets = DesktopConnectionSecrets(directory)
        try {
            secrets.write(id, "synthetic-test-key")
            secrets.write(id, "")
            assertEquals("", DesktopConnectionSecrets(directory).read(id))
        } finally { secrets.delete(id); directory.toFile().deleteRecursively() }
    }
}
