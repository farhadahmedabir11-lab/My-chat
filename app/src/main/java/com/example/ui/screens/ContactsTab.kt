package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ContactEntry
import com.example.model.PublicProfile
import com.example.model.UiState
import com.example.ui.components.UserAvatar
import com.example.ui.theme.MyChatBluePrimary
import com.example.ui.theme.MyChatOnlineGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsTab(
    searchResultsState: UiState<List<PublicProfile>>,
    contactsState: UiState<List<ContactEntry>>,
    onSearchQueryChanged: (String) -> Unit,
    onStartChat: (PublicProfile) -> Unit,
    onToggleContact: (PublicProfile, Boolean) -> Unit,
    onFetchPublicProfile: suspend (String) -> PublicProfile?,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var inspectedProfile by remember { mutableStateOf<PublicProfile?>(null) }
    val scope = rememberCoroutineScope()

    val savedContactIds = remember(contactsState) {
        (contactsState as? UiState.Success)?.data?.map { it.contactUid }?.toSet() ?: emptySet()
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onSearchQueryChanged(it)
            },
            placeholder = { Text("Search by @username or Gmail address") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = "Search users")
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        onSearchQueryChanged("")
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear user search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("user_search_input")
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (query.isNotBlank()) {
                item {
                    Text(
                        text = "Search Results",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                when (searchResultsState) {
                    is UiState.Loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MyChatBluePrimary)
                            }
                        }
                    }
                    is UiState.Error -> {
                        item {
                            Text(
                                text = searchResultsState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    is UiState.Success -> {
                        if (searchResultsState.data.isEmpty()) {
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "No matching discoverable users found for \"$query\".",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        } else {
                            items(searchResultsState.data, key = { "search_${it.uid}" }) { profile ->
                                val isSaved = profile.uid in savedContactIds
                                UserDiscoveryCard(
                                    profile = profile,
                                    isSaved = isSaved,
                                    onOpenProfile = { inspectedProfile = profile },
                                    onStartChat = { onStartChat(profile) },
                                    onToggleContact = { onToggleContact(profile, isSaved) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Saved Contacts",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (contactsState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MyChatBluePrimary)
                        }
                    }
                }
                is UiState.Error -> {
                    item {
                        Text(
                            text = contactsState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is UiState.Success -> {
                    if (contactsState.data.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PersonSearch,
                                        contentDescription = null,
                                        tint = MyChatBluePrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Search by username or Gmail address above to find people and save contacts.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(contactsState.data, key = { "contact_${it.contactUid}" }) { contact ->
                            val fallbackProfile = PublicProfile(
                                uid = contact.contactUid,
                                fullName = contact.fullName,
                                username = contact.username,
                                avatarColorHex = contact.avatarColorHex
                            )
                            UserDiscoveryCard(
                                profile = fallbackProfile,
                                isSaved = true,
                                onOpenProfile = {
                                    scope.launch {
                                        inspectedProfile = onFetchPublicProfile(contact.contactUid) ?: fallbackProfile
                                    }
                                },
                                onStartChat = { onStartChat(fallbackProfile) },
                                onToggleContact = { onToggleContact(fallbackProfile, true) }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    inspectedProfile?.let { profile ->
        val isSaved = profile.uid in savedContactIds
        ModalBottomSheet(
            onDismissRequest = { inspectedProfile = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UserAvatar(
                    fullName = profile.fullName,
                    colorHex = profile.avatarColorHex,
                    size = 76.dp,
                    isOnline = profile.online
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = profile.fullName,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "@${profile.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (profile.online) "Online now" else "Public profile",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (profile.online) MyChatOnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (profile.bio.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = profile.bio,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onToggleContact(profile, isSaved)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("profile_sheet_contact_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isSaved) "Saved" else "Save")
                    }
                    Button(
                        onClick = {
                            inspectedProfile = null
                            onStartChat(profile)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("profile_sheet_message_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MyChatBluePrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Message")
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun UserDiscoveryCard(
    profile: PublicProfile,
    isSaved: Boolean,
    onOpenProfile: () -> Unit,
    onStartChat: () -> Unit,
    onToggleContact: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenProfile)
            .testTag("user_card_${profile.username}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                fullName = profile.fullName,
                colorHex = profile.avatarColorHex,
                size = 48.dp,
                isOnline = profile.online
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.fullName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "@${profile.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onToggleContact,
                modifier = Modifier.testTag("save_contact_${profile.username}")
            ) {
                Icon(
                    imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = if (isSaved) "Remove contact" else "Save contact",
                    tint = if (isSaved) MyChatBluePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onStartChat,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MyChatBluePrimary,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .height(40.dp)
                    .testTag("start_chat_${profile.username}")
            ) {
                Text("Chat", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
