package com.offlinemesh.messenger.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.offlinemesh.messenger.MainActivity
import com.offlinemesh.messenger.MessengerApp
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.service.MessageNotifier
import kotlinx.coroutines.launch

/**
 * Keeps an active 1:1 WebRTC voice/video session alive while the user switches apps, and processes
 * notification Decline / Hangup actions directly without forcing an Activity launch.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? MessengerApp
        when (intent?.action) {
            ACTION_DECLINE_CALL -> {
                val callId = intent.getStringExtra(MessageNotifier.EXTRA_CALL_ID).orEmpty()
                if (callId.isNotBlank() && app != null) {
                    MessageNotifier.cancelCall(this, callId)
                    app.appScope.launch { app.calls.declineIncomingCall(callId) }
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HANGUP_CALL -> {
                if (app != null) {
                    app.appScope.launch { app.calls.endCall("hangup") }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val peerName = intent?.getStringExtra(EXTRA_PEER_NAME)
                    ?.ifBlank { getString(R.string.app_name) }
                    ?: getString(R.string.app_name)
                val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) ?: false
                val notification = buildOngoingNotification(peerName, isVideo)
                startCallForegroundSafely(notification, isVideo)
                return START_NOT_STICKY
            }
        }
    }

    private fun startCallForegroundSafely(notification: Notification, isVideo: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasMic = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val hasCam = isVideo && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                var fgsType = 0
                if (hasMic) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (hasCam) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (fgsType != 0) {
                    startForeground(ONGOING_CALL_NOTIFICATION_ID, notification, fgsType)
                } else {
                    stopSelf()
                }
            } else {
                startForeground(ONGOING_CALL_NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun buildOngoingNotification(peerName: String, isVideo: Boolean): Notification {
        MessageNotifier.ensureChannels(this)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0x4341,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangupIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_HANGUP_CALL
        }
        val hangupPending = PendingIntent.getService(
            this,
            0x4342,
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MessageNotifier.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peerName)
            .setContentText(
                getString(if (isVideo) R.string.call_ongoing_video else R.string.call_ongoing_audio)
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.call_end), hangupPending)
            .build()
    }

    companion object {
        private const val ONGOING_CALL_NOTIFICATION_ID = 0x4D4D_00C1
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_IS_VIDEO = "is_video"

        const val ACTION_START = "com.offlinemesh.messenger.call.START"
        const val ACTION_STOP = "com.offlinemesh.messenger.call.STOP"
        const val ACTION_DECLINE_CALL = "com.offlinemesh.messenger.call.DECLINE"
        const val ACTION_HANGUP_CALL = "com.offlinemesh.messenger.call.HANGUP"

        fun start(context: Context, peerName: String, isVideo: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
        }
    }
}
