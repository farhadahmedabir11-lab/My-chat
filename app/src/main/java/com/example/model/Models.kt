package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class MessageStatus {
    SENT,
    DELIVERED,
    SEEN;

    companion object {
        fun fromRaw(raw: String?): MessageStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SENT
    }
}

@Keep
@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val id: String = "",
    val uid: String = "",
    val fullName: String = "",
    val username: String = "",
    val email: String = "",
    val bio: String = "",
    val avatarColorHex: String = "#0D6EFD",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PublicProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val fullName: String = "",
    val username: String = "",
    val bio: String = "",
    val avatarColorHex: String = "#0D6EFD",
    val discoverable: Boolean = true,
    val online: Boolean = false,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserSettings(
    @DocumentId val id: String = "",
    val userId: String = "",
    val discoverable: Boolean = true,
    val discoverableByEmail: Boolean = true,
    val showOnlineStatus: Boolean = true,
    val showReadReceipts: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val showNotificationPreview: Boolean = true,
    val fcmToken: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ContactEntry(
    @DocumentId val id: String = "",
    val contactUid: String = "",
    val fullName: String = "",
    val username: String = "",
    val avatarColorHex: String = "#0D6EFD",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PrivateChat(
    @DocumentId val id: String = "",
    val participantIds: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val participantUsernames: Map<String, String> = emptyMap(),
    val participantColors: Map<String, String> = emptyMap(),
    val lastMessageText: String = "",
    val lastMessageSenderId: String = "",
    val lastMessageStatus: String = MessageStatus.SENT.name,
    @ServerTimestamp val lastMessageTime: Timestamp? = null,
    val unreadCounts: Map<String, Long> = emptyMap(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun peerId(myUid: String): String =
        participantIds.firstOrNull { it != myUid } ?: ""

    fun peerName(myUid: String): String {
        val other = peerId(myUid)
        return participantNames[other] ?: "My Chat User"
    }

    fun peerUsername(myUid: String): String {
        val other = peerId(myUid)
        return participantUsernames[other] ?: ""
    }

    fun peerColorHex(myUid: String): String {
        val other = peerId(myUid)
        return participantColors[other] ?: "#0D6EFD"
    }

    fun unreadFor(myUid: String): Int =
        (unreadCounts[myUid] as? Number)?.toInt() ?: 0
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO;

    companion object {
        fun fromRaw(raw: String?): MessageType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TEXT
    }
}

enum class CallType {
    AUDIO,
    VIDEO;

    companion object {
        fun fromRaw(raw: String?): CallType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUDIO
    }
}

enum class CallStatus {
    RINGING,
    ACCEPTED,
    REJECTED,
    ENDED,
    MISSED;

    val isTerminal: Boolean
        get() = this == REJECTED || this == ENDED || this == MISSED

    companion object {
        fun fromRaw(raw: String?): CallStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ENDED
    }
}

enum class CallOutcome {
    COMPLETED,
    MISSED,
    REJECTED,
    CANCELLED;

    companion object {
        fun fromRaw(raw: String?): CallOutcome =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: COMPLETED
    }
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val recipientId: String = "",
    val participantIds: List<String> = emptyList(),
    val text: String = "",
    val status: String = MessageStatus.SENT.name,
    val messageType: String? = null,
    val mediaId: String? = null,
    val mediaMimeType: String? = null,
    val mediaSizeBytes: Long? = null,
    val mediaDurationMs: Long? = null,
    val mediaWidth: Long? = null,
    val mediaHeight: Long? = null,
    val mediaChunkCount: Long? = null,
    val replyToMessageId: String? = null,
    val replyToText: String? = null,
    val replyToSenderName: String? = null,
    val deleted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedStatus: MessageStatus
        get() = MessageStatus.fromRaw(status)

    val parsedType: MessageType
        get() = MessageType.fromRaw(messageType)
}

@Keep
@IgnoreExtraProperties
data class ChatMediaMetadata(
    @DocumentId val id: String = "",
    val mediaId: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val recipientId: String = "",
    val participantIds: List<String> = emptyList(),
    val mediaType: String = MessageType.IMAGE.name,
    val mimeType: String = "image/jpeg",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val chunkCount: Long = 1L,
    val width: Long? = null,
    val height: Long? = null,
    val durationMs: Long? = null,
    val thumbnailBytes: com.google.firebase.firestore.Blob? = null,
    val uploadComplete: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSession(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerUsername: String = "",
    val callerColorHex: String = "#0D6EFD",
    val calleeId: String = "",
    val calleeName: String = "",
    val calleeUsername: String = "",
    val calleeColorHex: String = "#0D6EFD",
    val participantIds: List<String> = emptyList(),
    val callType: String = CallType.AUDIO.name,
    val status: String = CallStatus.RINGING.name,
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    @ServerTimestamp val startedAt: Timestamp? = null,
    @ServerTimestamp val endedAt: Timestamp? = null,
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedType: CallType
        get() = CallType.fromRaw(callType)

    val parsedStatus: CallStatus
        get() = CallStatus.fromRaw(status)

    fun peerId(myUid: String): String =
        if (callerId == myUid) calleeId else callerId

    fun peerName(myUid: String): String =
        if (callerId == myUid) calleeName else callerName

    fun peerColorHex(myUid: String): String =
        if (callerId == myUid) calleeColorHex else callerColorHex
}

@Keep
@IgnoreExtraProperties
data class IceCandidateEntry(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val participantIds: List<String> = emptyList(),
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallHistoryEntry(
    @DocumentId val id: String = "",
    val callId: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerColorHex: String = "#0D6EFD",
    val calleeId: String = "",
    val calleeName: String = "",
    val calleeColorHex: String = "#0D6EFD",
    val participantIds: List<String> = emptyList(),
    val callType: String = CallType.AUDIO.name,
    val outcome: String = CallOutcome.COMPLETED.name,
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedCallType: CallType
        get() = CallType.fromRaw(callType)

    val parsedOutcome: CallOutcome
        get() = CallOutcome.fromRaw(outcome)

    fun isOutgoing(myUid: String): Boolean = callerId == myUid

    fun peerId(myUid: String): String =
        if (callerId == myUid) calleeId else callerId

    fun peerName(myUid: String): String =
        if (callerId == myUid) calleeName else callerName

    fun peerColorHex(myUid: String): String =
        if (callerId == myUid) calleeColorHex else callerColorHex
}

enum class UploadStatus {
    PREPARING,
    UPLOADING,
    FAILED
}

data class MediaUploadTask(
    val tempId: String,
    val chatId: String,
    val recipientId: String,
    val mediaType: MessageType,
    val sourceUriString: String,
    val fileName: String,
    val caption: String,
    val progress: Float = 0f,
    val status: UploadStatus = UploadStatus.PREPARING,
    val errorMessage: String? = null
)

data class MediaDownloadState(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val cachedFilePath: String? = null,
    val errorMessage: String? = null
)

