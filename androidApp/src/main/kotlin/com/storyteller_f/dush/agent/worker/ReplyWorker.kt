package com.storyteller_f.dush.agent.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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

        val agent = AppGraph.agentRepository.getAgent(agentId)
        val modelId = agent?.modelId ?: AppGraph.modelRepository.defaultModel()?.id
        if (modelId != null) {
            setForeground(createForegroundInfo(threadId))
        }
        val result = AppGraph.agentRunner.run(threadId, agentId, id.toString())
        return result.fold(
            onSuccess = {
                val latest = AppGraph.chatRepository.messages(threadId).lastOrNull()
                if (latest != null) {
                    AppGraph.notificationHelper.showReplyNotification(
                        threadId = threadId,
                        title = AppGraph.chatRepository.getThread(threadId)?.title ?: "Agent reply",
                        text = latest.content.ifBlank { "Reply finished." },
                    )
                }
                Result.success()
            },
            onFailure = {
                AppGraph.notificationHelper.showReplyNotification(
                    threadId = threadId,
                    title = "Agent reply failed",
                    text = it.message ?: "Unable to generate a reply for $userMessageId.",
                )
                if (runAttemptCount < 1) Result.retry() else Result.failure()
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

        fun enqueue(context: Context, threadId: String, agentId: String, userMessageId: String) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_THREAD_ID, threadId)
                        .putString(KEY_AGENT_ID, agentId)
                        .putString(KEY_USER_MESSAGE_ID, userMessageId)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
