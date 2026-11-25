package com.example.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class PreparedMedia(
    val mediaType: MessageType,
    val mimeType: String,
    val fileName: String,
    val stagedFile: File,
    val sizeBytes: Long,
    val width: Long?,
    val height: Long?,
    val durationMs: Long?,
    val thumbnailBytes: ByteArray
)

object MediaProcessor {
    const val MAX_VIDEO_BYTES: Long = 10L * 1024L * 1024L // 10 MB free-tier cap
    const val CHUNK_SIZE_BYTES: Int = 420_000 // ~420 KB per Firestore chunk
    const val MAX_IMAGE_DIMENSION = 1080
    const val MAX_THUMB_DIMENSION = 144
    const val MAX_PHOTO_BYTES = 380_000 // Fits inside 1 chunk for minimal writes/bandwidth

    fun createCameraCaptureUri(context: Context): Pair<Uri, File> {
        val dir = File(context.cacheDir, "mychat_camera_cache").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return uri to file
    }

    fun getMediaCacheDir(context: Context): File =
        File(context.cacheDir, "mychat_media").apply { mkdirs() }

    fun getCachedMediaFile(context: Context, mediaId: String, mimeType: String?): File {
        val ext = extensionForMime(mimeType)
        return File(getMediaCacheDir(context), "$mediaId$ext")
    }

    fun extensionForMime(mimeType: String?): String = when (mimeType?.lowercase(Locale.ROOT)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "video/webm" -> ".webm"
        "video/3gpp" -> ".3gp"
        "video/mp4" -> ".mp4"
        else -> ".jpg"
    }

