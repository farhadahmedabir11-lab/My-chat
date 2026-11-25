package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FreeTierQuotaSnapshot(
    val dayKey: String,
    val dailyFirestoreWrites: Long,
    val maxDailyFirestoreWrites: Long,
    val dailyMediaBytes: Long,
    val maxDailyMediaBytes: Long,
    val firestoreQuotaExceeded: Boolean,
    val activeWarningMessage: String?
) {
    val isMediaBlocked: Boolean
        get() = firestoreQuotaExceeded ||
            dailyFirestoreWrites >= maxDailyFirestoreWrites ||
            dailyMediaBytes >= maxDailyMediaBytes
}

/**
 * Enforces strict Firebase Spark Plan free-tier limits so the app pauses affected services and
 * warns the user instead of exceeding free quotas or incurring charges.
 */
object FreeTierQuotaManager {
    const val MAX_DAILY_FIRESTORE_WRITES = 18_000L // Safe cap below Spark's 20k writes/day
    const val MAX_DAILY_MEDIA_BYTES = 100L * 1024L * 1024L // 100 MB/day safe Spark budget

    private const val PREFS_NAME = "mychat_free_tier_quota"
    private const val KEY_DAY = "day_key"
    private const val KEY_DAILY_WRITES = "daily_writes"
    private const val KEY_DAILY_MEDIA_BYTES = "daily_media_bytes"
    private const val KEY_FIRESTORE_EXCEEDED = "firestore_exceeded"
    private const val KEY_WARNING = "active_warning"

    private val lock = Any()
    @Volatile
    private var prefs: SharedPreferences? = null

    private val _snapshot = MutableStateFlow(
        FreeTierQuotaSnapshot(
            dayKey = currentDayKey(),
            dailyFirestoreWrites = 0L,
            maxDailyFirestoreWrites = MAX_DAILY_FIRESTORE_WRITES,
            dailyMediaBytes = 0L,
            maxDailyMediaBytes = MAX_DAILY_MEDIA_BYTES,
            firestoreQuotaExceeded = false,
            activeWarningMessage = null
        )
    )
    val snapshot: StateFlow<FreeTierQuotaSnapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            refreshLocked()
        }
    }

    fun canPerformFirestoreWrite(writesNeeded: Long = 1L): Result<Unit> = synchronized(lock) {
        val current = refreshLocked()
        if (current.firestoreQuotaExceeded || current.dailyFirestoreWrites + writesNeeded > MAX_DAILY_FIRESTORE_WRITES) {
            val msg = "Firebase Spark daily free write quota reached. Messaging & image uploads are paused until tomorrow."
            setWarningLocked(msg)
            return Result.failure(IllegalStateException(msg))
        }
        Result.success(Unit)
    }

    fun canPerformMediaUpload(estimatedBytes: Long, estimatedWrites: Long): Result<Unit> = synchronized(lock) {
        val current = refreshLocked()
        if (current.firestoreQuotaExceeded || current.dailyFirestoreWrites + estimatedWrites > MAX_DAILY_FIRESTORE_WRITES) {
            val msg = "Firebase Spark daily free write limit reached. Media uploads are paused until tomorrow."
            setWarningLocked(msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (current.dailyMediaBytes + estimatedBytes > MAX_DAILY_MEDIA_BYTES) {
            val msg = "Daily free media transfer limit (100 MB/day) reached. Uploads paused to keep your account on the free Spark plan."
            setWarningLocked(msg)
            return Result.failure(IllegalStateException(msg))
        }
        Result.success(Unit)
    }

    fun recordFirestoreWrites(count: Long) = synchronized(lock) {
        if (count <= 0L) return@synchronized
        val p = prefs ?: return@synchronized
        refreshLocked()
        val next = p.getLong(KEY_DAILY_WRITES, 0L) + count
        p.edit().putLong(KEY_DAILY_WRITES, next).apply()
        refreshLocked()
    }

    fun recordMediaBytes(bytes: Long) = synchronized(lock) {
        if (bytes <= 0L) return@synchronized
        val p = prefs ?: return@synchronized
        refreshLocked()
        val next = p.getLong(KEY_DAILY_MEDIA_BYTES, 0L) + bytes
        p.edit().putLong(KEY_DAILY_MEDIA_BYTES, next).apply()
        refreshLocked()
    }

    fun markFirestoreQuotaExceeded(detail: String? = null) = synchronized(lock) {
        val p = prefs ?: return@synchronized
        refreshLocked()
        val msg = detail?.takeIf { it.isNotBlank() }
            ?: "Firebase Spark free quota exceeded. Service paused until daily reset — no paid plan enabled."
        p.edit()
            .putBoolean(KEY_FIRESTORE_EXCEEDED, true)
            .putString(KEY_WARNING, msg)
            .apply()
        refreshLocked()
    }

    fun clearWarning() = synchronized(lock) {
        prefs?.edit()?.remove(KEY_WARNING)?.apply()
        refreshLocked()
    }

    fun detectAndHandleFirestoreQuotaError(error: Throwable): Boolean {
        val firestoreEx = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreEx?.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED) {
            markFirestoreQuotaExceeded()
            return true
        }
        return false
    }

    private fun setWarningLocked(message: String) {
        prefs?.edit()?.putString(KEY_WARNING, message)?.apply()
        refreshLocked()
    }

    private fun refreshLocked(): FreeTierQuotaSnapshot {
        val today = currentDayKey()
        val p = prefs

        if (p != null) {
            val savedDay = p.getString(KEY_DAY, null)
            if (savedDay != today) {
                p.edit()
                    .putString(KEY_DAY, today)
                    .putLong(KEY_DAILY_WRITES, 0L)
                    .putLong(KEY_DAILY_MEDIA_BYTES, 0L)
                    .putBoolean(KEY_FIRESTORE_EXCEEDED, false)
                    .remove(KEY_WARNING)
                    .apply()
            }
        }

        val snap = FreeTierQuotaSnapshot(
            dayKey = today,
            dailyFirestoreWrites = p?.getLong(KEY_DAILY_WRITES, 0L) ?: 0L,
            maxDailyFirestoreWrites = MAX_DAILY_FIRESTORE_WRITES,
            dailyMediaBytes = p?.getLong(KEY_DAILY_MEDIA_BYTES, 0L) ?: 0L,
            maxDailyMediaBytes = MAX_DAILY_MEDIA_BYTES,
            firestoreQuotaExceeded = p?.getBoolean(KEY_FIRESTORE_EXCEEDED, false) ?: false,
            activeWarningMessage = p?.getString(KEY_WARNING, null)
        )
        _snapshot.value = snap
        return snap
    }

    private fun currentDayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
