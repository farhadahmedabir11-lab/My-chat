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
    val monthKey: String,
    val dailyFirestoreWrites: Long,
    val maxDailyFirestoreWrites: Long,
    val dailyMediaBytes: Long,
    val maxDailyMediaBytes: Long,
    val monthlyTurnBytes: Long,
    val maxMonthlyTurnBytes: Long,
    val monthlyRealtimeMessages: Long,
    val maxMonthlyRealtimeMessages: Long,
    val firestoreQuotaExceeded: Boolean,
    val turnQuotaExceeded: Boolean,
    val realtimeQuotaExceeded: Boolean,
    val activeWarningMessage: String?
) {
    val isMediaBlocked: Boolean
        get() = firestoreQuotaExceeded ||
            dailyFirestoreWrites >= maxDailyFirestoreWrites ||
            dailyMediaBytes >= maxDailyMediaBytes

    val isCallsBlocked: Boolean
        get() = firestoreQuotaExceeded ||
            dailyFirestoreWrites >= maxDailyFirestoreWrites

    val isTurnRelayBlocked: Boolean
        get() = turnQuotaExceeded || monthlyTurnBytes >= maxMonthlyTurnBytes

    val isRealtimeSignalingBlocked: Boolean
        get() = realtimeQuotaExceeded || monthlyRealtimeMessages >= maxMonthlyRealtimeMessages
}

/**
 * Enforces strict Firebase Spark Plan + Metered free-tier limits so the app pauses affected
 * services and warns the user instead of exceeding free quotas or incurring charges.
 */
object FreeTierQuotaManager {
    const val MAX_DAILY_FIRESTORE_WRITES = 18_000L // Safe cap below Spark's 20k writes/day
    const val MAX_DAILY_MEDIA_BYTES = 100L * 1024L * 1024L // 100 MB/day safe Spark budget
    const val MAX_MONTHLY_TURN_BYTES = 20L * 1024L * 1024L * 1024L // 20 GB/month Metered Open Relay limit
    const val MAX_MONTHLY_REALTIME_MESSAGES = 95_000L // Safe cap below 100k/mo free tier

    private const val PREFS_NAME = "mychat_free_tier_quota"
    private const val KEY_DAY = "day_key"
    private const val KEY_MONTH = "month_key"
    private const val KEY_DAILY_WRITES = "daily_writes"
    private const val KEY_DAILY_MEDIA_BYTES = "daily_media_bytes"
    private const val KEY_MONTHLY_TURN_BYTES = "monthly_turn_bytes"
    private const val KEY_MONTHLY_REALTIME_MSGS = "monthly_realtime_msgs"
    private const val KEY_FIRESTORE_EXCEEDED = "firestore_exceeded"
    private const val KEY_TURN_EXCEEDED = "turn_exceeded"
    private const val KEY_REALTIME_EXCEEDED = "realtime_exceeded"
    private const val KEY_WARNING = "active_warning"

    private val lock = Any()
    @Volatile
    private var prefs: SharedPreferences? = null

