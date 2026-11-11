package com.offlinemesh.messenger.service

import android.Manifest
import android.annotation.SuppressLint
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
import com.offlinemesh.messenger.MainActivity
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.call.CallForegroundService
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.data.AppSettings
import com.offlinemesh.messenger.data.ConversationType

/**
 * Local & wakeup-hydrated notification dispatcher.
 *
 * Security invariant: notification titles and bodies are rendered strictly from locally decrypted
 * state inside this process; zero plaintext ever transits FCM or push logs.
 */
object MessageNotifier {

    const val EXTRA_CONVERSATION = "conversation_id"
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_CALL_ACTION = "call_action"
    const val CALL_ACTION_ANSWER = "answer"
    const val CALL_ACTION_OPEN = "open"

    private const val CHANNEL_DIRECT = "messages_direct_v2"
    private const val CHANNEL_GROUP = "messages_group_v2"
    private const val CHANNEL_CHANNEL = "messages_channel_v2"
    const val CHANNEL_CALLS = "calls_incoming_v2"

    private const val GROUP_KEY_MESSAGES = "com.offlinemesh.messenger.MESSAGES"
    private const val SUMMARY_NOTIFICATION_ID = 0x4D4D_0001

    fun ensureChannel(context: Context) = ensureChannels(context)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_DIRECT,
                context.getString(R.string.notifications_direct),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notifications_messages_desc)
                setShowBadge(true)
            },
            NotificationChannel(
                CHANNEL_GROUP,
                context.getString(R.string.notifications_groups),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notifications_messages_desc)
                setShowBadge(true)
            },
            NotificationChannel(
                CHANNEL_CHANNEL,
                context.getString(R.string.notifications_channels),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notifications_messages_desc)
                setShowBadge(true)
            },
            NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.notifications_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notifications_calls_desc)
                setShowBadge(true)
            }
        )
        channels.forEach { ch ->
            if (manager.getNotificationChannel(ch.id) == null) {
                manager.createNotificationChannel(ch)
            }
        }
    }

    /** Pure policy check for whether a conversation notification should fire. */
    fun shouldNotifyConversation(
        conversation: ConversationEntity?,
        settings: AppSettings?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (settings != null && !settings.notificationsEnabled) return false
        if (conversation != null) {
            if (conversation.muted) {
                val until = conversation.mutedUntil
                if (until == null || until > nowMs) return false
            }
            if (settings != null) {
                return when (conversation.type) {
                    ConversationType.GROUP -> settings.notifyGroups
                    ConversationType.CHANNEL -> settings.notifyChannels
                    else -> settings.notifyDirect
                }
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun notifyMessage(
        context: Context,
        conversationId: String,
        title: String,
        body: String,
        conversation: ConversationEntity? = null,
        settings: AppSettings? = null
    ) {
        if (!shouldNotifyConversation(conversation, settings)) return
        if (!hasPostPermission(context)) return
        ensureChannels(context)

        val channelId = when (conversation?.type) {
            ConversationType.GROUP -> CHANNEL_GROUP
            ConversationType.CHANNEL -> CHANNEL_CHANNEL
            else -> CHANNEL_DIRECT
        }

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
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setGroup(GROUP_KEY_MESSAGES)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.notify(conversationId.hashCode(), notification)
            nm.notify(SUMMARY_NOTIFICATION_ID, summary)
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyIncomingCall(
        context: Context,
        callId: String,
        conversationId: String,
        callerName: String,
        isVideo: Boolean,
        settings: AppSettings? = null
    ) {
        if (settings != null && (!settings.notificationsEnabled || !settings.notifyCalls)) return
        if (!hasPostPermission(context)) return
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CONVERSATION, conversationId)
            putExtra(EXTRA_CALL_ACTION, CALL_ACTION_OPEN)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CONVERSATION, conversationId)
            putExtra(EXTRA_CALL_ACTION, CALL_ACTION_ANSWER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val answerPending = PendingIntent.getActivity(
            context,
            callId.hashCode() xor 0x1111,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallForegroundService::class.java).apply {
            action = CallForegroundService.ACTION_DECLINE_CALL
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePending = PendingIntent.getService(
            context,
            callId.hashCode() xor 0x2222,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = context.getString(
            if (isVideo) R.string.call_incoming_video else R.string.call_incoming_audio
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName)
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setFullScreenIntent(openPending, true)
            .addAction(0, context.getString(R.string.call_decline), declinePending)
            .addAction(0, context.getString(R.string.call_accept), answerPending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(callNotificationId(callId), notification)
        }
    }

    fun cancelConversation(context: Context, conversationId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(conversationId.hashCode()) }
    }

    fun cancelCall(context: Context, callId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(callNotificationId(callId)) }
    }

    fun cancelAll(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }

    private fun callNotificationId(callId: String): Int = 0x4341_0000 xor callId.hashCode()

    private fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
