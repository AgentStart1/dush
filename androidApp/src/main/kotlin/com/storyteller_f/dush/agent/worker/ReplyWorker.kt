package com.storyteller_f.dush.agent.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.storyteller_f.dush.agent.data.MessageRole
import com.storyteller_f.dush.agent.data.MessageStatus
import com.storyteller_f.dush.agent.AppGraph
import com.storyteller_f.dush.agent.notify.AgentNotificationHelper

class ReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val threadId = inputData.getString(KEY_THREAD_ID) ?: return Result.failure()
        val agentId = inputData.getString(KEY_AGENT_ID) ?: return Result.failure()
        val userMessageId = inputData.getString(KEY_USER_MESSAGE_ID) ?: return Result.failure()
        val bandBindingId = inputData.getString("band_binding_id")
        val bandSessionId = inputData.getString("band_session_id")

        setForeground(createForegroundInfo(threadId))
        val result = coroutineScope {
            val streaming = if (bandBindingId != null && bandSessionId != null) launch {
                AppGraph.chatRepository.observeMessages(threadId)
                    .mapNotNull { messages -> messages.lastOrNull {
                        it.role == MessageRole.Assistant && it.workerId == id.toString() && it.status == MessageStatus.Streaming
                    }?.content?.takeIf { it.isNotBlank() } }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { text -> AppGraph.bands.publishReply(bandBindingId, bandSessionId, text, final = false) }
            } else null
            try { AppGraph.agentRunner.run(threadId, agentId, id.toString()) }
            finally { streaming?.cancelAndJoin() }
        }
        if (bandBindingId != null && bandSessionId != null) {
            val reply = AppGraph.chatRepository.messages(threadId).lastOrNull {
                it.role == com.storyteller_f.dush.agent.data.MessageRole.Assistant && it.workerId == id.toString()
            }
            AppGraph.bands.publishReply(bandBindingId, bandSessionId,
                if (result.isSuccess) reply?.content.orEmpty().ifBlank { "回复已完成，请查看手机" }
                else "回复失败，请在 dush 中检查推理服务连接")
        }
        val thread = AppGraph.chatRepository.getThread(threadId)
        val autoExpand = thread?.bubbleEnabled ?: false
        return result.fold(
            onSuccess = {
                val latest = AppGraph.chatRepository.messages(threadId).lastOrNull()
                if (latest != null) {
                    AppGraph.notificationHelper.showReplyNotification(
                        threadId = threadId,
                        title = thread?.title ?: "Agent reply",
                        text = latest.content.ifBlank { "Reply finished." },
                        autoExpandBubble = autoExpand,
                    )
                }
                Result.success()
            },
            onFailure = {
                AppGraph.notificationHelper.showReplyNotification(
                    threadId = threadId,
                    title = "Agent reply failed",
                    text = it.message ?: "Unable to generate a reply for $userMessageId.",
                    autoExpandBubble = autoExpand,
                )
                Result.failure()
            },
        )
    }

    private fun createForegroundInfo(threadId: String): ForegroundInfo {
        val notification = AppGraph.notificationHelper.foregroundNotification(threadId)
        return ForegroundInfo(
            AgentNotificationHelper.FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        const val KEY_THREAD_ID = "thread_id"
        const val KEY_AGENT_ID = "agent_id"
        const val KEY_USER_MESSAGE_ID = "user_message_id"

        fun enqueue(context: Context, threadId: String, agentId: String, userMessageId: String, bandBindingId: String? = null, bandSessionId: String? = null) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_THREAD_ID, threadId)
                        .putString(KEY_AGENT_ID, agentId)
                        .putString(KEY_USER_MESSAGE_ID, userMessageId)
                        .putString("band_binding_id", bandBindingId)
                        .putString("band_session_id", bandSessionId)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("reply-$threadId", androidx.work.ExistingWorkPolicy.REPLACE, request)
        }
    }
}
