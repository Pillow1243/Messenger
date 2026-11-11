package com.offlinemesh.messenger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.offlinemesh.messenger.MessengerApp
import kotlinx.coroutines.launch

/**
 * Receives zero-plaintext FCM data wakeups (`mm_wakeup`) and hydrates encrypted messages or
 * incoming 1:1 WebRTC call offers locally via [com.offlinemesh.messenger.data.OnlineSync].
 */
class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        val wakeupPolicy = PushWakeupPolicy()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = applicationContext as? MessengerApp ?: return
        app.appScope.launch {
            app.online.onNewFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val validated = wakeupPolicy.acceptOrNull(message.data) ?: return
        val app = applicationContext as? MessengerApp ?: return
        app.appScope.launch {
            app.online.handlePushWakeup(validated)
        }
    }
}
