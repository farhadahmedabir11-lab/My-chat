package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ChatUtils
import com.example.model.CallHistoryEntry
import com.example.model.CallOutcome
import com.example.model.CallType
import com.example.model.UiState
import com.example.ui.components.UserAvatar
import com.example.ui.theme.MyChatBluePrimary

@Composable
fun CallsTab(
    currentUserId: String,
    historyState: UiState<List<CallHistoryEntry>>,
    onAudioCallPeer: (peerUid: String, peerName: String, peerColorHex: String) -> Unit,
    onVideoCallPeer: (peerUid: String, peerName: String, peerColorHex: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        when (historyState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MyChatBluePrimary)
                }
            }
            is UiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = historyState.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is UiState.Success -> {
                val entries = historyState.data
                if (entries.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            tint = MyChatBluePrimary.copy(alpha = 0.65f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No calls yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Open any private chat and tap the phone or video icon to start a free WebRTC call.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 600.dp),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            val isOutgoing = entry.isOutgoing(currentUserId)
                            val peerUid = entry.peerId(currentUserId)
                            val peerName = entry.peerName(currentUserId)
                            val peerColor = entry.peerColorHex(currentUserId)
                            val isMissedOrRejected = entry.parsedOutcome == CallOutcome.MISSED ||
                                entry.parsedOutcome == CallOutcome.REJECTED

                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("call_history_item_${entry.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UserAvatar(
                                        fullName = peerName,
                                        colorHex = peerColor,
                                        size = 46.dp,
                                        showOnlineDot = false
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peerName,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = when {
                                                    isMissedOrRejected && !isOutgoing -> Icons.AutoMirrored.Rounded.CallMissed
                                                    isOutgoing -> Icons.AutoMirrored.Rounded.CallMade
                                                    else -> Icons.AutoMirrored.Rounded.CallReceived
                                                },
                                                contentDescription = null,
                                                tint = if (isMissedOrRejected) Color(0xFFDC2626) else Color(0xFF16A34A),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val subtitle = buildString {
                                                append(if (entry.parsedCallType == CallType.VIDEO) "Video" else "Audio")
                                                if (entry.durationSeconds > 0L) {
                                                    append(" • ${ChatUtils.formatDurationSeconds(entry.durationSeconds)}")
                                                } else {
                                                    append(" • ${entry.parsedOutcome.name.lowercase().replaceFirstChar { it.uppercase() }}")
                                                }
                                                val dateStr = ChatUtils.formatCallDateTime(entry.createdAt?.toDate())
                                                if (dateStr.isNotBlank()) {
                                                    append(" • $dateStr")
                                                }
                                            }
                                            Text(
                                                text = subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { onAudioCallPeer(peerUid, peerName, peerColor) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Call,
                                            contentDescription = "Audio call $peerName",
                                            tint = MyChatBluePrimary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onVideoCallPeer(peerUid, peerName, peerColor) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Videocam,
                                            contentDescription = "Video call $peerName",
                                            tint = MyChatBluePrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
