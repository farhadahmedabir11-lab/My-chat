package com.example.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatRepository
import com.example.data.MediaProcessor
import com.example.data.MediaRepository
import com.example.data.UserRepository
import com.example.data.friendlyFirestoreMessage
import com.example.model.ChatMessage
import com.example.model.MediaDownloadState
import com.example.model.MediaUploadTask
import com.example.model.MessageType
import com.example.model.PrivateChat
import com.example.model.PublicProfile
import com.example.model.UiState
import com.example.model.UploadStatus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val mediaRepository: MediaRepository,
    private val chatId: String,
    private val currentUserId: String
) : ViewModel() {

    val chatState: StateFlow<PrivateChat?> = chatRepository.observeChat(chatId, currentUserId)
        .catch { error ->
            if (isPureCancellation(error)) throw error
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val messagesState: StateFlow<UiState<List<ChatMessage>>> = chatRepository.observeMessages(chatId, currentUserId)
        .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(UiState.Error(friendlyFirestoreMessage(rootError, "Failed to load messages.")))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    private val _peerProfile = MutableStateFlow<PublicProfile?>(null)
    val peerProfile: StateFlow<PublicProfile?> = _peerProfile.asStateFlow()

    private val _replyTarget = MutableStateFlow<ChatMessage?>(null)
    val replyTarget: StateFlow<ChatMessage?> = _replyTarget.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _uploadTasks = MutableStateFlow<List<MediaUploadTask>>(emptyList())
    val uploadTasks: StateFlow<List<MediaUploadTask>> = _uploadTasks.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, MediaDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, MediaDownloadState>> = _downloadStates.asStateFlow()

    private val _thumbnailCache = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val thumbnailCache: StateFlow<Map<String, ByteArray>> = _thumbnailCache.asStateFlow()

    private val thumbnailFetchAttempted = mutableSetOf<String>()
    private val uploadMediaIds = mutableMapOf<String, String>()
    private var observedPeerId: String? = null

    fun ensureObservingPeer(peerUid: String) {
        if (peerUid.isBlank() || observedPeerId == peerUid) return
        observedPeerId = peerUid
        viewModelScope.launch {
            userRepository.observePublicProfile(peerUid)
                .catch { error ->
                    if (isPureCancellation(error)) throw error
                }
                .collect { profile ->
                    _peerProfile.value = profile
                }
        }
    }

    fun setReplyTarget(message: ChatMessage?) {
        _replyTarget.value = message
    }

    fun clearErrorBanner() {
        _errorBanner.value = null
    }

    fun sendMessage(recipientId: String, text: String, peerDisplayName: String) {
        if (text.isBlank() || recipientId.isBlank()) return
        val reply = _replyTarget.value
        val replySenderName = when {
            reply == null -> null
            reply.senderId == currentUserId -> "You"
            else -> peerDisplayName
        }
        viewModelScope.launch {
            _isSending.value = true
            val result = chatRepository.sendMessage(
                chatId = chatId,
                recipientId = recipientId,
                rawText = text,
                replyTo = reply,
                replySenderName = replySenderName
            )
            _isSending.value = false
            result.onSuccess {
                _replyTarget.value = null
            }.onFailure { error ->
                _errorBanner.value = friendlyFirestoreMessage(error, "Message could not be sent.")
            }
        }
    }

    fun sendMedia(
        recipientId: String,
        mediaType: MessageType,
        sourceUri: Uri,
        caption: String,
        peerDisplayName: String
    ) {
        if (recipientId.isBlank() || mediaType == MessageType.TEXT) return
        val tempId = "upload_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val reply = _replyTarget.value
        val replySenderName = when {
            reply == null -> null
            reply.senderId == currentUserId -> "You"
            else -> peerDisplayName
        }
        val task = MediaUploadTask(
            tempId = tempId,
            chatId = chatId,
            recipientId = recipientId,
            mediaType = mediaType,
            sourceUriString = sourceUri.toString(),
            fileName = if (mediaType == MessageType.VIDEO) "Video" else "Photo",
            caption = caption,
            progress = 0.02f,
            status = UploadStatus.PREPARING
        )
        _uploadTasks.value = _uploadTasks.value + task
        _replyTarget.value = null

        launchMediaUpload(task, reply, replySenderName)
    }

    fun retryUpload(tempId: String, peerDisplayName: String) {
        val task = _uploadTasks.value.firstOrNull { it.tempId == tempId } ?: return
        val updated = task.copy(
            status = UploadStatus.UPLOADING,
            progress = 0.05f,
            errorMessage = null
        )
        _uploadTasks.value = _uploadTasks.value.map { if (it.tempId == tempId) updated else it }
        launchMediaUpload(updated, null, peerDisplayName)
    }

    fun dismissFailedUpload(tempId: String) {
        _uploadTasks.value = _uploadTasks.value.filterNot { it.tempId == tempId }
        uploadMediaIds.remove(tempId)
    }

    private fun launchMediaUpload(
        task: MediaUploadTask,
        replyTo: ChatMessage?,
        replySenderName: String?
    ) {
        viewModelScope.launch {
            val uri = Uri.parse(task.sourceUriString)
            val existingMediaId = uploadMediaIds[task.tempId]
            val result = mediaRepository.uploadAndSendMediaMessage(
                existingMediaId = existingMediaId,
                chatId = task.chatId,
                recipientId = task.recipientId,
                mediaType = task.mediaType,
                sourceUri = uri,
                caption = task.caption,
                replyTo = replyTo,
                replySenderName = replySenderName,
                onPrepared = { mediaId, prepared ->
                    uploadMediaIds[task.tempId] = mediaId
                    if (prepared.thumbnailBytes.isNotEmpty()) {
                        _thumbnailCache.value = _thumbnailCache.value + (mediaId to prepared.thumbnailBytes)
                    }
                    _uploadTasks.value = _uploadTasks.value.map {
                        if (it.tempId == task.tempId) {
                            it.copy(
                                fileName = prepared.fileName,
                                status = UploadStatus.UPLOADING,
                                progress = 0.08f
                            )
                        } else it
                    }
                },
                onProgress = { progress ->
                    _uploadTasks.value = _uploadTasks.value.map {
                        if (it.tempId == task.tempId) {
                            it.copy(status = UploadStatus.UPLOADING, progress = progress)
                        } else it
                    }
                }
            )
            result.onSuccess { mediaId ->
                _uploadTasks.value = _uploadTasks.value.filterNot { it.tempId == task.tempId }
                uploadMediaIds.remove(task.tempId)
                // Mark local cache path immediately if cached
                val mime = if (task.mediaType == MessageType.VIDEO) "video/mp4" else "image/jpeg"
                val cached = _downloadStates.value[mediaId]?.cachedFilePath
                if (cached == null) {
                    _downloadStates.value = _downloadStates.value + (
                        mediaId to MediaDownloadState(
                            isDownloading = false,
                            progress = 1f,
                            cachedFilePath = null
                        )
                    )
                }
            }.onFailure { error ->
                val message = when (error) {
                    is IllegalArgumentException -> error.message ?: "Unsupported media."
                    else -> friendlyFirestoreMessage(error, "Upload failed. Check connection and retry.")
                }
                _uploadTasks.value = _uploadTasks.value.map {
                    if (it.tempId == task.tempId) {
                        it.copy(status = UploadStatus.FAILED, errorMessage = message)
                    } else it
                }
            }
        }
    }

    fun inspectLocalCache(context: Context, message: ChatMessage) {
        val mediaId = message.mediaId ?: return
        if (message.deleted) return
        val existing = _downloadStates.value[mediaId]
        if (existing?.cachedFilePath != null) return
        val file = MediaProcessor.getCachedMediaFile(context, mediaId, message.mediaMimeType)
        if (file.exists() && file.length() > 0L) {
            _downloadStates.value = _downloadStates.value + (
                mediaId to MediaDownloadState(
                    isDownloading = false,
                    progress = 1f,
                    cachedFilePath = file.absolutePath
                )
            )
        }
    }

    fun loadThumbnailIfNeeded(mediaId: String) {
        if (mediaId.isBlank()) return
        if (_thumbnailCache.value.containsKey(mediaId)) return
        if (!thumbnailFetchAttempted.add(mediaId)) return
        viewModelScope.launch {
            val meta = mediaRepository.fetchMediaMetadata(chatId, mediaId).getOrNull()
            val bytes = meta?.thumbnailBytes?.toBytes()
            if (bytes != null && bytes.isNotEmpty()) {
                _thumbnailCache.value = _thumbnailCache.value + (mediaId to bytes)
            }
        }
    }

    fun downloadMedia(
        mediaId: String,
        mimeType: String?,
        chunkCount: Int,
        onReady: (File) -> Unit = {}
    ) {
        if (mediaId.isBlank()) return
        val current = _downloadStates.value[mediaId]
        if (current?.cachedFilePath != null) {
            val file = File(current.cachedFilePath)
            if (file.exists() && file.length() > 0L) {
                onReady(file)
                return
            }
        }
        if (current?.isDownloading == true) return

        _downloadStates.value = _downloadStates.value + (
            mediaId to MediaDownloadState(isDownloading = true, progress = 0.05f)
        )
        viewModelScope.launch {
            val result = mediaRepository.downloadMediaToCache(
                chatId = chatId,
                mediaId = mediaId,
                mimeType = mimeType,
                expectedChunks = chunkCount,
                onProgress = { progress ->
                    _downloadStates.value = _downloadStates.value + (
                        mediaId to MediaDownloadState(isDownloading = true, progress = progress)
                    )
                }
            )
            result.onSuccess { file ->
                _downloadStates.value = _downloadStates.value + (
                    mediaId to MediaDownloadState(
                        isDownloading = false,
                        progress = 1f,
                        cachedFilePath = file.absolutePath
                    )
                )
                onReady(file)
            }.onFailure { error ->
                val msg = friendlyFirestoreMessage(error, "Download failed. Tap to retry.")
                _downloadStates.value = _downloadStates.value + (
                    mediaId to MediaDownloadState(
                        isDownloading = false,
                        progress = 0f,
                        errorMessage = msg
                    )
                )
                _errorBanner.value = msg
            }
        }
    }

    fun saveMediaToGallery(
        context: Context,
        mediaId: String,
        mimeType: String?,
        chunkCount: Int,
        defaultName: String
    ) {
        downloadMedia(mediaId, mimeType, chunkCount) { file ->
            viewModelScope.launch {
                val resolvedMime = mimeType ?: "image/jpeg"
                val res = MediaProcessor.saveToDeviceGallery(
                    context = context,
                    sourceFile = file,
                    mimeType = resolvedMime,
                    displayName = defaultName
                )
                res.onSuccess { savedMsg ->
                    _errorBanner.value = savedMsg
                }.onFailure { err ->
                    _errorBanner.value = err.message ?: "Could not save file."
                }
            }
        }
    }

    fun markMessagesRead(
        chat: PrivateChat?,
        messages: List<ChatMessage>,
        sendReadReceipts: Boolean
    ) {
        viewModelScope.launch {
            chatRepository.markMessagesDeliveredOrSeen(
                chatId = chatId,
                chat = chat,
                messages = messages,
                uid = currentUserId,
                sendReadReceipts = sendReadReceipts
            )
        }
    }

    fun deleteOwnMessage(message: ChatMessage, isLastMessage: Boolean) {
        viewModelScope.launch {
            val result = chatRepository.softDeleteMessage(chatId, message, isLastMessage)
            result.onFailure { error ->
                _errorBanner.value = friendlyFirestoreMessage(error, "Could not delete message.")
            }
        }
    }

    private fun isPureCancellation(error: Throwable): Boolean {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        return firestoreError == null && error is CancellationException
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
