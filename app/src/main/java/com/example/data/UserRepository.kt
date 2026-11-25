package com.example.data

import android.content.Context
import com.example.R
import com.example.model.ContactEntry
import com.example.model.PublicProfile
import com.example.model.UserAccount
import com.example.model.UserSettings
import com.google.firebase.Firebase
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class UserRepository(
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
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    fun observeMyAccount(uid: String): Flow<UserAccount?> = flow {
        if (uid.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfSignedIn(uid, error, OperationType.GET, path)
                    throw error
                }
        )
    }

    fun observeMySettings(uid: String): Flow<UserSettings?> = flow {
        if (uid.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "userSettings/$uid"
        emitAll(
            db.collection("userSettings").document(uid)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserSettings::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfSignedIn(uid, error, OperationType.GET, path)
                    throw error
                }
        )
    }

    fun observePublicProfile(targetUid: String): Flow<PublicProfile?> = flow {
        val myUid = auth.currentUser?.uid
        if (myUid.isNullOrBlank() || targetUid.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "publicProfiles/$targetUid"
        emitAll(
            db.collection("publicProfiles").document(targetUid)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(PublicProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfSignedIn(myUid, error, OperationType.GET, path)
                    throw error
                }
        )
    }

    fun observeContacts(uid: String): Flow<List<ContactEntry>> = flow {
        if (uid.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$uid/contacts"
        emitAll(
            db.collection("users").document(uid).collection("contacts")
                .snapshots()
                .map { snap ->
                    snap.toObjects(ContactEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.fullName.lowercase() }
                }
                .catch { error ->
                    logStreamErrorIfSignedIn(uid, error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun checkUsernameAvailable(rawUsername: String): Result<Boolean> {
        val normalized = ChatUtils.normalizeUsername(rawUsername)
        if (!ChatUtils.isValidUsername(normalized)) {
            return Result.success(false)
        }
        val path = "usernames/$normalized"
        return try {
            requireUserId()
            val doc = db.collection("usernames").document(normalized).get().await()
            Result.success(!doc.exists())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun registerUserProfile(
        fullName: String,
        rawUsername: String,
        email: String,
        bio: String,
        avatarColorHex: String
    ): Result<Unit> {
        val normalizedName = fullName.trim()
        val normalizedUsername = ChatUtils.normalizeUsername(rawUsername)
        val normalizedEmail = email.trim().lowercase()
        val normalizedBio = bio.trim().take(160)

        if (!ChatUtils.isValidFullName(normalizedName)) {
            return Result.failure(IllegalArgumentException("Full name must be between 2 and 60 characters."))
        }
        if (!ChatUtils.isValidUsername(normalizedUsername)) {
            return Result.failure(IllegalArgumentException("Username must be 3–24 characters using letters, numbers, or underscores."))
        }
        if (!ChatUtils.isValidEmail(normalizedEmail)) {
            return Result.failure(IllegalArgumentException("A valid Gmail address is required."))
        }

        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val userRef = db.collection("users").document(uid)
        val publicRef = db.collection("publicProfiles").document(uid)
        val usernameRef = db.collection("usernames").document(normalizedUsername)
        val emailLookupRef = db.collection("emailLookup").document(ChatUtils.sha256Email(normalizedEmail))
        val settingsRef = db.collection("userSettings").document(uid)

        return try {
            db.runTransaction { tx ->
                val existingUsername = tx.get(usernameRef)
                if (existingUsername.exists()) {
                    throw IllegalStateException("Username @$normalizedUsername is already taken.")
                }

                val now = FieldValue.serverTimestamp()

                tx.set(
                    usernameRef,
                    mapOf(
                        "uid" to uid,
                        "username" to normalizedUsername,
                        "createdAt" to now
                    )
                )

                val userMap = mutableMapOf<String, Any>(
                    "uid" to uid,
                    "fullName" to normalizedName,
                    "username" to normalizedUsername,
                    "email" to normalizedEmail,
                    "avatarColorHex" to avatarColorHex,
                    "createdAt" to now,
                    "updatedAt" to now
                )
                if (normalizedBio.isNotEmpty()) {
                    userMap["bio"] = normalizedBio
                }
                tx.set(userRef, userMap)

                val publicMap = mutableMapOf<String, Any>(
                    "uid" to uid,
                    "fullName" to normalizedName,
                    "username" to normalizedUsername,
                    "avatarColorHex" to avatarColorHex,
                    "discoverable" to true,
                    "online" to true,
                    "lastSeen" to now,
                    "createdAt" to now,
                    "updatedAt" to now
                )
                if (normalizedBio.isNotEmpty()) {
                    publicMap["bio"] = normalizedBio
                }
                tx.set(publicRef, publicMap)

                tx.set(
                    emailLookupRef,
                    mapOf(
                        "uid" to uid,
                        "username" to normalizedUsername,
                        "fullName" to normalizedName,
                        "avatarColorHex" to avatarColorHex,
                        "discoverableByEmail" to true,
                        "updatedAt" to now
                    )
                )

                tx.set(
                    settingsRef,
                    mapOf(
                        "userId" to uid,
                        "discoverable" to true,
                        "discoverableByEmail" to true,
                        "showOnlineStatus" to true,
                        "showReadReceipts" to true,
                        "notificationsEnabled" to true,
                        "showNotificationPreview" to true,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                )
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateProfile(
        account: UserAccount,
        fullName: String,
        bio: String,
        avatarColorHex: String
    ): Result<Unit> {
        val normalizedName = fullName.trim()
        val normalizedBio = bio.trim().take(160)
        if (!ChatUtils.isValidFullName(normalizedName)) {
            return Result.failure(IllegalArgumentException("Full name must be between 2 and 60 characters."))
        }
        return try {
            val uid = requireUserId()
            check(uid == account.uid) { "Signed-in user mismatch." }
            val batch = db.batch()
            val now = FieldValue.serverTimestamp()

            batch.update(
                db.collection("users").document(uid),
                mapOf(
                    "fullName" to normalizedName,
                    "bio" to normalizedBio,
                    "avatarColorHex" to avatarColorHex,
                    "updatedAt" to now
                )
            )
            batch.update(
                db.collection("publicProfiles").document(uid),
                mapOf(
                    "fullName" to normalizedName,
                    "bio" to normalizedBio,
                    "avatarColorHex" to avatarColorHex,
                    "updatedAt" to now
                )
            )
            if (account.email.isNotBlank()) {
                batch.update(
                    db.collection("emailLookup").document(ChatUtils.sha256Email(account.email)),
                    mapOf(
                        "fullName" to normalizedName,
                        "avatarColorHex" to avatarColorHex,
                        "updatedAt" to now
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/${account.uid}")
            Result.failure(e)
        }
    }

    suspend fun updateSettings(
        account: UserAccount,
        newSettings: UserSettings
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            check(uid == account.uid) { "Signed-in user mismatch." }
            val batch = db.batch()
            val now = FieldValue.serverTimestamp()

            val settingsMap = mapOf(
                "discoverable" to newSettings.discoverable,
                "discoverableByEmail" to newSettings.discoverableByEmail,
                "showOnlineStatus" to newSettings.showOnlineStatus,
                "showReadReceipts" to newSettings.showReadReceipts,
                "notificationsEnabled" to newSettings.notificationsEnabled,
                "showNotificationPreview" to newSettings.showNotificationPreview,
                "updatedAt" to now
            )
            batch.update(db.collection("userSettings").document(uid), settingsMap)
            batch.update(
                db.collection("publicProfiles").document(uid),
                mapOf(
                    "discoverable" to newSettings.discoverable,
                    "online" to newSettings.showOnlineStatus,
                    "lastSeen" to now,
                    "updatedAt" to now
                )
            )
            if (account.email.isNotBlank()) {
                batch.update(
                    db.collection("emailLookup").document(ChatUtils.sha256Email(account.email)),
                    mapOf(
                        "discoverableByEmail" to newSettings.discoverableByEmail,
                        "updatedAt" to now
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "userSettings/${account.uid}")
            Result.failure(e)
        }
    }

    suspend fun setOnlineStatus(uid: String, online: Boolean, showOnlineStatus: Boolean) {
        if (uid.isBlank() || auth.currentUser?.uid != uid) return
        val path = "publicProfiles/$uid"
        try {
            val now = FieldValue.serverTimestamp()
            db.collection("publicProfiles").document(uid)
                .update(
                    mapOf(
                        "online" to (online && showOnlineStatus),
                        "lastSeen" to now,
                        "updatedAt" to now
                    )
                )
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-fatal presence update; log only if user still authenticated
            if (auth.currentUser?.uid == uid) {
                handleFirestoreError(e, OperationType.UPDATE, path)
            }
        }
    }

    suspend fun updateFcmToken(uid: String, token: String) {
        val clean = token.trim()
        if (uid.isBlank() || clean.isBlank() || clean.length > 512 || auth.currentUser?.uid != uid) return
        val path = "userSettings/$uid"
        try {
            db.collection("userSettings").document(uid)
                .update(
                    mapOf(
                        "fcmToken" to clean,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // User profile may not be registered yet; non-fatal until profile exists
            if (auth.currentUser?.uid == uid && e is FirebaseFirestoreException &&
                e.code != FirebaseFirestoreException.Code.NOT_FOUND
            ) {
                handleFirestoreError(e, OperationType.UPDATE, path)
            }
        }
    }

    suspend fun searchUsers(rawQuery: String, currentUid: String): Result<List<PublicProfile>> {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) return Result.success(emptyList())

        return try {
            requireUserId()
            val resultsByUid = linkedMapOf<String, PublicProfile>()

            // 1. If query looks like an email address, run privacy-preserving SHA-256 emailLookup
            if (trimmed.contains("@") && ChatUtils.isValidEmail(trimmed)) {
                val hash = ChatUtils.sha256Email(trimmed)
                try {
                    val emailDoc = db.collection("emailLookup").document(hash).get().await()
                    if (emailDoc.exists()) {
                        val targetUid = emailDoc.getString("uid").orEmpty()
                        if (targetUid.isNotBlank() && targetUid != currentUid) {
                            resultsByUid[targetUid] = PublicProfile(
                                uid = targetUid,
                                fullName = emailDoc.getString("fullName").orEmpty(),
                                username = emailDoc.getString("username").orEmpty(),
                                avatarColorHex = emailDoc.getString("avatarColorHex") ?: "#0D6EFD",
                                discoverable = true
                            )
                        }
                    }
                } catch (e: FirebaseFirestoreException) {
                    // PERMISSION_DENIED when target user disabled discoverableByEmail; keep private
                    if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        throw e
                    }
                }
            }

            // 2. Search discoverable publicProfiles (rule enforces discoverable == true)
            val queryLower = ChatUtils.normalizeUsername(trimmed)
            val nameQueryLower = trimmed.lowercase()
            val snapshot = db.collection("publicProfiles")
                .whereEqualTo("discoverable", true)
                .limit(60)
                .get()
                .await()

            snapshot.toObjects(PublicProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .filter { profile ->
                    profile.uid.isNotBlank() &&
                        profile.uid != currentUid &&
                        (profile.username.contains(queryLower, ignoreCase = true) ||
                            profile.fullName.lowercase().contains(nameQueryLower))
                }
                .forEach { profile ->
                    resultsByUid[profile.uid] = profile
                }

            Result.success(resultsByUid.values.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "publicProfiles")
            Result.failure(e)
        }
    }

    suspend fun getPublicProfile(targetUid: String): Result<PublicProfile?> {
        if (targetUid.isBlank()) return Result.success(null)
        val path = "publicProfiles/$targetUid"
        return try {
            requireUserId()
            val doc = db.collection("publicProfiles").document(targetUid).get().await()
            val profile = if (doc.exists()) {
                doc.toObject(PublicProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun saveContact(profile: PublicProfile): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (profile.uid.isBlank() || profile.uid == uid) {
            return Result.failure(IllegalArgumentException("Invalid contact."))
        }
        val path = "users/$uid/contacts/${profile.uid}"
        return try {
            db.document(path).set(
                mapOf(
                    "contactUid" to profile.uid,
                    "fullName" to profile.fullName,
                    "username" to profile.username,
                    "avatarColorHex" to profile.avatarColorHex,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun removeContact(contactUid: String): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val path = "users/$uid/contacts/$contactUid"
        return try {
            db.document(path).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
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
