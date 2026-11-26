package com.example.data

import android.content.Context
import com.example.R
import com.example.model.CallHistoryEntry
import com.example.model.CallOutcome
import com.example.model.CallSession
import com.example.model.CallStatus
import com.example.model.CallType
import com.example.model.IceCandidateEntry
import com.example.model.PublicProfile
import com.example.model.UserAccount
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CallRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before placing calls.")

    fun observeIncomingCalls(userId: String): Flow<List<CallSession>> = callbackFlow {
        val registration = db.collection("calls")
            .whereArrayContains("participantIds", userId)
            .whereEqualTo("status", CallStatus.RINGING.name)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val incoming = snapshot?.documents
                    ?.mapNotNull { it.toObject(CallSession::class.java) }
                    ?.filter { it.calleeId == userId }
                    ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                    .orEmpty()
                trySend(incoming)
            }
        awaitClose { registration.remove() }
    }

    fun observeCallSession(callId: String): Flow<CallSession?> = callbackFlow {
        val registration = db.collection("calls").document(callId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(CallSession::class.java))
            }
        awaitClose { registration.remove() }
    }

    fun observeIceCandidates(callId: String, myUid: String): Flow<List<IceCandidateEntry>> = callbackFlow {
        val registration = db.collection("calls")
            .document(callId)
            .collection("candidates")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val remoteCandidates = snapshot?.documents
                    ?.mapNotNull { it.toObject(IceCandidateEntry::class.java) }
                    ?.filter { it.senderId != myUid }
                    .orEmpty()
                trySend(remoteCandidates)
            }
        awaitClose { registration.remove() }
    }

    fun observeCallHistory(userId: String): Flow<List<CallHistoryEntry>> = callbackFlow {
        val registration = db.collection("callHistory")
            .whereArrayContains("participantIds", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents
                    ?.mapNotNull { it.toObject(CallHistoryEntry::class.java) }
                    ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                    .orEmpty()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun createCallSession(
        caller: UserAccount,
        callee: PublicProfile,
        callType: CallType
    ): Result<CallSession> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            FreeTierQuotaManager.canPerformFirestoreWrite(2L).getOrThrow()
            val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(22)}"
            val chatId = ChatUtils.deterministicChatId(uid, callee.uid)
            val participants = ChatUtils.sortedParticipants(uid, callee.uid)
            val now = FieldValue.serverTimestamp()

            val callerColor = caller.avatarColorHex.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: "#0D6EFD"
            val calleeColor = callee.avatarColorHex.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: "#0D6EFD"
            val safeCallerName = caller.fullName.trim().ifBlank { "My Chat User" }.take(60)
            val safeCalleeName = callee.fullName.trim().ifBlank { "My Chat User" }.take(60)

            val payload = hashMapOf<String, Any>(
                "chatId" to chatId,
                "callerId" to uid,
                "callerName" to safeCallerName,
                "callerUsername" to caller.username.take(30),
                "callerColorHex" to callerColor,
                "calleeId" to callee.uid,
                "calleeName" to safeCalleeName,
                "calleeUsername" to callee.username.take(30),
                "calleeColorHex" to calleeColor,
                "participantIds" to participants,
                "callType" to callType.name,
                "status" to CallStatus.RINGING.name,
                "durationSeconds" to 0L,
                "createdAt" to now,
                "updatedAt" to now
            )
            db.collection("calls").document(callId).set(payload).await()
            FreeTierQuotaManager.recordFirestoreWrites(1L)

            val session = CallSession(
                id = callId,
                chatId = chatId,
                callerId = uid,
                callerName = safeCallerName,
                callerUsername = caller.username.take(30),
                callerColorHex = callerColor,
                calleeId = callee.uid,
                calleeName = safeCalleeName,
                calleeUsername = callee.username.take(30),
                calleeColorHex = calleeColor,
                participantIds = participants,
                callType = callType.name,
                status = CallStatus.RINGING.name,
                durationSeconds = 0L
            )
            Result.success(session)
        } catch (e: Exception) {
            FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(e)
            Result.failure(e)
        }
    }

    suspend fun publishOffer(callId: String, offerSdp: String): Result<Unit> = runCatching {
        FreeTierQuotaManager.canPerformFirestoreWrite(1L).getOrThrow()
        db.collection("calls").document(callId).update(
            mapOf(
                "offerSdp" to offerSdp.take(40_000),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        FreeTierQuotaManager.recordFirestoreWrites(1L)
    }.onFailure { FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(it) }

    suspend fun acceptCallAndPublishAnswer(callId: String, answerSdp: String): Result<Unit> = runCatching {
        FreeTierQuotaManager.canPerformFirestoreWrite(1L).getOrThrow()
        val now = FieldValue.serverTimestamp()
        db.collection("calls").document(callId).update(
            mapOf(
                "status" to CallStatus.ACCEPTED.name,
                "answerSdp" to answerSdp.take(40_000),
                "startedAt" to now,
                "updatedAt" to now
            )
        ).await()
        FreeTierQuotaManager.recordFirestoreWrites(1L)
    }.onFailure { FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(it) }

    suspend fun addIceCandidate(
        callId: String,
        participantIds: List<String>,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        FreeTierQuotaManager.canPerformFirestoreWrite(1L).getOrThrow()
        val candidateId = "ice_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val payload = hashMapOf<String, Any>(
            "senderId" to uid,
            "participantIds" to participantIds,
            "sdpMid" to sdpMid.take(64),
            "sdpMLineIndex" to sdpMLineIndex.coerceIn(0, 16),
            "candidate" to candidate.take(2048),
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("calls").document(callId)
            .collection("candidates").document(candidateId)
            .set(payload)
            .await()
        FreeTierQuotaManager.recordFirestoreWrites(1L)
    }.onFailure { FreeTierQuotaManager.detectAndHandleFirestoreQuotaError(it) }

    suspend fun updateCallTerminalState(
        session: CallSession,
        newStatus: CallStatus,
        durationSeconds: Long,
        outcome: CallOutcome
    ): Result<Unit> = runCatching {
        val now = FieldValue.serverTimestamp()
        val safeDuration = durationSeconds.coerceAtLeast(0L)
        runCatching {
            db.collection("calls").document(session.id).update(
                mapOf(
                    "status" to newStatus.name,
                    "endedAt" to now,
                    "durationSeconds" to safeDuration,
                    "updatedAt" to now
                )
            ).await()
            FreeTierQuotaManager.recordFirestoreWrites(1L)
        }

        val callerColor = session.callerColorHex.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: "#0D6EFD"
        val calleeColor = session.calleeColorHex.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: "#0D6EFD"
        val historyPayload = hashMapOf<String, Any>(
            "callId" to session.id,
            "callerId" to session.callerId,
            "callerName" to session.callerName.ifBlank { "My Chat User" }.take(60),
            "callerColorHex" to callerColor,
            "calleeId" to session.calleeId,
            "calleeName" to session.calleeName.ifBlank { "My Chat User" }.take(60),
            "calleeColorHex" to calleeColor,
            "participantIds" to session.participantIds,
            "callType" to session.callType,
            "outcome" to outcome.name,
            "durationSeconds" to safeDuration,
            "createdAt" to now,
            "updatedAt" to now
        )
        val historyRef = db.collection("callHistory").document(session.id)
        val existing = historyRef.get().await()
        if (!existing.exists()) {
            historyRef.set(historyPayload).await()
        } else {
            historyRef.update(
                mapOf(
                    "outcome" to outcome.name,
                    "durationSeconds" to safeDuration,
                    "updatedAt" to now
                )
            ).await()
        }
        FreeTierQuotaManager.recordFirestoreWrites(1L)
        Unit
    }
}
