package com.example.data

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatUtils {
    private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,24}$")

    fun normalizeUsername(raw: String): String =
        raw.trim().removePrefix("@").lowercase(Locale.ROOT)

    fun isValidUsername(raw: String): Boolean =
        USERNAME_REGEX.matches(normalizeUsername(raw))

    fun isValidFullName(raw: String): Boolean =
        raw.trim().length in 2..60

    fun isValidEmail(raw: String): Boolean =
        android.util.Patterns.EMAIL_ADDRESS.matcher(raw.trim()).matches()

    fun sha256Email(email: String): String {
        val normalized = email.trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun deterministicChatId(uid1: String, uid2: String): String =
        listOf(uid1, uid2).sorted().joinToString("_")

    fun sortedParticipants(uid1: String, uid2: String): List<String> =
        listOf(uid1, uid2).sorted()

    fun formatMessageTime(date: Date?): String {
        if (date == null) return ""
        val now = Date()
        val sameDayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return if (sameDayFormat.format(now) == sameDayFormat.format(date)) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
        }
    }

    fun formatFullDate(date: Date?): String {
        if (date == null) return "Recently joined"
        return SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date)
    }

    fun formatCallDateTime(date: Date?): String {
        if (date == null) return ""
        return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
    }

    fun formatDurationSeconds(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    fun formatDurationMs(ms: Long): String = formatDurationSeconds((ms / 1000L).coerceAtLeast(0L))

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    fun isSupportedVideoMime(mimeType: String?): Boolean =
        mimeType?.lowercase(Locale.ROOT) in setOf("video/mp4", "video/webm", "video/3gpp")

    fun isSupportedImageMime(mimeType: String?): Boolean =
        mimeType?.lowercase(Locale.ROOT) in setOf("image/jpeg", "image/jpg", "image/png", "image/webp")
}
