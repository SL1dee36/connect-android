package com.connect.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.connect.app.ConnectApplication

class CallNotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REJECT_CALL = "com.connect.app.ACTION_REJECT_CALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REJECT_CALL) {
            val caller = intent.getStringExtra(NotificationHelper.EXTRA_CALLER) ?: ""
            val room = intent.getStringExtra(NotificationHelper.EXTRA_ROOM) ?: ""
            val callType = intent.getStringExtra(NotificationHelper.EXTRA_CALL_TYPE) ?: "audio"

            val app = ConnectApplication.instance
            app.socketManager.rejectCall(caller, room, callType)
            NotificationHelper.cancelCallNotification(context)
        }
    }
}
