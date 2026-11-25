package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatRepository
import com.example.data.ChatUtils
import com.example.data.UserRepository
import com.example.data.friendlyFirestoreMessage
import com.example.model.ContactEntry
import com.example.model.PrivateChat
import com.example.model.PublicProfile
import com.example.model.UiState
import com.example.model.UserAccount
import com.example.model.UserSettings
import com.google.firebase.Firebase
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainViewModel(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val currentUserId: String
) : ViewModel() {

    val accountState: StateFlow<UiState<UserAccount?>> = userRepository.observeMyAccount(currentUserId)
        .map<UserAccount?, UiState<UserAccount?>> { UiState.Success(it) }
        .catch { emitError(it, "Failed to load account") { msg -> emit(UiState.Error(msg)) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val settingsState: StateFlow<UserSettings> = userRepository.observeMySettings(currentUserId)
        .map { it ?: UserSettings(userId = currentUserId) }
        .catch { error ->
            if (isPureCancellation(error)) throw error
            emit(UserSettings(userId = currentUserId))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UserSettings(userId = currentUserId)
        )

    val chatsState: StateFlow<UiState<List<PrivateChat>>> = chatRepository.observeUserChats(currentUserId)
        .map<List<PrivateChat>, UiState<List<PrivateChat>>> { UiState.Success(it) }
        .catch { emitError(it, "Failed to load conversations") { msg -> emit(UiState.Error(msg)) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val contactsState: StateFlow<UiState<List<ContactEntry>>> = userRepository.observeContacts(currentUserId)
        .map<List<ContactEntry>, UiState<List<ContactEntry>>> { UiState.Success(it) }
        .catch { emitError(it, "Failed to load contacts") { msg -> emit(UiState.Error(msg)) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    private val _searchResults = MutableStateFlow<UiState<List<PublicProfile>>>(UiState.Success(emptyList()))
    val searchResults: StateFlow<UiState<List<PublicProfile>>> = _searchResults.asStateFlow()

    private val _usernameAvailable = MutableStateFlow<Boolean?>(null)
    val usernameAvailable: StateFlow<Boolean?> = _usernameAvailable.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _peerProfiles = MutableStateFlow<Map<String, PublicProfile>>(emptyMap())
    val peerProfiles: StateFlow<Map<String, PublicProfile>> = _peerProfiles.asStateFlow()

    private var usernameCheckJob: Job? = null
    private var searchJob: Job? = null
    private val observedPeerUids = mutableSetOf<String>()

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun syncFcmToken() {
        viewModelScope.launch {
            runCatching {
                val token = Firebase.messaging.token.await()
                if (!token.isNullOrBlank()) {
                    userRepository.updateFcmToken(currentUserId, token)
                }
            }
        }
    }

    fun checkUsernameAvailability(rawUsername: String) {
        usernameCheckJob?.cancel()
        val normalized = ChatUtils.normalizeUsername(rawUsername)
        if (!ChatUtils.isValidUsername(normalized)) {
            _usernameAvailable.value = null
            return
        }
        usernameCheckJob = viewModelScope.launch {
            delay(350)
            val result = userRepository.checkUsernameAvailable(normalized)
            result.onSuccess { available ->
                _usernameAvailable.value = available
            }
        }
    }

    fun registerProfile(
        fullName: String,
        username: String,
        email: String,
        bio: String,
        avatarColorHex: String
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = userRepository.registerUserProfile(
                fullName = fullName,
                rawUsername = username,
                email = email,
                bio = bio,
                avatarColorHex = avatarColorHex
            )
            _isBusy.value = false
            result.onSuccess {
                syncFcmToken()
            }.onFailure { error ->
                _actionMessage.value = friendlyFirestoreMessage(error, "Registration failed.")
            }
        }
    }

    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = UiState.Success(emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            _searchResults.value = UiState.Loading
            val result = userRepository.searchUsers(query, currentUserId)
            result.onSuccess { list ->
                _searchResults.value = UiState.Success(list)
            }.onFailure { error ->
                _searchResults.value = UiState.Error(friendlyFirestoreMessage(error, "User search failed."))
            }
        }
    }

    fun startPrivateChat(
        account: UserAccount,
        peer: PublicProfile,
        onChatReady: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = chatRepository.getOrCreateChat(account, peer)
            _isBusy.value = false
            result.onSuccess { chatId ->
                onChatReady(chatId)
            }.onFailure { error ->
                _actionMessage.value = friendlyFirestoreMessage(error, "Could not start chat.")
            }
        }
    }

    fun toggleContact(profile: PublicProfile, isCurrentlySaved: Boolean) {
        viewModelScope.launch {
            val result = if (isCurrentlySaved) {
                userRepository.removeContact(profile.uid)
            } else {
                userRepository.saveContact(profile)
            }
            result.onSuccess {
                _actionMessage.value = if (isCurrentlySaved) "Removed from contacts" else "Added to contacts"
            }.onFailure { error ->
                _actionMessage.value = friendlyFirestoreMessage(error, "Failed to update contact.")
            }
        }
    }

    fun updateProfile(
        account: UserAccount,
        fullName: String,
        bio: String,
        avatarColorHex: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = userRepository.updateProfile(account, fullName, bio, avatarColorHex)
            _isBusy.value = false
            result.onSuccess {
                _actionMessage.value = "Profile updated"
                onDone()
            }.onFailure { error ->
                _actionMessage.value = friendlyFirestoreMessage(error, "Failed to update profile.")
            }
        }
    }

    fun updateSettings(account: UserAccount, newSettings: UserSettings) {
        viewModelScope.launch {
            val result = userRepository.updateSettings(account, newSettings)
            result.onFailure { error ->
                _actionMessage.value = friendlyFirestoreMessage(error, "Failed to update settings.")
            }
        }
    }

    fun setOnlinePresence(online: Boolean) {
        val showOnline = settingsState.value.showOnlineStatus
        viewModelScope.launch {
            userRepository.setOnlineStatus(currentUserId, online, showOnline)
        }
    }

    suspend fun setOfflineBeforeSignOut() {
        userRepository.setOnlineStatus(currentUserId, online = false, showOnlineStatus = false)
    }

    fun markChatsDelivered(chats: List<PrivateChat>) {
        viewModelScope.launch {
            chatRepository.markIncomingDelivered(chats, currentUserId)
        }
        chats.forEach { chat ->
            val peerUid = chat.peerId(currentUserId)
            if (peerUid.isNotBlank() && observedPeerUids.add(peerUid)) {
                observePeerOnlineStatus(peerUid)
            }
        }
    }

    private fun observePeerOnlineStatus(peerUid: String) {
        viewModelScope.launch {
            userRepository.observePublicProfile(peerUid)
                .catch { error ->
                    if (isPureCancellation(error)) throw error
                }
                .collect { profile ->
                    if (profile != null) {
                        _peerProfiles.value = _peerProfiles.value + (peerUid to profile)
                    }
                }
        }
    }

    suspend fun fetchPublicProfile(targetUid: String): PublicProfile? {
        return userRepository.getPublicProfile(targetUid).getOrNull()
    }

    private fun isPureCancellation(error: Throwable): Boolean {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        return firestoreError == null && error is CancellationException
    }

    private suspend fun emitError(
        error: Throwable,
        fallback: String,
        emitter: suspend (String) -> Unit
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        emitter(friendlyFirestoreMessage(rootError, fallback))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
