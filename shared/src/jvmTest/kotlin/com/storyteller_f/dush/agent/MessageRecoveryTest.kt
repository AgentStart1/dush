package com.storyteller_f.dush.agent

import androidx.paging.*
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.runtime.*
import com.storyteller_f.dush.agent.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRecoveryTest {
    private class Sources {
        var failRefresh = false
        var failAppend = false
        var blockedThread: String? = null
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val created = mutableMapOf<String, Int>()
        var refreshLoads = 0
        fun create(thread: String) = object : PagingSource<Int, ChatMessageEntity>() {
            init { created[thread] = (created[thread] ?: 0) + 1 }
            override fun getRefreshKey(state: PagingState<Int, ChatMessageEntity>): Int = 0
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChatMessageEntity> {
                if (params is LoadParams.Refresh) refreshLoads++
                if (thread == blockedThread) {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                }
                if ((params is LoadParams.Refresh && failRefresh) || (params is LoadParams.Append && failAppend)) {
                    return LoadResult.Error(IllegalStateException("Synthetic database read failure"))
                }
                val start = params.key ?: 0
                val end = (start + params.loadSize).coerceAtMost(60)
                return LoadResult.Page((start until end).map { index ->
                    ChatMessageEntity("$thread-$index", thread, MessageRole.User, "message-$index",
                        MessageStatus.Complete, index.toLong(), 0, null)
                }, if (start == 0) null else (start - 50).coerceAtLeast(0), if (end == 60) null else end)
            }
        }
    }

    private fun scenario(block: suspend TestScope.(WorkspaceHost, Sources) -> Unit) = runTest {
        val path = Files.createTempFile("dush-pager-recovery", ".db")
        val db = openDatabase(path.toString())
        val services = AgentServices(db, MemorySecrets(), InferenceRuntime { _, _, _, _, _, _ -> emptyFlow() })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replies = ForegroundReplies(services.chats, services.agents, services.runner, dispatcher)
        val source = Sources()
        val host = WorkspaceHost(services, replies, dispatcher, source::create)
        try { block(host, source) }
        finally { source.release.complete(Unit); host.close(); replies.close(); db.close(); Files.deleteIfExists(path) }
    }

    @Test fun refreshFailureRetriesThroughPagingWithoutReplacingTheSession() = scenario { host, source ->
        source.failRefresh = true
        host.navigate(WorkspaceRoute.Thread("first")).join()
        val session = requireNotNull(host.state.value.threadHost)
        val presenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        presenter.collect(backgroundScope, session)
        presenter.loadStateFlow.filterNotNull().first { it.refresh is LoadState.Error }
        source.failRefresh = false

        presenter.retry()

        assertEquals(50, presenter.rows.first { it.isNotEmpty() }.size)
        assertSame(session, host.state.value.threadHost)
        assertEquals(2, source.refreshLoads)
    }

    @Test fun appendFailureRetriesOnlyTheFailedPage() = scenario { host, source ->
        host.navigate(WorkspaceRoute.Thread("first")).join()
        val presenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        presenter.collect(backgroundScope, requireNotNull(host.state.value.threadHost))
        presenter.rows.first { it.size == 50 }
        source.failAppend = true
        presenter[49]
        presenter.loadStateFlow.filterNotNull().first { it.append is LoadState.Error }
        source.failAppend = false

        presenter.retry()

        val rows = presenter.rows.first { it.size == 60 }
        assertEquals(60, rows.map { it.id }.distinct().size)
        assertEquals(1, source.refreshLoads)
    }

    @Test fun navigationPublishesTheNewSessionWithoutWaitingForOldLoadCleanup() = scenario { host, source ->
        source.blockedThread = "first"
        host.navigate(WorkspaceRoute.Thread("first")).join()
        val oldSession = requireNotNull(host.state.value.threadHost)
        val oldPresenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        oldPresenter.collect(backgroundScope, oldSession)
        source.entered.await()

        val navigation = host.navigate(WorkspaceRoute.Thread("second"))
        runCurrent()
        assertTrue(navigation.isCompleted, "Navigation must not wait for old query cleanup")
        source.cleaning.await()
        assertFalse(source.release.isCompleted)
        val current = requireNotNull(host.state.value.threadHost)
        assertEquals("second", current.id)
        val presenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        presenter.collect(backgroundScope, current)
        val rows = presenter.rows.first { it.size == 50 }
        assertTrue(rows.all { it.threadId == "second" })

        oldPresenter.retry()
        oldPresenter.refresh()
        runCurrent()
        assertEquals(1, source.created["first"])
        assertSame(current, host.state.value.threadHost)
        source.release.complete(Unit)
        runCurrent()
        assertTrue(presenter.rows.value.all { it.threadId == "second" })
    }

    @Test fun windowShutdownWaitsForPreviouslyCanceledSessions() = scenario { host, source ->
        source.blockedThread = "first"
        host.navigate(WorkspaceRoute.Thread("first")).join()
        val presenter = MessagePresenter(StandardTestDispatcher(testScheduler))
        presenter.collect(backgroundScope, requireNotNull(host.state.value.threadHost))
        source.entered.await()
        host.navigate(WorkspaceRoute.Settings).join()
        source.cleaning.await()

        val closing = async { host.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        source.release.complete(Unit)
        closing.await()
    }
}
