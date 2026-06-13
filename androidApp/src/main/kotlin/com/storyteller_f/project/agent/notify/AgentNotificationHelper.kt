package com.storyteller_f.project.agent.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.storyteller_f.project.BubbleActivity
import com.storyteller_f.project.MainActivity
import com.storyteller_f.project.R

class AgentNotificationHelper(
    private val context: Context,
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GENERATION,
                "Agent replies",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background on-device agent generation"
                setAllowBubbles(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONVERSATION,
                "Agent conversations",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Completed on-device agent replies"
                setAllowBubbles(true)
            }
        )
    }

    fun foregroundNotification(threadId: String): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_GENERATION)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Generating reply")
            .setContentText("Your on-device agent is thinking.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(threadPendingIntent(threadId))
            .build()
    }

    fun showReplyNotification(threadId: String, title: String, text: String) {
        if (!canPostNotifications()) return
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_CONVERSATION)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(threadPendingIntent(threadId))
            .setAutoCancel(true)
            .setBubbleMetadata(bubbleMetadata(threadId))
            .setShortcutId("thread-$threadId")
            .build()
        NotificationManagerCompat.from(context).notify(threadId.hashCode(), notification)
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun threadPendingIntent(threadId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_THREAD)
            .putExtra(EXTRA_THREAD_ID, threadId)
        return PendingIntent.getActivity(
            context,
            threadId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun bubbleMetadata(threadId: String): NotificationCompat.BubbleMetadata? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val intent = Intent(context, BubbleActivity::class.java)
            .setAction(ACTION_OPEN_THREAD)
            .putExtra(EXTRA_THREAD_ID, threadId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            ("bubble-$threadId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.BubbleMetadata.Builder(
            pendingIntent,
            IconCompat.createWithResource(context, R.mipmap.ic_launcher),
        )
            .setDesiredHeight(640)
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()
    }

    companion object {
        const val ACTION_OPEN_THREAD = "com.storyteller_f.project.OPEN_THREAD"
        const val EXTRA_THREAD_ID = "thread_id"
        const val CHANNEL_GENERATION = "agent_generation"
        const val CHANNEL_CONVERSATION = "agent_conversation"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }
}
