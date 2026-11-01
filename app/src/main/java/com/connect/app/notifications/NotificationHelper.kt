package com.connect.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.connect.app.ConnectApplication
import com.connect.app.MainActivity
import com.connect.app.R
import com.connect.app.ui.screens.call.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NotificationHelper {

    const val CHANNEL_DM = "channel_dm_messages"
    const val CHANNEL_GROUPS = "channel_group_messages"
    const val CHANNEL_CALLS = "channel_incoming_calls"
    const val CHANNEL_SYSTEM = "channel_system_alerts"

    const val EXTRA_ROOM = "extra_room"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_CALL_TYPE = "extra_call_type"
    const val EXTRA_CALLER = "extra_caller"
    const val EXTRA_CALLER_NAME = "extra_caller_name"
    const val EXTRA_ACTION_ANSWER = "extra_action_answer"
    const val EXTRA_ACTION_REJECT = "extra_action_reject"

    private const val CALL_NOTIFICATION_ID = 9999

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Direct Messages
            val dmChannel = NotificationChannel(
                CHANNEL_DM,
                "Личные сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых личных сообщениях"
                enableVibration(true)
                setShowBadge(true)
            }

            // 2. Groups
            val grpChannel = NotificationChannel(
                CHANNEL_GROUPS,
                "Групповые чаты",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления из групповых чатов"
                enableVibration(true)
                setShowBadge(true)
            }

            // 3. Incoming Calls
            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Входящие звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Оповещения о входящих аудио- и видеозвонках"
                enableVibration(true)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                setSound(Settings.System.DEFAULT_RINGTONE_URI, audioAttributes)
                setShowBadge(true)
            }

            // 4. System / Friend Requests
            val sysChannel = NotificationChannel(
                CHANNEL_SYSTEM,
                "Заявки в друзья и система",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Заявки в друзья и системные события"
                enableVibration(true)
            }

            manager.createNotificationChannels(listOf(dmChannel, grpChannel, callsChannel, sysChannel))
        }
    }

    fun showMessageNotification(
        context: Context,
        room: String,
        author: String,
        message: String,
        avatarUrl: String?,
        isGroup: Boolean = false
    ) {
        val app = ConnectApplication.instance
        if (app.prefs.isRoomMuted(room)) return

        val isSoundEnabled = if (isGroup) app.prefs.isGroupSoundEnabled() else app.prefs.isDmSoundEnabled()
        val isVibrationEnabled = if (isGroup) app.prefs.isGroupVibrationEnabled() else app.prefs.isDmVibrationEnabled()
        val isPreviewEnabled = if (isGroup) app.prefs.isGroupPreviewEnabled() else app.prefs.isDmPreviewEnabled()
        val customRingtone = if (isGroup) app.prefs.getGroupRingtoneUri() else app.prefs.getDmRingtoneUri()

        val channelId = if (isGroup) CHANNEL_GROUPS else CHANNEL_DM
        val contentText = if (isPreviewEnabled) message else "Новое сообщение"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_TITLE, author)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            room.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(author)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (!isVibrationEnabled) {
            builder.setVibrate(longArrayOf(0))
        }

        if (!isSoundEnabled) {
            builder.setSilent(true)
        } else if (!customRingtone.isNullOrBlank()) {
            builder.setSound(Uri.parse(customRingtone))
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Load avatar bitmap asynchronously if available
        if (!avatarUrl.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                val fullUrl = app.apiClient.getMediaUrl(avatarUrl)
                val bitmap = loadBitmap(context, fullUrl)
                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                }
                manager.notify(room.hashCode(), builder.build())
            }
        } else {
            manager.notify(room.hashCode(), builder.build())
        }
    }

    fun showIncomingCallNotification(
        context: Context,
        caller: String,
        callerName: String,
        callType: String,
        room: String
    ) {
        val app = ConnectApplication.instance
        val isVideo = callType.equals("video", ignoreCase = true)
        val customRingtone = app.prefs.getCallsRingtoneUri()
        val isVibration = app.prefs.isCallsVibrationEnabled()

        // Fullscreen Intent to open CallActivity
        val fullScreenIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALLER, caller)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_CALL_TYPE, callType)
            putExtra(EXTRA_ROOM, room)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            1001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Answer Action
        val answerIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALLER, caller)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_CALL_TYPE, callType)
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_ACTION_ANSWER, true)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            1002,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Reject Action via Receiver
        val rejectIntent = Intent(context, CallNotificationActionReceiver::class.java).apply {
            action = CallNotificationActionReceiver.ACTION_REJECT_CALL
            putExtra(EXTRA_CALLER, caller)
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_CALL_TYPE, callType)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            context,
            1003,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = if (!customRingtone.isNullOrBlank()) {
            Uri.parse(customRingtone)
        } else {
            Settings.System.DEFAULT_RINGTONE_URI
        }

        val title = callerName.ifBlank { caller }
        val subtitle = if (isVideo) "Входящий видеозвонок..." else "Входящий аудиозвонок..."

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(android.R.drawable.ic_menu_call, "Ответить", answerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отклонить", rejectPendingIntent)

        if (isVibration) {
            builder.setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000))
        } else {
            builder.setVibrate(longArrayOf(0))
        }

        builder.setSound(soundUri)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(CALL_NOTIFICATION_ID, builder.build())
    }

    fun cancelCallNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(CALL_NOTIFICATION_ID)
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        return try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
            val result = (loader.execute(request) as? SuccessResult)?.drawable
            (result as? BitmapDrawable)?.bitmap
        } catch (_: Exception) {
            null
        }
    }
}
