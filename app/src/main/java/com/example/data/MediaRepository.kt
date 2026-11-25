package com.example.data

import android.content.Context
import android.net.Uri
import com.example.R
import com.example.model.ChatMediaMetadata
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import com.example.model.MessageType
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.ceil

class MediaRepository(
    private val db: FirebaseFirestore,
    private val appContext: Context,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        appContext = context.applicationContext
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before sharing media.")

    suspend fun uploadAndSendMediaMessage(
        existingMediaId: String? = null,
        chatId: String,
        recipientId: String,
        mediaType: MessageType,
        sourceUri: Uri,
        caption: String,
        replyTo: ChatMessage? = null,
        replySenderName: String? = null,
        onPrepared: (mediaId: String, PreparedMedia) -> Unit = { _, _ -> },
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val mediaId = existingMediaId ?: "media_${UUID.randomUUID().toString().replace("-", "").take(22)}"
        return try {
            FreeTierQuotaManager.canPerformFirestoreWrite(3L).getOrThrow()
            onProgress(0.05f)
            val prepared = when (mediaType) {
                MessageType.IMAGE -> MediaProcessor.preparePhoto(appContext, sourceUri).getOrThrow()
                MessageType.VIDEO -> MediaProcessor.prepareVideo(appContext, sourceUri).getOrThrow()
                MessageType.TEXT -> throw IllegalArgumentException("Invalid media type.")
            }

            val chunkSize = MediaProcessor.CHUNK_SIZE_BYTES
            val chunkCount = ceil(prepared.sizeBytes.toDouble() / chunkSize.toDouble())
                .toInt()
                .coerceAtLeast(1)
            if (chunkCount > 30) {
                prepared.stagedFile.delete()
                throw IllegalArgumentException("Media exceeds maximum allowed free-tier chunk count.")
            }

            val quotaCheck = FreeTierQuotaManager.canPerformMediaUpload(
                estimatedBytes = prepared.sizeBytes,
                estimatedWrites = chunkCount.toLong() + 4L
            )
            if (quotaCheck.isFailure) {
                prepared.stagedFile.delete()
                throw quotaCheck.exceptionOrNull()!!
            }
            onPrepared(mediaId, prepared)

            val sorted = ChatUtils.sortedParticipants(uid, recipientId)
            val mediaRef = db.collection("chats").document(chatId).collection("media").document(mediaId)
            val now = FieldValue.serverTimestamp()

            val existingMeta = mediaRef.get().await()
            if (!existingMeta.exists()) {
                val metaPayload = mutableMapOf<String, Any>(
                    "mediaId" to mediaId,
                    "chatId" to chatId,
                    "senderId" to uid,
                    "recipientId" to recipientId,
                    "participantIds" to sorted,
                    "mediaType" to prepared.mediaType.name,
                    "mimeType" to prepared.mimeType,
                    "fileName" to prepared.fileName.take(150),
                    "sizeBytes" to prepared.sizeBytes,
                    "chunkCount" to chunkCount.toLong(),
                    "uploadComplete" to false,
                    "createdAt" to now,
                    "updatedAt" to now
                )
                prepared.width?.let { metaPayload["width"] = it }
                prepared.height?.let { metaPayload["height"] = it }
                prepared.durationMs?.let { metaPayload["durationMs"] = it }
                if (prepared.thumbnailBytes.isNotEmpty() && prepared.thumbnailBytes.size <= 78_000) {
                    metaPayload["thumbnailBytes"] = Blob.fromBytes(prepared.thumbnailBytes)
                }
                mediaRef.set(metaPayload).await()
                FreeTierQuotaManager.recordFirestoreWrites(1L)
                FreeTierQuotaManager.recordMediaBytes(prepared.thumbnailBytes.size.toLong())
            }

            // Upload chunks sequentially (resuming safely if retrying an interrupted upload)
            withContext(Dispatchers.IO) {
                prepared.stagedFile.inputStream().use { input ->
                    val buffer = ByteArray(chunkSize)
                    for (chunkIndex in 0 until chunkCount) {
                        var offset = 0
                        while (offset < chunkSize) {
                            val read = input.read(buffer, offset, chunkSize - offset)
                            if (read <= 0) break
                            offset += read
                        }
                        if (offset <= 0) break

                        val chunkRef = mediaRef.collection("chunks").document("chunk_$chunkIndex")
                        val alreadyUploaded = runCatching { chunkRef.get().await().exists() }.getOrDefault(false)
                        if (!alreadyUploaded) {
                            val chunkBytes = if (offset == buffer.size) buffer else buffer.copyOf(offset)
                            chunkRef.set(
                                mapOf(
                                    "chunkIndex" to chunkIndex,
                                    "senderId" to uid,
                                    "participantIds" to sorted,
                                    "data" to Blob.fromBytes(chunkBytes),
                                    "createdAt" to FieldValue.serverTimestamp()
                                )
                            ).await()
                            FreeTierQuotaManager.recordFirestoreWrites(1L)
                            FreeTierQuotaManager.recordMediaBytes(chunkBytes.size.toLong())
                        }
                        val fraction = 0.1f + 0.85f * ((chunkIndex + 1).toFloat() / chunkCount.toFloat())
                        onProgress(fraction.coerceIn(0.1f, 0.95f))
                    }
                }
            }

            // Mark metadata complete + create message + update conversation summary
            val trimmedCaption = caption.trim().take(1000)
            val fallbackLabel = if (prepared.mediaType == MessageType.VIDEO) "🎬 Video" else "📷 Photo"
            val summaryPreview = if (trimmedCaption.isNotEmpty()) {
                "$fallbackLabel: $trimmedCaption"
            } else {
                fallbackLabel
            }

            val chatRef = db.collection("chats").document(chatId)
            val msgRef = chatRef.collection("messages").document()
            val commitTime = FieldValue.serverTimestamp()
            val messagePayload = mutableMapOf<String, Any>(
                "chatId" to chatId,
                "senderId" to uid,
                "recipientId" to recipientId,
                "participantIds" to sorted,
                "text" to trimmedCaption,
                "status" to MessageStatus.SENT.name,
                "messageType" to prepared.mediaType.name,
                "mediaId" to mediaId,
                "mediaMimeType" to prepared.mimeType,
                "mediaSizeBytes" to prepared.sizeBytes,
                "mediaChunkCount" to chunkCount.toLong(),
                "deleted" to false,
                "createdAt" to commitTime,
                "updatedAt" to commitTime
            )
            prepared.width?.let { messagePayload["mediaWidth"] = it }
            prepared.height?.let { messagePayload["mediaHeight"] = it }
            prepared.durationMs?.let { messagePayload["mediaDurationMs"] = it }

            if (replyTo != null && replyTo.id.isNotBlank()) {
                messagePayload["replyToMessageId"] = replyTo.id.take(128)
                val preview = when {
                    replyTo.deleted -> "Deleted message"
                    replyTo.text.isNotBlank() -> replyTo.text.take(120)
                    replyTo.parsedType == MessageType.IMAGE -> "📷 Photo"
                    replyTo.parsedType == MessageType.VIDEO -> "🎬 Video"
                    else -> "Message"
                }
                messagePayload["replyToText"] = preview
                if (!replySenderName.isNullOrBlank()) {
                    messagePayload["replyToSenderName"] = replySenderName.take(60)
                }
            }

            val batch = db.batch()
            batch.update(
                mediaRef,
                mapOf(
                    "uploadComplete" to true,
                    "updatedAt" to commitTime
                )
            )
            batch.set(msgRef, messagePayload)
            batch.update(
                chatRef,
                mapOf(
                    "lastMessageText" to summaryPreview.take(240),
                    "lastMessageSenderId" to uid,
                    "lastMessageStatus" to MessageStatus.SENT.name,
                    "lastMessageTime" to commitTime,
                    "unreadCounts.$recipientId" to FieldValue.increment(1L),
                    "unreadCounts.$uid" to 0L,
                    "updatedAt" to commitTime
                )
            )
            batch.commit().await()
            FreeTierQuotaManager.recordFirestoreWrites(3L)

            // Cache sent file locally so sender doesn't re-download
            withContext(Dispatchers.IO) {
                val cachedDest = MediaProcessor.getCachedMediaFile(appContext, mediaId, prepared.mimeType)
                runCatching {
                    prepared.stagedFile.copyTo(cachedDest, overwrite = true)
                    prepared.stagedFile.delete()
                }
            }
            onProgress(1f)
            Result.success(mediaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            handleFirestoreError(e, OperationType.WRITE, "chats/$chatId/media/$mediaId")
            Result.failure(e)
        }
    }

    suspend fun fetchMediaMetadata(chatId: String, mediaId: String): Result<ChatMediaMetadata?> {
        if (chatId.isBlank() || mediaId.isBlank()) return Result.success(null)
        val path = "chats/$chatId/media/$mediaId"
        return try {
            requireUserId()
            val doc = db.document(path).get().await()
            val metadata = if (doc.exists()) {
                doc.toObject(ChatMediaMetadata::class.java)
            } else null
            Result.success(metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun downloadMediaToCache(
        chatId: String,
        mediaId: String,
        mimeType: String?,
        expectedChunks: Int = 1,
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        val targetFile = MediaProcessor.getCachedMediaFile(appContext, mediaId, mimeType)
        if (targetFile.exists() && targetFile.length() > 0L) {
            onProgress(1f)
            return Result.success(targetFile)
        }

        val mediaRef = db.collection("chats").document(chatId).collection("media").document(mediaId)
        return try {
            requireUserId()
            val metaSnap = mediaRef.get().await()
            if (!metaSnap.exists()) {
                return Result.failure(IllegalStateException("Media item was deleted or is unavailable."))
            }
            val meta = metaSnap.toObject(ChatMediaMetadata::class.java)
            val totalChunks = (meta?.chunkCount?.toInt() ?: expectedChunks).coerceAtLeast(1)
            val resolvedMime = meta?.mimeType ?: mimeType
            val resolvedFile = MediaProcessor.getCachedMediaFile(appContext, mediaId, resolvedMime)
            if (resolvedFile.exists() && resolvedFile.length() > 0L) {
                onProgress(1f)
                return Result.success(resolvedFile)
            }

            val tempFile = File(resolvedFile.parentFile, "${resolvedFile.name}.part")
            withContext(Dispatchers.IO) {
                FileOutputStream(tempFile).use { out ->
                    for (index in 0 until totalChunks) {
                        val chunkSnap = mediaRef.collection("chunks").document("chunk_$index").get().await()
                        val blob = chunkSnap.getBlob("data")
                            ?: throw IllegalStateException("Media chunk $index is missing. Tap retry.")
                        val bytes = blob.toBytes()
                        out.write(bytes)
                        FreeTierQuotaManager.recordMediaBytes(bytes.size.toLong())
                        onProgress(((index + 1).toFloat() / totalChunks.toFloat()).coerceIn(0.05f, 0.98f))
                    }
                }
                if (!tempFile.renameTo(resolvedFile)) {
                    tempFile.copyTo(resolvedFile, overwrite = true)
                    tempFile.delete()
                }
            }
            onProgress(1f)
            Result.success(resolvedFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            handleFirestoreError(e, OperationType.GET, mediaRef.path)
            Result.failure(e)
        }
    }
}
