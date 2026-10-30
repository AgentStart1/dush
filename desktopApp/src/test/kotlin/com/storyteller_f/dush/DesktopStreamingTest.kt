package com.storyteller_f.dush

import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.ui.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class DesktopStreamingTest {
    private suspend fun awaitMessage(host: ThreadHost, text: String, status: MessageStatus) = coroutineScope {
        val rows = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
        val presenter = object : PagingDataPresenter<ChatMessageEntity>(coroutineContext) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<ChatMessageEntity>) {
                rows.value = snapshot().items
            }
        }
        val collection = launch { host.messages.collectLatest { presenter.collectFrom(it) } }
        try { rows.first { list -> list.any { it.content == text && it.status == status } } }
        finally { collection.cancelAndJoin() }
    }
    @Test fun desktopWorkspaceStreamsHttpReplyAndRestoresItAfterRestart() = runTest {
        val directory = Files.createTempDirectory("dush-desktop-stream")
        val partial = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write("""data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"first"},"finish_reason":null}]}

""".toByteArray())
                body.flush()
                partial.complete(Unit)
                // HttpServer has a synchronous callback boundary; keep blocking isolated here.
                runBlocking { withTimeout(10_000) { finish.await() } }
                body.write("""data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":" second"},"finish_reason":null}]}

data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

""".toByteArray())
            }
        }
        server.start()
        try {
            val graph = DesktopGraph.open(directory)
            val host = WorkspaceHost(graph.services, graph.replies, graph.coordination)
            val thread = try {
                graph.services.connections.save(ConnectionEntity("service", "Synthetic", "LlmdHttp", "http://127.0.0.1:${server.address.port}/v1", "test-model", 1, true))
                host.newThread().join()
                val thread = (host.state.value.route as WorkspaceRoute.Thread).id
                host.input("synthetic prompt").join()

                host.send().join()
                partial.await()
                awaitMessage(requireNotNull(host.state.value.threadHost), "first", MessageStatus.Streaming)
                assertTrue(thread in host.state.value.active)
                host.navigate(WorkspaceRoute.Connections).join()
                finish.complete(Unit)
                graph.replies.active.first { it.isEmpty() }
                host.navigate(WorkspaceRoute.Thread(thread)).join()
                awaitMessage(requireNotNull(host.state.value.threadHost), "first second", MessageStatus.Complete)
                thread
            } finally { finish.complete(Unit); host.close(); graph.close() }

            val reopened = DesktopGraph.open(directory)
            try {
                val rows = reopened.services.chats.messages(thread)
                assertEquals(listOf("synthetic prompt", "first second"), rows.map { it.content })
                assertTrue(rows.all { it.status == MessageStatus.Complete })
            } finally { reopened.close() }
        } finally { finish.complete(Unit); server.stop(0); directory.toFile().deleteRecursively() }
    }
}
