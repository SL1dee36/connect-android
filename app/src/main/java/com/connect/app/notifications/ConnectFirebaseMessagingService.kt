package com.connect.app.notifications

import com.connect.app.ConnectApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = ConnectApplication.instance
        app.prefs.setFcmToken(token)

        val authToken = app.prefs.getToken()
        if (!authToken.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.apiClient.service.registerFcmToken(
                        auth = "Bearer $authToken",
                        body = mapOf("token" to token)
                    )
                } catch (_: Exception) {
                    // Safe fallback, will re-sync on login or socket connect
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val notificationType = data["type"] ?: "message"

        when (notificationType) {
            "incoming_call" -> {
                val caller = data["caller"] ?: ""
                val callerName = data["callerName"] ?: caller
                val callType = data["callType"] ?: "audio"
                val room = data["room"] ?: ""
                if (caller.isNotBlank()) {
                    NotificationHelper.showIncomingCallNotification(
                        context = this,
                        caller = caller,
                        callerName = callerName,
                        callType = callType,
                        room = room
                    )
                }
            }
            "dm" -> {
                val room = data["room"] ?: ""
                val author = data["author"] ?: data["title"] ?: "Собеседник"
                val body = data["body"] ?: remoteMessage.notification?.body ?: "Новое сообщение"
                if (room.isNotBlank()) {
                    NotificationHelper.showMessageNotification(
                        context = this,
                        room = room,
                        author = author,
                        message = body,
                        avatarUrl = null,
                        isGroup = false
                    )
                }
            }
            "group" -> {
                val room = data["room"] ?: ""
                val author = data["author"] ?: data["title"] ?: "Группа"
                val body = data["body"] ?: remoteMessage.notification?.body ?: "Новое сообщение"
                if (room.isNotBlank()) {
                    NotificationHelper.showMessageNotification(
                        context = this,
                        room = room,
                        author = author,
                        message = body,
                        avatarUrl = null,
                        isGroup = true
                    )
                }
            }
            else -> {
                val title = remoteMessage.notification?.title ?: data["title"] ?: "Connect"
                val body = remoteMessage.notification?.body ?: data["body"] ?: ""
                val room = data["room"] ?: ""
                if (room.isNotBlank()) {
                    NotificationHelper.showMessageNotification(
                        context = this,
                        room = room,
                        author = title,
                        message = body,
                        avatarUrl = null,
                        isGroup = false
                    )
                }
            }
        }
    }
}