    private val _snapshot = MutableStateFlow(
        FreeTierQuotaSnapshot(
            dayKey = currentDayKey(),
            monthKey = currentMonthKey(),
            dailyFirestoreWrites = 0L,
            maxDailyFirestoreWrites = MAX_DAILY_FIRESTORE_WRITES,
            dailyMediaBytes = 0L,
            maxDailyMediaBytes = MAX_DAILY_MEDIA_BYTES,
            monthlyTurnBytes = 0L,
            maxMonthlyTurnBytes = MAX_MONTHLY_TURN_BYTES,
            monthlyRealtimeMessages = 0L,
            maxMonthlyRealtimeMessages = MAX_MONTHLY_REALTIME_MESSAGES,
            firestoreQuotaExceeded = false,
            turnQuotaExceeded = false,
            realtimeQuotaExceeded = false,
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
            val msg = "Firebase Spark daily free write quota reached. Messaging & signaling are paused until tomorrow."
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

    fun canUseTurnServer(): Result<Unit> = synchronized(lock) {
        val current = refreshLocked()
        if (current.isTurnRelayBlocked) {
            val msg = "Monthly free TURN relay limit (20 GB/month) reached. Using direct STUN peer connection only."
            setWarningLocked(msg)
            return Result.failure(IllegalStateException(msg))
        }
        Result.success(Unit)
    }

    fun canUseMeteredRealtime(): Result<Unit> = synchronized(lock) {
        val current = refreshLocked()
        if (current.isRealtimeSignalingBlocked) {
            val msg = "Monthly Metered Realtime free signaling limit reached. Falling back to Firestore signaling."
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

    fun recordTurnBytes(bytes: Long) = synchronized(lock) {
        if (bytes <= 0L) return@synchronized
        val p = prefs ?: return@synchronized
        refreshLocked()
        val next = p.getLong(KEY_MONTHLY_TURN_BYTES, 0L) + bytes
        val exceeded = next >= MAX_MONTHLY_TURN_BYTES
        p.edit()
            .putLong(KEY_MONTHLY_TURN_BYTES, next)
            .apply {
                if (exceeded) {
                    putBoolean(KEY_TURN_EXCEEDED, true)
                    putString(KEY_WARNING, "Monthly free TURN limit (20 GB) reached. TURN relay paused cleanly — no billing enabled.")
                }
            }
            .apply()
        refreshLocked()
    }

    fun recordRealtimeMessage(count: Long = 1L) = synchronized(lock) {
        if (count <= 0L) return@synchronized
        val p = prefs ?: return@synchronized
        refreshLocked()
        val next = p.getLong(KEY_MONTHLY_REALTIME_MSGS, 0L) + count
        val exceeded = next >= MAX_MONTHLY_REALTIME_MESSAGES
        p.edit()
            .putLong(KEY_MONTHLY_REALTIME_MSGS, next)
            .apply {
                if (exceeded) {
                    putBoolean(KEY_REALTIME_EXCEEDED, true)
                    putString(KEY_WARNING, "Monthly Metered Realtime free limit reached. Service paused cleanly.")
                }
            }
            .apply()
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

    fun markTurnQuotaExceeded(detail: String? = null) = synchronized(lock) {
        val p = prefs ?: return@synchronized
        refreshLocked()
        val msg = detail?.takeIf { it.isNotBlank() }
            ?: "Free Metered TURN server quota exceeded. Relay paused — using free STUN peer connection only."
        p.edit()
            .putBoolean(KEY_TURN_EXCEEDED, true)
            .putString(KEY_WARNING, msg)
            .apply()
        refreshLocked()
    }

    fun markRealtimeQuotaExceeded(detail: String? = null) = synchronized(lock) {
        val p = prefs ?: return@synchronized
        refreshLocked()
        val msg = detail?.takeIf { it.isNotBlank() }
            ?: "Free Metered Realtime signaling quota exceeded. Switched to Firestore signaling."
        p.edit()
            .putBoolean(KEY_REALTIME_EXCEEDED, true)
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
        val thisMonth = currentMonthKey()
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
            val savedMonth = p.getString(KEY_MONTH, null)
            if (savedMonth != thisMonth) {
                p.edit()
                    .putString(KEY_MONTH, thisMonth)
                    .putLong(KEY_MONTHLY_TURN_BYTES, 0L)
                    .putLong(KEY_MONTHLY_REALTIME_MSGS, 0L)
                    .putBoolean(KEY_TURN_EXCEEDED, false)
                    .putBoolean(KEY_REALTIME_EXCEEDED, false)
                    .apply()
            }
        }

        val snap = FreeTierQuotaSnapshot(
            dayKey = today,
            monthKey = thisMonth,
            dailyFirestoreWrites = p?.getLong(KEY_DAILY_WRITES, 0L) ?: 0L,
            maxDailyFirestoreWrites = MAX_DAILY_FIRESTORE_WRITES,
            dailyMediaBytes = p?.getLong(KEY_DAILY_MEDIA_BYTES, 0L) ?: 0L,
            maxDailyMediaBytes = MAX_DAILY_MEDIA_BYTES,
            monthlyTurnBytes = p?.getLong(KEY_MONTHLY_TURN_BYTES, 0L) ?: 0L,
            maxMonthlyTurnBytes = MAX_MONTHLY_TURN_BYTES,
            monthlyRealtimeMessages = p?.getLong(KEY_MONTHLY_REALTIME_MSGS, 0L) ?: 0L,
            maxMonthlyRealtimeMessages = MAX_MONTHLY_REALTIME_MESSAGES,
            firestoreQuotaExceeded = p?.getBoolean(KEY_FIRESTORE_EXCEEDED, false) ?: false,
            turnQuotaExceeded = p?.getBoolean(KEY_TURN_EXCEEDED, false) ?: false,
            realtimeQuotaExceeded = p?.getBoolean(KEY_REALTIME_EXCEEDED, false) ?: false,
            activeWarningMessage = p?.getString(KEY_WARNING, null)
        )
        _snapshot.value = snap
        return snap
    }

    private fun currentDayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun currentMonthKey(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
}
