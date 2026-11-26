package com.example.notifications

import com.example.data.UserRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyChatFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = Firebase.auth.currentUser?.uid ?: return
        serviceScope.launch {
            UserRepository(applicationContext).updateFcmToken(uid, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"].orEmpty()
        when (type) {
            "INCOMING_CALL" -> {
                val callId = data["callId"].orEmpty()
                val callerName = data["callerName"] ?: message.notification?.title ?: "My Chat User"
                val isVideo = data["callType"].equals("VIDEO", ignoreCase = true)
                if (callId.isNotBlank()) {
                    ChatNotificationHelper.showIncomingCallNotification(
                        context = applicationContext,
                        callId = callId,
                        callerName = callerName,
                        isVideo = isVideo
                    )
                }
                return
            }
            "CALL_ENDED", "CALL_CANCELLED" -> {
                val callId = data["callId"].orEmpty()
                if (callId.isNotBlank()) {
                    ChatNotificationHelper.dismissCallNotification(applicationContext, callId)
                }
                return
            }
        }

        val chatId = data["chatId"].orEmpty()
        val senderName = data["senderName"] ?: message.notification?.title ?: "My Chat"
        val preview = data["preview"] ?: message.notification?.body ?: "Sent you a message"
        if (chatId.isNotBlank()) {
            ChatNotificationHelper.showIncomingMessageNotification(
                context = applicationContext,
                chatId = chatId,
                senderName = senderName,
                messagePreview = preview,
                showPreview = true
            )
        }
    }
}
