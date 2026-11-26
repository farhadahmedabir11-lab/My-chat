package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.CallRepository
import com.example.data.ChatRepository
import com.example.data.FreeTierQuotaManager
import com.example.data.MediaRepository
import com.example.data.UserRepository
import com.example.data.attemptAutoSignIn
import com.example.data.authStateFlow
import com.example.data.signOutSession
import com.example.model.CallSession
import com.example.model.CallType
import com.example.model.PublicProfile
import com.example.model.UiState
import com.example.notifications.ChatNotificationHelper
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.IncomingCallOverlay
import com.example.ui.screens.ProfileSetupScreen
import com.example.ui.theme.MyChatBluePrimary
import com.example.ui.theme.MyChatTheme
import com.example.viewmodel.CallViewModel
import com.example.viewmodel.ChatViewModel
import com.example.viewmodel.MainViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat/{chatId}"

private sealed interface PendingCallLaunch {
    data class Outgoing(
        val peerUid: String,
        val peerName: String,
        val peerColorHex: String,
        val callType: CallType
    ) : PendingCallLaunch

    data class Incoming(val session: CallSession) : PendingCallLaunch
}

class MainActivity : ComponentActivity() {
    private var pendingChatId by mutableStateOf<String?>(null)
    private var pendingCallId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FreeTierQuotaManager.init(this)
        enableEdgeToEdge()
        consumeIntent(intent)
        ChatNotificationHelper.ensureChannel(this)

        setContent {
            MyChatTheme {
                AppRoot(
                    pendingChatId = pendingChatId,
                    pendingCallId = pendingCallId,
                    onConsumedPendingChat = { pendingChatId = null },
                    onConsumedPendingCall = { pendingCallId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        val chatId = intent.getStringExtra(ChatNotificationHelper.EXTRA_CHAT_ID)
        if (!chatId.isNullOrBlank()) {
            pendingChatId = chatId
        }
        val callId = intent.getStringExtra(ChatNotificationHelper.EXTRA_CALL_ID)
        if (!callId.isNullOrBlank()) {
            pendingCallId = callId
        }
    }
}

@Composable
private fun AppRoot(
    pendingChatId: String?,
    pendingCallId: String?,
    onConsumedPendingChat: () -> Unit,
    onConsumedPendingCall: () -> Unit,
    auth: FirebaseAuth = Firebase.auth
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var isCheckingSession by remember { mutableStateOf(auth.currentUser == null) }

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = { isCheckingSession = false },
                onUnauthenticated = { isCheckingSession = false },
                scope = scope
            )
        } else {
            isCheckingSession = false
        }
    }

    val user = currentUser
    when {
        isCheckingSession && user == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MyChatBluePrimary)
            }
        }
        user == null -> {
            AuthScreen(
                onAuthSuccess = { isCheckingSession = false }
            )
        }
        else -> {
            key(user.uid) {
                AuthenticatedApp(
                    firebaseUser = user,
                    pendingChatId = pendingChatId,
                    pendingCallId = pendingCallId,
                    onConsumedPendingChat = onConsumedPendingChat,
                    onConsumedPendingCall = onConsumedPendingCall,
                    credentialManager = credentialManager
                )
            }
        }
    }
}

