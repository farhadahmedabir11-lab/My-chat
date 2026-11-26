package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.model.CallHistoryEntry
import com.example.model.ContactEntry
import com.example.model.PrivateChat
import com.example.model.PublicProfile
import com.example.model.UiState
import com.example.model.UserAccount
import com.example.model.UserSettings
import com.example.ui.components.MyChatBrandLogo
import com.example.ui.components.UserAvatar
import com.example.ui.theme.MyChatBluePrimary
import com.example.ui.theme.MyChatOnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    currentUserId: String,
    account: UserAccount,
    settings: UserSettings,
    chatsState: UiState<List<PrivateChat>>,
    callHistoryState: UiState<List<CallHistoryEntry>>,
    contactsState: UiState<List<ContactEntry>>,
    searchResultsState: UiState<List<PublicProfile>>,
    peerProfiles: Map<String, PublicProfile>,
    isBusy: Boolean,
    actionMessage: String?,
    onClearActionMessage: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSearchUsers: (String) -> Unit,
    onStartChat: (PublicProfile) -> Unit,
    onAudioCallPeer: (peerUid: String, peerName: String, peerColorHex: String) -> Unit,
    onVideoCallPeer: (peerUid: String, peerName: String, peerColorHex: String) -> Unit,
    onToggleContact: (PublicProfile, Boolean) -> Unit,
    onFetchPublicProfile: suspend (String) -> PublicProfile?,
    onUpdateProfile: (String, String, String, () -> Unit) -> Unit,
    onUpdateSettings: (UserSettings) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(actionMessage) {
        if (!actionMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(actionMessage)
            onClearActionMessage()
        }
    }

    val totalUnread = remember(chatsState, currentUserId) {
        (chatsState as? UiState.Success)?.data?.sumOf { it.unreadFor(currentUserId) } ?: 0
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = MyChatBluePrimary,
                    shadowElevation = 3.dp
                ) {
                    TopAppBar(
                        modifier = Modifier.statusBarsPadding(),
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MyChatBluePrimary,
                            titleContentColor = Color.White
                        ),
                        title = {
                            MyChatBrandLogo(lightOnDark = true)
                        },
                        actions = {
                            Surface(
                                color = Color.White.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (settings.showOnlineStatus) MyChatOnlineGreen else Color.LightGray)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (settings.showOnlineStatus) "Online" else "Hidden",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                            UserAvatar(
                                fullName = account.fullName,
                                colorHex = account.avatarColorHex,
                                size = 36.dp,
                                isOnline = false,
                                showOnlineDot = false,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    )
                }
            },
            floatingActionButton = {
                if (selectedTab == 0) {
                    FloatingActionButton(
                        onClick = { selectedTab = 2 },
                        containerColor = MyChatBluePrimary,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.testTag("new_chat_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddComment,
                            contentDescription = "New private chat"
                        )
                    }
                }
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            modifier = Modifier.testTag("nav_chats"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MyChatBluePrimary,
                                selectedTextColor = MyChatBluePrimary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (totalUnread > 0) {
                                            Badge(containerColor = MyChatBluePrimary) {
                                                Text(if (totalUnread > 99) "99+" else totalUnread.toString())
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (selectedTab == 0) Icons.AutoMirrored.Filled.Chat else Icons.AutoMirrored.Outlined.Chat,
                                        contentDescription = "Chats"
                                    )
                                }
                            },
                            label = { Text("Chats") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            modifier = Modifier.testTag("nav_calls"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MyChatBluePrimary,
                                selectedTextColor = MyChatBluePrimary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 1) Icons.Filled.Call else Icons.Outlined.Call,
                                    contentDescription = "Calls"
                                )
                            },
                            label = { Text("Calls") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            modifier = Modifier.testTag("nav_contacts"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MyChatBluePrimary,
                                selectedTextColor = MyChatBluePrimary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 2) Icons.Filled.Contacts else Icons.Outlined.Contacts,
                                    contentDescription = "Contacts"
                                )
                            },
                            label = { Text("Contacts") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            modifier = Modifier.testTag("nav_profile"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MyChatBluePrimary,
                                selectedTextColor = MyChatBluePrimary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 3) Icons.Filled.Person else Icons.Outlined.Person,
                                    contentDescription = "Profile"
                                )
                            },
                            label = { Text("Profile") }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NavigationRailItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 0) Icons.AutoMirrored.Filled.Chat else Icons.AutoMirrored.Outlined.Chat,
                                    contentDescription = "Chats"
                                )
                            },
                            label = { Text("Chats") }
                        )
                        NavigationRailItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 1) Icons.Filled.Call else Icons.Outlined.Call,
                                    contentDescription = "Calls"
                                )
                            },
                            label = { Text("Calls") }
                        )
                        NavigationRailItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 2) Icons.Filled.Contacts else Icons.Outlined.Contacts,
                                    contentDescription = "Contacts"
                                )
                            },
                            label = { Text("Contacts") }
                        )
                        NavigationRailItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 3) Icons.Filled.Person else Icons.Outlined.Person,
                                    contentDescription = "Profile"
                                )
                            },
                            label = { Text("Profile") }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> ChatsTab(
                        currentUserId = currentUserId,
                        chatsState = chatsState,
                        peerProfiles = peerProfiles,
                        onChatClick = onOpenChat,
                        onFindPeopleClick = { selectedTab = 2 }
                    )
                    1 -> CallsTab(
                        currentUserId = currentUserId,
                        historyState = callHistoryState,
                        onAudioCallPeer = onAudioCallPeer,
                        onVideoCallPeer = onVideoCallPeer
                    )
                    2 -> ContactsTab(
                        searchResultsState = searchResultsState,
                        contactsState = contactsState,
                        onSearchQueryChanged = onSearchUsers,
                        onStartChat = onStartChat,
                        onToggleContact = onToggleContact,
                        onFetchPublicProfile = onFetchPublicProfile
                    )
                    3 -> ProfileTab(
                        account = account,
                        settings = settings,
                        isBusy = isBusy,
                        onUpdateProfile = onUpdateProfile,
                        onUpdateSettings = onUpdateSettings,
                        onLogout = onLogout
                    )
                }
            }
        }
    }
}
