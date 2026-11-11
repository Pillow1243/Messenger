package com.offlinemesh.messenger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.offlinemesh.messenger.MessengerApp
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.core.mesh.HeartbeatManager

class MeshService : Service() {

    private var heartbeat: HeartbeatManager? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MessengerApp
        if (heartbeat == null) {
            heartbeat = HeartbeatManager(app.meshManager, app.settingsRepository)
            heartbeat?.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeat?.stop()
        heartbeat = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val channelId = "mesh_channel"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.mesh_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mesh_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }
}
