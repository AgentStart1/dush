package com.storyteller_f.dush.agent

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.runtime.*
import com.storyteller_f.dush.agent.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

internal class MessagePresenter(context: CoroutineContext) : PagingDataPresenter<ChatMessageEntity>(context) {
    val rows = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    override suspend fun presentPagingDataEvent(event: PagingDataEvent<ChatMessageEntity>) {
        rows.value = snapshot().items
    }
    fun collect(scope: CoroutineScope, host: ThreadHost) = scope.launch {
        host.messages.collectLatest { collectFrom(it) }
    }
}

class HistoryPagingTest {
    private fun scenario(block: suspend TestScope.(AgentServices, WorkspaceHost, AgentDatabase, String) -> Unit) = runTest {
        val path = Files.createTempFile("dush-pager", ".db")
        val db = openDatabase(path.toString())
        val services = AgentServices(db, MemorySecrets(), InferenceRuntime { _, _, _, _, _, _ -> emptyFlow() })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replies = ForegroundReplies(services.chats, services.agents, services.runner, dispatcher)
        val host = WorkspaceHost(services, replies, dispatcher)
        try {
            val thread = services.chats.createThread()
            repeat(125) { index -> db.chatDao().upsertMessage(ChatMessageEntity("id-${200-index}", thread,
                MessageRole.User, "message-$index", MessageStatus.Complete, 1, 1, null)) }
            host.navigate(WorkspaceRoute.Thread(thread)).join()
            block(services, host, db, thread)
        } finally { host.close(); replies.close(); db.close(); Files.deleteIfExists(path) }
    }

    @Test fun roomPagerLoadsOlderPagesInInsertionOrder() = scenario { _, host, _, _ ->
        val presenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        presenter.collect(backgroundScope, requireNotNull(host.state.value.threadHost))
        val initial = presenter.rows.first { it.size == 50 }
        assertEquals((124 downTo 75).map { "message-$it" }, initial.map { it.content })
        presenter[49]
        val second = presenter.rows.first { it.size == 100 }
        assertEquals((124 downTo 25).map { "message-$it" }, second.map { it.content })
        presenter[99]
        val all = presenter.rows.first { it.size == 125 }
        assertEquals((124 downTo 0).map { "message-$it" }, all.map { it.content })
        assertEquals(125, all.map { it.id }.distinct().size)
    }

    @Test fun roomInvalidationDeliversNewMessagesAndStreamingUpdates() = scenario { services, host, _, thread ->
        val presenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        presenter.collect(backgroundScope, requireNotNull(host.state.value.threadHost))
        presenter.rows.first { it.size == 50 }
        val reply = services.chats.createAssistantMessage(thread, "pager-owner")
        presenter.rows.first { it.firstOrNull()?.id == reply.id }
        services.chats.updateAssistantMessage(reply.id, "streamed partial", MessageStatus.Streaming)
        presenter.rows.first { it.firstOrNull()?.content == "streamed partial" }
        services.chats.updateAssistantMessage(reply.id, "complete reply", MessageStatus.Complete)
        presenter.rows.first { it.firstOrNull()?.status == MessageStatus.Complete && it.first().content == "complete reply" }
    }

    @Test fun reopeningAThreadCreatesANewPagerAtTheLatestPage() = scenario { _, host, _, thread ->
        val first = requireNotNull(host.state.value.threadHost)
        val presenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        presenter.collect(backgroundScope, first)
        presenter.rows.first { it.size == 50 }
        presenter[49]
        presenter.rows.first { it.size == 100 }
        host.navigate(WorkspaceRoute.Chats).join()
        host.navigate(WorkspaceRoute.Thread(thread)).join()
        val reopened = requireNotNull(host.state.value.threadHost)
        assertNotSame(first, reopened)
        val newPresenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        newPresenter.collect(backgroundScope, reopened)
        assertEquals("message-124", newPresenter.rows.first { it.size == 50 }.first().content)
    }
}
