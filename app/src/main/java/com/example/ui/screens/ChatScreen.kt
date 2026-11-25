package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.ChatUtils
import com.example.data.FreeTierQuotaManager
import com.example.data.MediaProcessor
import com.example.model.ChatMessage
import com.example.model.MediaDownloadState
import com.example.model.MediaUploadTask
import com.example.model.MessageType
import com.example.model.UiState
import com.example.model.UploadStatus
import com.example.ui.components.MessageStatusTicks
import com.example.ui.components.UserAvatar
import com.example.ui.theme.MyChatBluePrimary
import com.example.ui.theme.MyChatChatCanvasDark
import com.example.ui.theme.MyChatChatCanvasLight
import com.example.ui.theme.MyChatReceivedBubbleDark
import com.example.ui.theme.MyChatReceivedBubbleLight
import com.example.ui.theme.MyChatSentBubbleDark
import com.example.ui.theme.MyChatSentBubbleLight
import com.example.viewmodel.ChatViewModel
import java.io.File

private data class PendingMediaDraft(
    val mediaType: MessageType,
    val uri: Uri
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    currentUserId: String,
    sendReadReceipts: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val chat by viewModel.chatState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val peerProfile by viewModel.peerProfile.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val errorBanner by viewModel.errorBanner.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val uploadTasks by viewModel.uploadTasks.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val thumbnailCache by viewModel.thumbnailCache.collectAsStateWithLifecycle()
    val quota by FreeTierQuotaManager.snapshot.collectAsStateWithLifecycle()
    val isWriteBlocked = quota.firestoreQuotaExceeded || quota.dailyFirestoreWrites >= quota.maxDailyFirestoreWrites
    val isMediaBlocked = quota.isMediaBlocked

    var composerText by remember { mutableStateOf("") }
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var pendingDraft by remember { mutableStateOf<PendingMediaDraft?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var viewingMediaMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val peerUid = chat?.peerId(currentUserId).orEmpty()
    val peerName = peerProfile?.fullName ?: chat?.peerName(currentUserId) ?: "Chat"
    val peerColor = peerProfile?.avatarColorHex ?: chat?.peerColorHex(currentUserId) ?: "#0D6EFD"
    val isPeerOnline = peerProfile?.online == true

    // Media & Camera Launchers (zero-permission Photo Picker + FileProvider Camera)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingDraft = PendingMediaDraft(MessageType.IMAGE, uri)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingDraft = PendingMediaDraft(MessageType.VIDEO, uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            pendingDraft = PendingMediaDraft(MessageType.IMAGE, uri)
        }
        pendingCameraUri = null
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val (uri, _) = MediaProcessor.createCameraCaptureUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val launchCameraCapture = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val (uri, _) = MediaProcessor.createCameraCaptureUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(peerUid) {
        if (peerUid.isNotBlank()) {
            viewModel.ensureObservingPeer(peerUid)
        }
    }

    LaunchedEffect(errorBanner) {
        if (!errorBanner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(errorBanner.orEmpty())
            viewModel.clearErrorBanner()
        }
    }

    LaunchedEffect(messagesState, chat, sendReadReceipts) {
        val messages = (messagesState as? UiState.Success)?.data ?: return@LaunchedEffect
        viewModel.markMessagesRead(chat, messages, sendReadReceipts)
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val isDark = isSystemInDarkTheme()
    val canvasColor = if (isDark) MyChatChatCanvasDark else MyChatChatCanvasLight

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = canvasColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = MyChatBluePrimary, shadowElevation = 4.dp) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MyChatBluePrimary,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    ),
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("chat_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to chats"
                            )
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(
                                fullName = peerName,
                                colorHex = peerColor,
                                size = 40.dp,
                                isOnline = isPeerOnline
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = peerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when {
                                        isPeerOnline -> "Online"
                                        peerProfile?.lastSeen != null -> "Last seen ${ChatUtils.formatMessageTime(peerProfile?.lastSeen?.toDate())}"
                                        else -> "Offline"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPeerOnline) Color(0xFFBBF7D0) else Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isMediaBlocked || isWriteBlocked || !quota.activeWarningMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_quota_warning_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = quota.activeWarningMessage
                                        ?: "Daily free-tier quota reached. Service paused until reset — no paid billing enabled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!isMediaBlocked && !isWriteBlocked) {
                                    IconButton(
                                        onClick = { FreeTierQuotaManager.clearWarning() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = "Dismiss quota warning",
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (replyTarget != null) {
                        val target = replyTarget!!
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(32.dp)
                                        .background(MyChatBluePrimary, RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (target.senderId == currentUserId) "Replying to yourself" else "Replying to $peerName",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MyChatBluePrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val replyBody = when {
                                        target.deleted -> "Deleted message"
                                        target.text.isNotBlank() -> target.text
                                        target.parsedType == MessageType.IMAGE -> "Photo"
                                        target.parsedType == MessageType.VIDEO -> "Video"
                                        else -> "Message"
                                    }
                                    Text(
                                        text = replyBody,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.setReplyTarget(null) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Cancel reply")
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box {
                            IconButton(
                                onClick = { attachMenuExpanded = true },
                                enabled = peerUid.isNotBlank() && !isMediaBlocked,
                                modifier = Modifier.testTag("chat_attach_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = "Attach photo or video",
                                    tint = if (isMediaBlocked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f) else MyChatBluePrimary
                                )
                            }
                            DropdownMenu(
                                expanded = attachMenuExpanded,
                                onDismissRequest = { attachMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Photo from Gallery") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = MyChatBluePrimary)
                                    },
                                    onClick = {
                                        attachMenuExpanded = false
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.testTag("attach_gallery_photo")
                                )
                                DropdownMenuItem(
                                    text = { Text("Video from Gallery") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = MyChatBluePrimary)
                                    },
                                    onClick = {
                                        attachMenuExpanded = false
                                        videoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    },
                                    modifier = Modifier.testTag("attach_gallery_video")
                                )
                                DropdownMenuItem(
                                    text = { Text("Take Photo") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = MyChatBluePrimary)
                                    },
                                    onClick = {
                                        attachMenuExpanded = false
                                        launchCameraCapture()
                                    },
                                    modifier = Modifier.testTag("attach_camera")
                                )
                            }
                        }

                        IconButton(
                            onClick = launchCameraCapture,
                            enabled = peerUid.isNotBlank() && !isMediaBlocked,
                            modifier = Modifier.testTag("chat_camera_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhotoCamera,
                                contentDescription = "Take photo with camera",
                                tint = if (isMediaBlocked) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        OutlinedTextField(
                            value = composerText,
                            onValueChange = { composerText = it.take(2000) },
                            placeholder = { Text("Message $peerName...") },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                focusedBorderColor = MyChatBluePrimary,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("message_input")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val canSendText = composerText.isNotBlank() && peerUid.isNotBlank() && !isSending && !isWriteBlocked
                        Surface(
                            color = if (canSendText) MyChatBluePrimary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("send_message_button")
                        ) {
                            IconButton(
                                onClick = {
                                    val msg = composerText
                                    composerText = ""
                                    viewModel.sendMessage(peerUid, msg, peerName)
                                },
                                enabled = canSendText
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "Send message",
                                    tint = if (canSendText) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = messagesState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MyChatBluePrimary)
                    }
                }
                is UiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                is UiState.Success -> {
                    val messages = state.data
                    if (messages.isEmpty() && uploadTasks.isEmpty()) {
                        EmptyConversationState(peerName = peerName)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                                val isMine = message.senderId == currentUserId
                                val isLast = index == messages.lastIndex
                                val mediaId = message.mediaId
                                val downloadState = if (mediaId != null) downloadStates[mediaId] else null
                                val thumbBytes = if (mediaId != null) thumbnailCache[mediaId] else null

                                LaunchedEffect(message.id, message.deleted) {
                                    if (!message.deleted && message.parsedType != MessageType.TEXT) {
                                        viewModel.inspectLocalCache(context, message)
                                        mediaId?.let { viewModel.loadThumbnailIfNeeded(it) }
                                    }
                                }

                                MessageBubbleRow(
                                    message = message,
                                    isMine = isMine,
                                    isDark = isDark,
                                    thumbnailBytes = thumbBytes,
                                    downloadState = downloadState,
                                    onOpenMedia = {
                                        viewingMediaMessage = message
                                        if (!mediaId.isNullOrBlank()) {
                                            viewModel.downloadMedia(
                                                mediaId = mediaId,
                                                mimeType = message.mediaMimeType,
                                                chunkCount = (message.mediaChunkCount ?: 1L).toInt()
                                            )
                                        }
                                    },
                                    onSaveMedia = {
                                        if (!mediaId.isNullOrBlank()) {
                                            val defaultName = if (message.parsedType == MessageType.VIDEO) {
                                                "MyChat_${mediaId.takeLast(8)}.mp4"
                                            } else {
                                                "MyChat_${mediaId.takeLast(8)}.jpg"
                                            }
                                            viewModel.saveMediaToGallery(
                                                context = context,
                                                mediaId = mediaId,
                                                mimeType = message.mediaMimeType,
                                                chunkCount = (message.mediaChunkCount ?: 1L).toInt(),
                                                defaultName = defaultName
                                            )
                                        }
                                    },
                                    onReply = { viewModel.setReplyTarget(message) },
                                    onDelete = { viewModel.deleteOwnMessage(message, isLast) }
                                )
                            }

                            items(uploadTasks, key = { it.tempId }) { task ->
                                MediaUploadProgressCard(
                                    task = task,
                                    onRetry = { viewModel.retryUpload(task.tempId, peerName) },
                                    onDismiss = { viewModel.dismissFailedUpload(task.tempId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Pre-send media preview dialog
    pendingDraft?.let { draft ->
        MediaPreviewDialog(
            mediaType = draft.mediaType,
            sourceUri = draft.uri,
            peerName = peerName,
            onDismiss = { pendingDraft = null },
            onSend = { caption ->
                pendingDraft = null
                if (peerUid.isNotBlank()) {
                    viewModel.sendMedia(
                        recipientId = peerUid,
                        mediaType = draft.mediaType,
                        sourceUri = draft.uri,
                        caption = caption,
                        peerDisplayName = peerName
                    )
                }
            }
        )
    }

    // Full-screen Photo or Video viewer
    viewingMediaMessage?.let { mediaMsg ->
        val mediaId = mediaMsg.mediaId.orEmpty()
        val downloadState = downloadStates[mediaId]
        val cachedFile = downloadState?.cachedFilePath?.let { File(it) }
        val defaultName = if (mediaMsg.parsedType == MessageType.VIDEO) {
            "MyChat_${mediaId.takeLast(8)}.mp4"
        } else {
            "MyChat_${mediaId.takeLast(8)}.jpg"
        }

        if (mediaMsg.parsedType == MessageType.VIDEO) {
            FullScreenVideoDialog(
                file = cachedFile,
                downloadState = downloadState,
                caption = mediaMsg.text,
                onRetryDownload = {
                    viewModel.downloadMedia(
                        mediaId = mediaId,
                        mimeType = mediaMsg.mediaMimeType,
                        chunkCount = (mediaMsg.mediaChunkCount ?: 1L).toInt()
                    )
                },
                onSaveToGallery = {
                    viewModel.saveMediaToGallery(
                        context = context,
                        mediaId = mediaId,
                        mimeType = mediaMsg.mediaMimeType,
                        chunkCount = (mediaMsg.mediaChunkCount ?: 1L).toInt(),
                        defaultName = defaultName
                    )
                },
                onDismiss = { viewingMediaMessage = null }
            )
        } else {
            FullScreenPhotoDialog(
                file = cachedFile,
                downloadState = downloadState,
                caption = mediaMsg.text,
                onRetryDownload = {
                    viewModel.downloadMedia(
                        mediaId = mediaId,
                        mimeType = mediaMsg.mediaMimeType,
                        chunkCount = (mediaMsg.mediaChunkCount ?: 1L).toInt()
                    )
                },
                onSaveToGallery = {
                    viewModel.saveMediaToGallery(
                        context = context,
                        mediaId = mediaId,
                        mimeType = mediaMsg.mediaMimeType,
                        chunkCount = (mediaMsg.mediaChunkCount ?: 1L).toInt(),
                        defaultName = defaultName
                    )
                },
                onDismiss = { viewingMediaMessage = null }
            )
        }
    }
}

@Composable
private fun MediaUploadProgressCard(
    task: MediaUploadTask,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MyChatSentBubbleLight,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier
                .widthIn(max = 290.dp)
                .testTag("upload_progress_${task.tempId}")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (task.status == UploadStatus.FAILED) Icons.Rounded.ErrorOutline else Icons.Rounded.Image,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (task.status) {
                            UploadStatus.PREPARING -> "Preparing ${if (task.mediaType == MessageType.VIDEO) "video" else "photo"}..."
                            UploadStatus.UPLOADING -> "Sending ${if (task.mediaType == MessageType.VIDEO) "video" else "photo"} (${(task.progress * 100).toInt()}%)"
                            UploadStatus.FAILED -> "Upload failed"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (task.status != UploadStatus.FAILED) {
                    LinearProgressIndicator(
                        progress = { task.progress.coerceIn(0.05f, 1f) },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.28f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = task.errorMessage ?: "Network interruption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFEE2E2)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                        }
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.testTag("retry_upload_${task.tempId}")
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleRow(
    message: ChatMessage,
    isMine: Boolean,
    isDark: Boolean,
    thumbnailBytes: ByteArray?,
    downloadState: MediaDownloadState?,
    onOpenMedia: () -> Unit,
    onSaveMedia: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val bubbleColor = when {
        isMine && isDark -> MyChatSentBubbleDark
        isMine -> MyChatSentBubbleLight
        isDark -> MyChatReceivedBubbleDark
        else -> MyChatReceivedBubbleLight
    }
    val textColor = if (isMine) Color.White else MaterialTheme.colorScheme.onSurface
    val timeColor = if (isMine) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isMine) 18.dp else 4.dp,
        bottomEnd = if (isMine) 4.dp else 18.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                shadowElevation = if (isMine) 1.dp else 2.dp,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(bubbleShape)
                    .combinedClickable(
                        onClick = { menuExpanded = true },
                        onLongClick = { menuExpanded = true }
                    )
                    .testTag("message_bubble_${message.id}")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!message.replyToText.isNullOrBlank()) {
                        Surface(
                            color = if (isMine) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(28.dp)
                                        .background(if (isMine) Color.White else MyChatBluePrimary, RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = message.replyToSenderName ?: "Reply",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isMine) Color.White else MyChatBluePrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = message.replyToText.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isMine) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (message.deleted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = null,
                                tint = timeColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "This message was deleted",
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = timeColor
                            )
                        }
                    } else {
                        if (message.parsedType != MessageType.TEXT && !message.mediaId.isNullOrBlank()) {
                            MediaBubbleContent(
                                message = message,
                                isMine = isMine,
                                thumbnailBytes = thumbnailBytes,
                                downloadState = downloadState,
                                onOpenMedia = onOpenMedia,
                                onSaveMedia = onSaveMedia
                            )
                            if (message.text.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                        if (message.text.isNotBlank()) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ChatUtils.formatMessageTime(message.createdAt?.toDate()),
                            style = MaterialTheme.typography.labelSmall,
                            color = timeColor
                        )
                        if (isMine && !message.deleted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            MessageStatusTicks(
                                status = message.parsedStatus,
                                onBlueBubble = true
                            )
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (!message.deleted && message.parsedType != MessageType.TEXT) {
                    DropdownMenuItem(
                        text = { Text("Save to device") },
                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onSaveMedia()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Reply") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onReply()
                    }
                )
                if (isMine && !message.deleted) {
                    DropdownMenuItem(
                        text = { Text("Delete message", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaBubbleContent(
    message: ChatMessage,
    isMine: Boolean,
    thumbnailBytes: ByteArray?,
    downloadState: MediaDownloadState?,
    onOpenMedia: () -> Unit,
    onSaveMedia: () -> Unit
) {
    val cachedFile = downloadState?.cachedFilePath?.let { File(it) }?.takeIf { it.exists() }
    val previewModel: Any? = when {
        message.parsedType == MessageType.IMAGE && cachedFile != null -> cachedFile
        thumbnailBytes != null && thumbnailBytes.isNotEmpty() -> thumbnailBytes
        else -> null
    }
    val isVideo = message.parsedType == MessageType.VIDEO

    Box(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .height(176.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isMine) Color.Black.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpenMedia)
            .testTag("media_bubble_${message.id}"),
        contentAlignment = Alignment.Center
    ) {
        if (previewModel != null) {
            AsyncImage(
                model = previewModel,
                contentDescription = if (isVideo) "Video message preview" else "Photo message",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Image,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isVideo) "Tap to play video" else "Tap to view photo",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        if (isVideo) {
            Surface(
                color = Color.Black.copy(alpha = 0.56f),
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }

        if (downloadState?.isDownloading == true) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { downloadState.progress.coerceIn(0.05f, 1f) },
                    color = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        // Bottom badge: size + duration + save shortcut
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val infoText = buildString {
                if (isVideo && message.mediaDurationMs != null && message.mediaDurationMs > 0L) {
                    append(ChatUtils.formatDurationMs(message.mediaDurationMs))
                    append(" • ")
                }
                append(ChatUtils.formatFileSize(message.mediaSizeBytes ?: 0L))
            }
            Text(
                text = infoText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = "Download media",
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onSaveMedia)
            )
        }
    }
}

@Composable
private fun EmptyConversationState(peerName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MyChatBluePrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Private 1-to-1 conversation with $peerName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Say hello or share photos and videos!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