    suspend fun preparePhoto(context: Context, sourceUri: Uri): Result<PreparedMedia> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(sourceUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                } ?: throw IllegalStateException("Could not open selected image.")

                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IllegalArgumentException("Selected file is not a valid image.")
                }

                val sampleSize = calculateInSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    MAX_IMAGE_DIMENSION,
                    MAX_IMAGE_DIMENSION
                )
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val rawBitmap = resolver.openInputStream(sourceUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOpts)
                } ?: throw IllegalStateException("Could not decode selected image.")

                val exifRotation = resolver.openInputStream(sourceUri)?.use { input ->
                    runCatching {
                        val exif = ExifInterface(input)
                        when (
                            exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                        ) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    }.getOrDefault(0f)
                } ?: 0f

                val rotated = if (exifRotation != 0f) {
                    val matrix = Matrix().apply { postRotate(exifRotation) }
                    Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        .also { if (it !== rawBitmap) rawBitmap.recycle() }
                } else {
                    rawBitmap
                }

                val scaled = scaleDownBitmap(rotated, MAX_IMAGE_DIMENSION)
                if (scaled !== rotated) rotated.recycle()

                var quality = 80
                var compressedBytes: ByteArray
                do {
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    compressedBytes = out.toByteArray()
                    quality -= 10
                } while (compressedBytes.size > MAX_PHOTO_BYTES && quality >= 45)

                val thumbBitmap = scaleDownBitmap(scaled, MAX_THUMB_DIMENSION)
                val thumbOut = ByteArrayOutputStream()
                thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 62, thumbOut)
                val thumbBytes = thumbOut.toByteArray()
                if (thumbBitmap !== scaled) thumbBitmap.recycle()

                val finalWidth = scaled.width.toLong()
                val finalHeight = scaled.height.toLong()
                scaled.recycle()

                val stagingDir = File(context.cacheDir, "mychat_staging").apply { mkdirs() }
                val stagedFile = File(stagingDir, "photo_${UUID.randomUUID()}.jpg")
                FileOutputStream(stagedFile).use { it.write(compressedBytes) }

                val rawName = resolveDisplayName(context, sourceUri)
                    ?.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                    ?.take(120)
                    ?.ifBlank { null }
                    ?: "photo_${System.currentTimeMillis()}.jpg"
                val fileName = if (rawName.endsWith(".jpg", true) || rawName.endsWith(".jpeg", true)) {
                    rawName
                } else {
                    "$rawName.jpg"
                }

                PreparedMedia(
                    mediaType = MessageType.IMAGE,
                    mimeType = "image/jpeg",
                    fileName = fileName,
                    stagedFile = stagedFile,
                    sizeBytes = compressedBytes.size.toLong(),
                    width = finalWidth,
                    height = finalHeight,
                    durationMs = null,
                    thumbnailBytes = thumbBytes
                )
            }
        }

    suspend fun prepareVideo(context: Context, sourceUri: Uri): Result<PreparedMedia> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val detectedMime = resolver.getType(sourceUri)
                    ?: MimeTypeMap.getFileExtensionFromUrl(sourceUri.toString())
                        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase(Locale.ROOT)) }
                    ?: "video/mp4"

                val normalizedMime = detectedMime.lowercase(Locale.ROOT)
                if (!ChatUtils.isSupportedVideoMime(normalizedMime)) {
                    throw IllegalArgumentException(
                        "Unsupported video format ($normalizedMime). Please select MP4, WebM, or 3GPP."
                    )
                }

                val stagingDir = File(context.cacheDir, "mychat_staging").apply { mkdirs() }
                val stagedFile = File(stagingDir, "video_${UUID.randomUUID()}${extensionForMime(normalizedMime)}")

                var totalBytes = 0L
                resolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            if (totalBytes > MAX_VIDEO_BYTES) {
                                stagedFile.delete()
                                throw IllegalArgumentException(
                                    "Video exceeds the 10 MB free-tier sharing limit. Please trim or pick a shorter clip."
                                )
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IllegalStateException("Could not read selected video.")

                if (totalBytes <= 0L) {
                    stagedFile.delete()
                    throw IllegalArgumentException("Selected video is empty.")
                }

                var durationMs: Long? = null
                var width: Long? = null
                var height: Long? = null
                var thumbBytes = ByteArray(0)

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(stagedFile.absolutePath)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull()
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull()
                    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val scaledThumb = scaleDownBitmap(frame, MAX_THUMB_DIMENSION)
                        val out = ByteArrayOutputStream()
                        scaledThumb.compress(Bitmap.CompressFormat.JPEG, 62, out)
                        thumbBytes = out.toByteArray()
                        if (scaledThumb !== frame) scaledThumb.recycle()
                        frame.recycle()
                    }
                } catch (_: Exception) {
                    // Best-effort metadata & frame extraction
                } finally {
                    runCatching { retriever.release() }
                }

                val rawName = resolveDisplayName(context, sourceUri)
                    ?.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                    ?.take(120)
                    ?.ifBlank { null }
                    ?: "video_${System.currentTimeMillis()}${extensionForMime(normalizedMime)}"

                PreparedMedia(
                    mediaType = MessageType.VIDEO,
                    mimeType = normalizedMime,
                    fileName = rawName,
                    stagedFile = stagedFile,
                    sizeBytes = totalBytes,
                    width = width,
                    height = height,
                    durationMs = durationMs,
                    thumbnailBytes = thumbBytes
                )
            }
        }

    suspend fun saveToDeviceGallery(
        context: Context,
        sourceFile: File,
        mimeType: String,
        displayName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw IllegalStateException("Media file is not downloaded yet.")
            }
            val isVideo = mimeType.startsWith("video/", ignoreCase = true)
            val safeName = displayName.ifBlank {
                if (isVideo) "MyChat_${System.currentTimeMillis()}.mp4"
                else "MyChat_${System.currentTimeMillis()}.jpg"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val relativePath = if (isVideo) {
                    "${Environment.DIRECTORY_MOVIES}/My Chat"
                } else {
                    "${Environment.DIRECTORY_PICTURES}/My Chat"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("Failed to create MediaStore destination.")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open MediaStore output stream.")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                if (isVideo) "Saved to Movies/My Chat" else "Saved to Pictures/My Chat"
            } else {
                val subDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val baseDir = context.getExternalFilesDir(subDir) ?: context.filesDir
                val targetDir = File(baseDir, "My Chat").apply { mkdirs() }
                val dest = File(targetDir, safeName)
                sourceFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(dest.absolutePath),
                    arrayOf(mimeType),
                    null
                )
                "Saved as $safeName"
            }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun scaleDownBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / largest.toFloat()
        val newW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
