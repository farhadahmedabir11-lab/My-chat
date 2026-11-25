package com.example.data

import android.content.Context
import com.example.R
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import com.example.model.PrivateChat
import com.example.model.PublicProfile
import com.example.model.UserAccount
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before messaging.")

    fun observeUserChats(uid: String): Flow<List<PrivateChat>> = flow {
        if (uid.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "chats"
        emitAll(
            db.collection(path)
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(PrivateChat::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { chat ->
                            chat.lastMessageTime ?: chat.updatedAt ?: chat.createdAt ?: Timestamp(0, 0)
                        }
                }
                .catch { error ->
                    logStreamErrorIfSignedIn(uid, error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeChat(chatId: String, uid: String): Flow<PrivateChat?> = flow {
        if (chatId.isBlank() || uid.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "chats/$chatId"
        emitAll(
            db.document(path)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(PrivateChat::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfSignedIn(uid, error, OperationType.GET, path)
                    throw error
                }
        )
    }

    fun observeMessages(chatId: String, uid: String): Flow<List<ChatMessage>> = flow {
        if (chatId.isBlank() || uid.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "chats/$chatId/messages"
        emitAll(
            db.collection("chats").document(chatId).collection("messages")
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logStreamErrorIfSignedIn(uid, error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun getOrCreateChat(
        currentUser: UserAccount,
        peer: PublicProfile
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (peer.uid.isBlank() || peer.uid == uid) {
            return Result.failure(IllegalArgumentException("Cannot start a chat with yourself."))
        }
        val chatId = ChatUtils.deterministicChatId(uid, peer.uid)
        val chatRef = db.collection("chats").document(chatId)
        return try {
            val existing = chatRef.get().await()
            if (!existing.exists()) {
                val sorted = ChatUtils.sortedParticipants(uid, peer.uid)
                val now = FieldValue.serverTimestamp()
                val payload = mapOf(
                    "participantIds" to sorted,
                    "participantNames" to mapOf(
                        uid to currentUser.fullName,
                        peer.uid to peer.fullName
                    ),
                    "participantUsernames" to mapOf(
                        uid to currentUser.username,
                        peer.uid to peer.username
                    ),
                    "participantColors" to mapOf(
                        uid to currentUser.avatarColorHex,
                        peer.uid to peer.avatarColorHex
                    ),
                    "lastMessageText" to "",
                    "lastMessageSenderId" to "",
                    "lastMessageStatus" to MessageStatus.SENT.name,
                    "unreadCounts" to mapOf(
                        sorted[0] to 0L,
                        sorted[1] to 0L
                    ),
                    "createdAt" to now,
                    "updatedAt" to now
                )
                FreeTierQuotaManager.canPerformFirestoreWrite(1L).getOrThrow()
                chatRef.set(payload).await()
                FreeTierQuotaManager.recordFirestoreWrites(1L)
            }
            Result.success(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            handleFirestoreError(e, OperationType.CREATE, chatRef.path)
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        chatId: String,
        recipientId: String,
        rawText: String,
        replyTo: ChatMessage? = null,
        replySenderName: String? = null
    ): Result<Unit> {
        val trimmedText = rawText.trim()
        if (trimmedText.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        if (trimmedText.length > 2000) {
            return Result.failure(IllegalArgumentException("Message must be 2000 characters or less."))
        }
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val chatRef = db.collection("chats").document(chatId)
        val msgRef = chatRef.collection("messages").document()
        return try {
            FreeTierQuotaManager.canPerformFirestoreWrite(2L).getOrThrow()
            val sorted = ChatUtils.sortedParticipants(uid, recipientId)
            val now = FieldValue.serverTimestamp()
            val messagePayload = mutableMapOf<String, Any>(
                "chatId" to chatId,
                "senderId" to uid,
                "recipientId" to recipientId,
                "participantIds" to sorted,
                "text" to trimmedText,
                "status" to MessageStatus.SENT.name,
                "deleted" to false,
                "createdAt" to now,
                "updatedAt" to now
            )
            if (replyTo != null && replyTo.id.isNotBlank()) {
                messagePayload["replyToMessageId"] = replyTo.id.take(128)
                val preview = when {
                    replyTo.deleted -> "Deleted message"
                    replyTo.text.isNotBlank() -> replyTo.text.take(120)
                    replyTo.parsedType == com.example.model.MessageType.IMAGE -> "📷 Photo"
                    replyTo.parsedType == com.example.model.MessageType.VIDEO -> "🎬 Video"
                    else -> "Message"
                }
                messagePayload["replyToText"] = preview
                if (!replySenderName.isNullOrBlank()) {
                    messagePayload["replyToSenderName"] = replySenderName.take(60)
                }
            }

            val batch = db.batch()
            batch.set(msgRef, messagePayload)
            batch.update(
                chatRef,
                mapOf(
                    "lastMessageText" to trimmedText.take(240),
                    "lastMessageSenderId" to uid,
                    "lastMessageStatus" to MessageStatus.SENT.name,
                    "lastMessageTime" to now,
                    "unreadCounts.$recipientId" to FieldValue.increment(1L),
                    "unreadCounts.$uid" to 0L,
                    "updatedAt" to now
                )
            )
            batch.commit().await()
            FreeTierQuotaManager.recordFirestoreWrites(2L)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            handleFirestoreError(e, OperationType.WRITE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun markIncomingDelivered(
        chats: List<PrivateChat>,
        uid: String
    ) {
        if (uid.isBlank() || auth.currentUser?.uid != uid) return
        val sentFromOthers = chats.filter {
            it.lastMessageSenderId.isNotBlank() &&
                it.lastMessageSenderId != uid &&
                it.lastMessageStatus == MessageStatus.SENT.name
        }
        if (sentFromOthers.isEmpty()) return
        if (FreeTierQuotaManager.canPerformFirestoreWrite(sentFromOthers.size.toLong()).isFailure) return

        for (chat in sentFromOthers) {
            try {
                db.collection("chats").document(chat.id)
                    .update(
                        mapOf(
                            "lastMessageStatus" to MessageStatus.DELIVERED.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()
                FreeTierQuotaManager.recordFirestoreWrites(1L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(e, OperationType.UPDATE, "chats/${chat.id}")
                }
            }
        }
    }

    suspend fun markMessagesDeliveredOrSeen(
        chatId: String,
        chat: PrivateChat?,
        messages: List<ChatMessage>,
        uid: String,
        sendReadReceipts: Boolean
    ) {
        if (chatId.isBlank() || uid.isBlank() || auth.currentUser?.uid != uid) return
        val targetStatus = if (sendReadReceipts) MessageStatus.SEEN else MessageStatus.DELIVERED
        val toUpdate = messages.filter { msg ->
            msg.recipientId == uid &&
                msg.id.isNotBlank() &&
                when (msg.parsedStatus) {
                    MessageStatus.SENT -> true
                    MessageStatus.DELIVERED -> targetStatus == MessageStatus.SEEN
                    MessageStatus.SEEN -> false
                }
        }

        val needsUnreadReset = (chat?.unreadFor(uid) ?: 0) > 0
        val needsChatStatusUpdate = chat != null &&
            chat.lastMessageSenderId.isNotBlank() &&
            chat.lastMessageSenderId != uid &&
            MessageStatus.fromRaw(chat.lastMessageStatus).ordinal < targetStatus.ordinal

        if (toUpdate.isEmpty() && !needsUnreadReset && !needsChatStatusUpdate) return
        val writesNeeded = toUpdate.take(250).size.toLong() + if (needsUnreadReset || needsChatStatusUpdate) 1L else 0L
        if (FreeTierQuotaManager.canPerformFirestoreWrite(writesNeeded).isFailure) return

        try {
            val batch = db.batch()
            val now = FieldValue.serverTimestamp()
            toUpdate.take(250).forEach { msg ->
                val msgRef = db.collection("chats").document(chatId)
                    .collection("messages").document(msg.id)
                batch.update(
                    msgRef,
                    mapOf(
                        "status" to targetStatus.name,
                        "updatedAt" to now
                    )
                )
            }
            if (needsUnreadReset || needsChatStatusUpdate) {
                val chatUpdates = mutableMapOf<String, Any>(
                    "unreadCounts.$uid" to 0L,
                    "updatedAt" to now
                )
                if (needsChatStatusUpdate) {
                    chatUpdates["lastMessageStatus"] = targetStatus.name
                }
                batch.update(db.collection("chats").document(chatId), chatUpdates)
            }
            batch.commit().await()
            FreeTierQuotaManager.recordFirestoreWrites(writesNeeded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            if (auth.currentUser?.uid == uid) {
                handleFirestoreError(e, OperationType.UPDATE, "chats/$chatId/messages")
            }
        }
    }

    suspend fun softDeleteMessage(
        chatId: String,
        message: ChatMessage,
        isLastMessage: Boolean
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (message.senderId != uid) {
            return Result.failure(IllegalAccessException("You can only delete your own messages."))
        }
        val msgRef = db.collection("chats").document(chatId).collection("messages").document(message.id)
        return try {
            FreeTierQuotaManager.canPerformFirestoreWrite(if (isLastMessage) 2L else 1L).getOrThrow()
            val now = FieldValue.serverTimestamp()
            val batch = db.batch()
            batch.update(
                msgRef,
                mapOf(
                    "deleted" to true,
                    "text" to "",
                    "updatedAt" to now
                )
            )
            if (isLastMessage) {
                batch.update(
                    db.collection("chats").document(chatId),
                    mapOf(
                        "lastMessageText" to "This message was deleted",
                        "updatedAt" to now
                    )
                )
            }
            batch.commit().await()
            FreeTierQuotaManager.recordFirestoreWrites(if (isLastMessage) 2L else 1L)

            val mediaId = message.mediaId
            if (!mediaId.isNullOrBlank()) {
                runCatching {
                    db.collection("chats").document(chatId)
                        .collection("media").document(mediaId)
                        .delete().await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    private fun logStreamErrorIfSignedIn(
        expectedUid: String,
        error: Throwable,
        operationType: OperationType,
        path: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val toLog = firestoreError ?: (error as? Exception) ?: return
        if (auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(toLog, operationType, path)
        }
    }
}
