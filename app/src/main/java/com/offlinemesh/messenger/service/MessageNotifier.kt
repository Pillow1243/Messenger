package com.offlinemesh.messenger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.offlinemesh.messenger.MainActivity
import com.offlinemesh.messenger.R

/**
 * Local notifications for messages that arrive while the app is in the background.
 *
 * Notification text is the decrypted body, which exists only in this process; nothing is sent to a
 * push service. Tapping the notification opens the conversation (the activity is `singleTask`, so
 * `onNewIntent` receives the extra and the existing task is reused).
 */
object MessageNotifier {

    const val EXTRA_CONVERSATION = "conversation_id"
    private const val CHANNEL_ID = "messages_v1"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notifications_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notifications_messages_desc)
                setShowBadge(true)
            }
        )
    }

    fun notifyMessage(
        context: Context,
        conversationId: String,
        title: String,
        body: String
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CONVERSATION, conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(conversationId.hashCode(), notification)
        }
    }
}
