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
        if (type == "INCOMING_CALL" || type == "CALL_ENDED" || type == "CALL_CANCELLED") {
            return
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
