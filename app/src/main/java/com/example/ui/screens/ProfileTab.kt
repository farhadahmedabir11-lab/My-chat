package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ChatUtils
import com.example.data.FreeTierQuotaManager
import com.example.model.UserAccount
import com.example.model.UserSettings
import com.example.ui.components.UserAvatar
import com.example.ui.components.parseHexColorOrDefault
import com.example.ui.theme.AvatarPaletteHexes
import com.example.ui.theme.MyChatBluePrimary

@Composable
fun ProfileTab(
    account: UserAccount,
    settings: UserSettings,
    isBusy: Boolean,
    onUpdateProfile: (fullName: String, bio: String, colorHex: String, onDone: () -> Unit) -> Unit,
    onUpdateSettings: (UserSettings) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val quota by FreeTierQuotaManager.snapshot.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        UserAvatar(
                            fullName = account.fullName,
                            colorHex = account.avatarColorHex,
                            size = 84.dp,
                            isOnline = settings.showOnlineStatus
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = account.fullName,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "@${account.username}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (account.bio.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = account.bio,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { showEditDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("edit_profile_button")
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Edit Profile")
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Account Details",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        AccountInfoRow(
                            icon = Icons.Rounded.AlternateEmail,
                            label = "Username",
                            value = "@${account.username}"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                        AccountInfoRow(
                            icon = Icons.Rounded.Email,
                            label = "Gmail Address",
                            value = account.email
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                        AccountInfoRow(
                            icon = Icons.Rounded.CalendarMonth,
                            label = "Joined My Chat",
                            value = ChatUtils.formatFullDate(account.createdAt?.toDate())
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.PrivacyTip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Privacy Settings",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSwitchRow(
                            title = "Discoverable by username",
                            subtitle = "Allow others to find you in user search",
                            checked = settings.discoverable,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(discoverable = it))
                            }
                        )
                        SettingSwitchRow(
                            title = "Discoverable by Gmail",
                            subtitle = "Allow search using your exact Gmail address",
                            checked = settings.discoverableByEmail,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(discoverableByEmail = it))
                            }
                        )
                        SettingSwitchRow(
                            title = "Online status",
                            subtitle = "Show when you are active in My Chat",
                            checked = settings.showOnlineStatus,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(showOnlineStatus = it))
                            }
                        )
                        SettingSwitchRow(
                            title = "Read receipts",
                            subtitle = "Send blue seen ticks when reading messages",
                            checked = settings.showReadReceipts,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(showReadReceipts = it))
                            }
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Notifications",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        SettingSwitchRow(
                            title = "Message alerts",
                            subtitle = "Notify when new private messages arrive",
                            checked = settings.notificationsEnabled,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(notificationsEnabled = it))
                            }
                        )
                        SettingSwitchRow(
                            title = "Show message preview",
                            subtitle = "Display message text inside notifications",
                            checked = settings.showNotificationPreview,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(showNotificationPreview = it))
                            }
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("free_tier_monitor_card"),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.DataSaverOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Free-Tier Services & Bandwidth",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (!quota.activeWarningMessage.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = quota.activeWarningMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { FreeTierQuotaManager.clearWarning() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = "Dismiss warning",
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        QuotaUsageRow(
                            label = "Firebase Spark Writes (Today)",
                            valueText = "${quota.dailyFirestoreWrites} / ${quota.maxDailyFirestoreWrites}",
                            progress = (quota.dailyFirestoreWrites.toFloat() / quota.maxDailyFirestoreWrites.toFloat()).coerceIn(0f, 1f),
                            isExceeded = quota.firestoreQuotaExceeded
                        )
                        QuotaUsageRow(
                            label = "Media Transfer Budget (Today)",
                            valueText = "${ChatUtils.formatFileSize(quota.dailyMediaBytes)} / 100 MB",
                            progress = (quota.dailyMediaBytes.toFloat() / quota.maxDailyMediaBytes.toFloat()).coerceIn(0f, 1f),
                            isExceeded = quota.dailyMediaBytes >= quota.maxDailyMediaBytes
                        )
                        QuotaUsageRow(
                            label = "Metered Free TURN Relay (Month)",
                            valueText = "${ChatUtils.formatFileSize(quota.monthlyTurnBytes)} / 20 GB",
                            progress = (quota.monthlyTurnBytes.toFloat() / quota.maxMonthlyTurnBytes.toFloat()).coerceIn(0f, 1f),
                            isExceeded = quota.isTurnRelayBlocked
                        )
                        QuotaUsageRow(
                            label = "Metered Realtime Signals (Month)",
                            valueText = "${quota.monthlyRealtimeMessages} / ${quota.maxMonthlyRealtimeMessages}",
                            progress = (quota.monthlyRealtimeMessages.toFloat() / quota.maxMonthlyRealtimeMessages.toFloat()).coerceIn(0f, 1f),
                            isExceeded = quota.isRealtimeSignalingBlocked
                        )

                        Text(
                            text = "Strictly free-tier only: Firebase Spark + free Google STUN + optional Metered Open Relay free TURN (configured safely via BuildConfig secrets). Video calls are capped at 480x360 @ 18fps (450 kbps) to save mobile data, and any exceeded quota pauses cleanly without billing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = onLogout,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("logout_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Log Out", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showEditDialog) {
        EditProfileDialog(
            initialName = account.fullName,
            initialBio = account.bio,
            initialColor = account.avatarColorHex,
            isBusy = isBusy,
            onDismiss = { showEditDialog = false },
            onSave = { name, bio, color ->
                onUpdateProfile(name, bio, color) {
                    showEditDialog = false
                }
            }
        )
    }
}

@Composable
private fun AccountInfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun QuotaUsageRow(
    label: String,
    valueText: String,
    progress: Float,
    isExceeded: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = if (isExceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            color = if (isExceeded) MaterialTheme.colorScheme.error else MyChatBluePrimary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MyChatBluePrimary
            )
        )
    }
}

@Composable
private fun EditProfileDialog(
    initialName: String,
    initialBio: String,
    initialColor: String,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var bio by remember { mutableStateOf(initialBio) }
    var colorHex by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Full Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_full_name_input")
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(160) },
                    label = { Text("Bio") },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_bio_input")
                )
                Text(
                    text = "Avatar Color",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarPaletteHexes.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(parseHexColorOrDefault(hex))
                                .then(
                                    if (hex == colorHex) {
                                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else Modifier
                                )
                                .clickable { colorHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, bio, colorHex) },
                enabled = !isBusy && ChatUtils.isValidFullName(name),
                modifier = Modifier.testTag("save_profile_button")
            ) {
                Text(if (isBusy) "Saving..." else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
