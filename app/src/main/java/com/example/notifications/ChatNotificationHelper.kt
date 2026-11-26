package com.example.notifications

import android.Manifest
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
import com.example.MainActivity
import com.example.R

object ChatNotificationHelper {
    private const val CHANNEL_ID = "my_chat_messages"
    private const val CHANNEL_NAME = "Messages"
    private const val CALL_CHANNEL_ID = "my_chat_calls"
    private const val CALL_CHANNEL_NAME = "Incoming Calls"

    const val EXTRA_CHAT_ID = "extra_chat_id"
    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_CALL_ACTION = "extra_call_action"
    const val ACTION_ACCEPT_CALL = "ACTION_ACCEPT_CALL"
    const val ACTION_REJECT_CALL = "ACTION_REJECT_CALL"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val msgChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming private chat message alerts"
            }
            manager.createNotificationChannel(msgChannel)

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                CALL_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming audio and video call alerts"
                enableVibration(true)
            }
            manager.createNotificationChannel(callChannel)
        }
    }

    fun showIncomingMessageNotification(
        context: Context,
        chatId: String,
        senderName: String,
        messagePreview: String,
        showPreview: Boolean
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (showPreview && messagePreview.isNotBlank()) {
            messagePreview
        } else {
            "Sent you a private message"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName.ifBlank { "My Chat" })
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(chatId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked at runtime
        }
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        callerName: String,
        isVideo: Boolean
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALL_ACTION, ACTION_ACCEPT_CALL)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            (callId + "_open").hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_REJECT_CALL
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            (callId + "_decline").hashCode(),
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callLabel = if (isVideo) "Incoming video call" else "Incoming audio call"
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(callerName.ifBlank { "My Chat User" })
            .setContentText(callLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Answer", openPendingIntent)
            .addAction(0, "Decline", declinePendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(callId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked at runtime
        }
    }

    fun dismissCallNotification(context: Context, callId: String) {
        NotificationManagerCompat.from(context).cancel(callId.hashCode())
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }
}