@Composable
private fun AuthenticatedApp(
    firebaseUser: FirebaseUser,
    pendingChatId: String?,
    pendingCallId: String?,
    onConsumedPendingChat: () -> Unit,
    onConsumedPendingCall: () -> Unit,
    credentialManager: CredentialManager
) {
    val uid = firebaseUser.uid
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val mainViewModel: MainViewModel = viewModel(
        key = uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                MainViewModel(
                    userRepository = UserRepository(db),
                    chatRepository = ChatRepository(db),
                    currentUserId = uid
                )
            }
        }
    )

    val callViewModel: CallViewModel = viewModel(
        key = "calls_$uid",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                CallViewModel(
                    callRepository = CallRepository(db),
                    appContext = app.applicationContext,
                    currentUserId = uid
                )
            }
        }
    )

    val accountState by mainViewModel.accountState.collectAsStateWithLifecycle()
    val settings by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val chatsState by mainViewModel.chatsState.collectAsStateWithLifecycle()
    val contactsState by mainViewModel.contactsState.collectAsStateWithLifecycle()
    val searchResultsState by mainViewModel.searchResults.collectAsStateWithLifecycle()
    val peerProfiles by mainViewModel.peerProfiles.collectAsStateWithLifecycle()
    val usernameAvailable by mainViewModel.usernameAvailable.collectAsStateWithLifecycle()
    val actionMessage by mainViewModel.actionMessage.collectAsStateWithLifecycle()
    val isBusy by mainViewModel.isBusy.collectAsStateWithLifecycle()

    val callHistoryState by callViewModel.callHistoryState.collectAsStateWithLifecycle()
    val incomingCall by callViewModel.incomingCall.collectAsStateWithLifecycle()
    val activeCallState by callViewModel.activeCallState.collectAsStateWithLifecycle()
    val hasRemoteVideo by callViewModel.hasRemoteVideo.collectAsStateWithLifecycle()

    val navController = rememberNavController()

    var activeChatId by remember { mutableStateOf<String?>(null) }
    var isAppInForeground by remember { mutableStateOf(true) }
    var lastNotifiedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialChatsLoaded by remember { mutableStateOf(false) }
    var pendingCallLaunch by remember { mutableStateOf<PendingCallLaunch?>(null) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val launch = pendingCallLaunch
        pendingCallLaunch = null
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!audioGranted || launch == null) return@rememberLauncherForActivityResult

        val myAccount = (accountState as? UiState.Success)?.data
        when (launch) {
            is PendingCallLaunch.Outgoing -> {
                if (myAccount != null) {
                    callViewModel.startOutgoingCall(
                        caller = myAccount,
                        callee = PublicProfile(
                            uid = launch.peerUid,
                            fullName = launch.peerName,
                            avatarColorHex = launch.peerColorHex
                        ),
                        callType = launch.callType
                    )
                }
            }
            is PendingCallLaunch.Incoming -> {
                callViewModel.acceptIncomingCall(launch.session)
            }
        }
    }

    val launchCallWithPermissions: (PendingCallLaunch) -> Unit = { launch ->
        val needsCamera = when (launch) {
            is PendingCallLaunch.Outgoing -> launch.callType == CallType.VIDEO
            is PendingCallLaunch.Incoming -> launch.session.parsedType == CallType.VIDEO
        }
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (needsCamera) add(Manifest.permission.CAMERA)
        }
        val allGranted = required.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            val myAccount = (accountState as? UiState.Success)?.data
            when (launch) {
                is PendingCallLaunch.Outgoing -> {
                    if (myAccount != null) {
                        callViewModel.startOutgoingCall(
                            caller = myAccount,
                            callee = PublicProfile(
                                uid = launch.peerUid,
                                fullName = launch.peerName,
                                avatarColorHex = launch.peerColorHex
                            ),
                            callType = launch.callType
                        )
                    }
                }
                is PendingCallLaunch.Incoming -> {
                    callViewModel.acceptIncomingCall(launch.session)
                }
            }
        } else {
            pendingCallLaunch = launch
            callPermissionLauncher.launch(required.toTypedArray())
        }
    }

    val handleLogout: () -> Unit = {
        signOutSession(
            credentialManager = credentialManager,
            scope = scope,
            onBeforeSignOut = { mainViewModel.setOfflineBeforeSignOut() }
        )
    }

    when (val accState = accountState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MyChatBluePrimary)
            }
        }
        is UiState.Error -> {
            ProfileSetupScreen(
                initialName = firebaseUser.displayName.orEmpty(),
                verifiedEmail = firebaseUser.email.orEmpty(),
                usernameAvailable = usernameAvailable,
                isBusy = isBusy,
                errorMessage = accState.message,
                onUsernameChanged = mainViewModel::checkUsernameAvailability,
                onSubmit = { name, username, bio, color ->
                    mainViewModel.registerProfile(
                        fullName = name,
                        username = username,
                        email = firebaseUser.email.orEmpty(),
                        bio = bio,
                        avatarColorHex = color
                    )
                },
                onLogout = handleLogout
            )
        }
        is UiState.Success -> {
            val account = accState.data
            if (account == null) {
                ProfileSetupScreen(
                    initialName = firebaseUser.displayName.orEmpty(),
                    verifiedEmail = firebaseUser.email.orEmpty(),
                    usernameAvailable = usernameAvailable,
                    isBusy = isBusy,
                    errorMessage = actionMessage,
                    onUsernameChanged = mainViewModel::checkUsernameAvailability,
                    onSubmit = { name, username, bio, color ->
                        mainViewModel.clearActionMessage()
                        mainViewModel.registerProfile(
                            fullName = name,
                            username = username,
                            email = firebaseUser.email.orEmpty(),
                            bio = bio,
                            avatarColorHex = color
                        )
                    },
                    onLogout = handleLogout
                )
            } else {
                LaunchedEffect(uid) {
                    mainViewModel.syncFcmToken()
                }

                // Track foreground/background presence
                DisposableEffect(lifecycleOwner, account.uid, settings.showOnlineStatus) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                isAppInForeground = true
                                mainViewModel.setOnlinePresence(true)
                            }
                            Lifecycle.Event.ON_STOP -> {
                                isAppInForeground = false
                                mainViewModel.setOnlinePresence(false)
                            }
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Mark incoming messages delivered + dispatch local alerts for new incoming messages
                LaunchedEffect(chatsState, isAppInForeground, activeChatId, settings) {
                    val chats = (chatsState as? UiState.Success)?.data ?: return@LaunchedEffect
                    mainViewModel.markChatsDelivered(chats)

                    val incomingKeys = chats
                        .filter { it.lastMessageSenderId.isNotBlank() && it.lastMessageSenderId != uid && it.unreadFor(uid) > 0 }
                        .map { "${it.id}_${it.lastMessageTime?.seconds ?: 0}_${it.lastMessageText.hashCode()}" }
                        .toSet()

                    if (!initialChatsLoaded) {
                        initialChatsLoaded = true
                        lastNotifiedKeys = incomingKeys
                        return@LaunchedEffect
                    }

                    val newAlerts = chats.filter { chat ->
                        val key = "${chat.id}_${chat.lastMessageTime?.seconds ?: 0}_${chat.lastMessageText.hashCode()}"
                        chat.lastMessageSenderId.isNotBlank() &&
                            chat.lastMessageSenderId != uid &&
                            chat.unreadFor(uid) > 0 &&
                            key !in lastNotifiedKeys &&
                            (!isAppInForeground || activeChatId != chat.id)
                    }

                    if (settings.notificationsEnabled) {
                        newAlerts.forEach { chat ->
                            ChatNotificationHelper.showIncomingMessageNotification(
                                context = context,
                                chatId = chat.id,
                                senderName = chat.peerName(uid),
                                messagePreview = chat.lastMessageText,
                                showPreview = settings.showNotificationPreview
                            )
                        }
                    }
                    lastNotifiedKeys = incomingKeys
                }

                // Trigger notification for incoming call when app is backgrounded
                LaunchedEffect(incomingCall, isAppInForeground) {
                    val incoming = incomingCall ?: return@LaunchedEffect
                    if (!isAppInForeground) {
                        ChatNotificationHelper.showIncomingCallNotification(
                            context = context,
                            callId = incoming.id,
                            callerName = incoming.callerName,
                            isVideo = incoming.parsedType == CallType.VIDEO
                        )
                    }
                }

                LaunchedEffect(pendingCallId, incomingCall) {
                    if (!pendingCallId.isNullOrBlank() && incomingCall?.id == pendingCallId) {
                        launchCallWithPermissions(PendingCallLaunch.Incoming(incomingCall!!))
                        onConsumedPendingCall()
                    }
                }

                LaunchedEffect(pendingChatId) {
                    if (!pendingChatId.isNullOrBlank()) {
                        navController.navigate("chat/$pendingChatId") {
                            launchSingleTop = true
                        }
                        onConsumedPendingChat()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_HOME
                    ) {
                        composable(ROUTE_HOME) {
                            LaunchedEffect(Unit) { activeChatId = null }
                            HomeScreen(
                                currentUserId = uid,
                                account = account,
                                settings = settings,
                                chatsState = chatsState,
                                callHistoryState = callHistoryState,
                                contactsState = contactsState,
                                searchResultsState = searchResultsState,
                                peerProfiles = peerProfiles,
                                isBusy = isBusy,
                                actionMessage = actionMessage,
                                onClearActionMessage = mainViewModel::clearActionMessage,
                                onOpenChat = { chatId ->
                                    navController.navigate("chat/$chatId") {
                                        launchSingleTop = true
                                    }
                                },
                                onSearchUsers = mainViewModel::searchUsers,
                                onStartChat = { peer ->
                                    mainViewModel.startPrivateChat(account, peer) { chatId ->
                                        navController.navigate("chat/$chatId") {
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                onAudioCallPeer = { peerUid, peerName, peerColor ->
                                    launchCallWithPermissions(
                                        PendingCallLaunch.Outgoing(peerUid, peerName, peerColor, CallType.AUDIO)
                                    )
                                },
                                onVideoCallPeer = { peerUid, peerName, peerColor ->
                                    launchCallWithPermissions(
                                        PendingCallLaunch.Outgoing(peerUid, peerName, peerColor, CallType.VIDEO)
                                    )
                                },
                                onToggleContact = mainViewModel::toggleContact,
                                onFetchPublicProfile = mainViewModel::fetchPublicProfile,
                                onUpdateProfile = { name, bio, color, onDone ->
                                    mainViewModel.updateProfile(account, name, bio, color, onDone)
                                },
                                onUpdateSettings = { updated ->
                                    mainViewModel.updateSettings(account, updated)
                                },
                                onLogout = handleLogout
                            )
                        }

                        composable(
                            route = ROUTE_CHAT,
                            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val chatId = backStackEntry.arguments?.getString("chatId").orEmpty()
                            LaunchedEffect(chatId) { activeChatId = chatId }
                            val chatViewModel: ChatViewModel = viewModel(
                                key = "${uid}_$chatId",
                                factory = viewModelFactory {
                                    initializer {
                                        val app = checkNotNull(this[APPLICATION_KEY])
                                        val databaseId = app.getString(R.string.firestore_database_id)
                                        val db = FirebaseFirestore.getInstance(databaseId)
                                        ChatViewModel(
                                            chatRepository = ChatRepository(db),
                                            userRepository = UserRepository(db),
                                            mediaRepository = MediaRepository(db, app.applicationContext),
                                            chatId = chatId,
                                            currentUserId = uid
                                        )
                                    }
                                }
                            )
                            ChatScreen(
                                viewModel = chatViewModel,
                                currentUserId = uid,
                                sendReadReceipts = settings.showReadReceipts,
                                onStartAudioCall = { peerUid, peerName, peerColor ->
                                    launchCallWithPermissions(
                                        PendingCallLaunch.Outgoing(peerUid, peerName, peerColor, CallType.AUDIO)
                                    )
                                },
                                onStartVideoCall = { peerUid, peerName, peerColor ->
                                    launchCallWithPermissions(
                                        PendingCallLaunch.Outgoing(peerUid, peerName, peerColor, CallType.VIDEO)
                                    )
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // Active 1-to-1 WebRTC audio/video call screen
                    if (activeCallState.session != null) {
                        CallScreen(
                            callState = activeCallState,
                            currentUserId = uid,
                            hasRemoteVideo = hasRemoteVideo,
                            eglBaseContext = callViewModel.getEglBaseContext(),
                            onAttachLocalRenderer = callViewModel::attachLocalRenderer,
                            onAttachRemoteRenderer = callViewModel::attachRemoteRenderer,
                            onToggleMute = callViewModel::toggleMute,
                            onToggleSpeaker = callViewModel::toggleSpeaker,
                            onToggleCamera = callViewModel::toggleCamera,
                            onSwitchCamera = callViewModel::switchCamera,
                            onEndCall = callViewModel::endActiveCall
                        )
                    } else if (incomingCall != null) {
                        IncomingCallOverlay(
                            session = incomingCall!!,
                            onAccept = {
                                launchCallWithPermissions(PendingCallLaunch.Incoming(incomingCall!!))
                            },
                            onReject = {
                                callViewModel.rejectIncomingCall(incomingCall!!)
                            },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}
