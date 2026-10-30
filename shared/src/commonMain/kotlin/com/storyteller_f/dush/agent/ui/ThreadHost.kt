package com.storyteller_f.dush.agent.ui

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.storyteller_f.dush.agent.data.ChatMessageEntity
import kotlinx.coroutines.*

/** One open conversation owns one Pager. Cancellation never waits on the navigation path.
 * The parent window job retains ownership until all canceled children finish cleanup.
 */
class ThreadHost internal constructor(val id: String, dispatcher: CoroutineDispatcher, parent: Job,
    source: () -> PagingSource<Int, ChatMessageEntity>) {
    private val job = SupervisorJob(parent)
    private val scope = CoroutineScope(job + dispatcher)
    val messages = Pager(PagingConfig(pageSize = 50, initialLoadSize = 50,
        prefetchDistance = 5, enablePlaceholders = false), pagingSourceFactory = source).flow.cachedIn(scope)

    internal fun cancel() { job.cancel() }
}
