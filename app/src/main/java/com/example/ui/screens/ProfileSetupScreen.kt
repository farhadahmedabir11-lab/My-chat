package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.ChatUtils
import com.example.ui.components.MyChatBrandLogo
import com.example.ui.components.UserAvatar
import com.example.ui.components.parseHexColorOrDefault
import com.example.ui.theme.AvatarPaletteHexes
import com.example.ui.theme.MyChatBluePrimary
import com.example.ui.theme.MyChatOnlineGreen

@Composable
fun ProfileSetupScreen(
    initialName: String,
    verifiedEmail: String,
    usernameAvailable: Boolean?,
    isBusy: Boolean,
    errorMessage: String?,
    onUsernameChanged: (String) -> Unit,
    onSubmit: (fullName: String, username: String, bio: String, colorHex: String) -> Unit,
    onLogout: () -> Unit
) {
    var fullName by remember(initialName) { mutableStateOf(initialName) }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("Hey there! I am using My Chat.") }
    var selectedColor by remember { mutableStateOf(AvatarPaletteHexes.first()) }
    var localError by remember { mutableStateOf<String?>(null) }

    val normalizedUsername = ChatUtils.normalizeUsername(username)
    val isUsernameFormatValid = ChatUtils.isValidUsername(normalizedUsername)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MyChatBrandLogo()
            TextButton(
                onClick = onLogout,
                modifier = Modifier.testTag("setup_logout_button")
            ) {
                Text("Switch Account")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UserAvatar(
                    fullName = fullName.ifBlank { "My Chat" },
                    colorHex = selectedColor,
                    size = 76.dp,
                    isOnline = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Complete your profile",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pick a unique username so friends can find and message you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Verified Gmail badge
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Verified Gmail",
                            tint = MyChatBluePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Verified Gmail Account",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = verifiedEmail.ifBlank { "Signed in with Google" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = fullName,
                    onValueChange = {
                        fullName = it
                        localError = null
                    },
                    label = { Text("Full Name") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Person, contentDescription = null)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("full_name_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { input ->
                        val cleaned = input.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(24)
                        username = cleaned
                        localError = null
                        onUsernameChanged(cleaned)
                    },
                    label = { Text("Unique Username") },
                    placeholder = { Text("e.g. farhad_11") },
                    leadingIcon = {
                        Icon(Icons.Rounded.AlternateEmail, contentDescription = null)
                    },
                    trailingIcon = {
                        if (isUsernameFormatValid) {
                            when (usernameAvailable) {
                                true -> Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "Username available",
                                    tint = MyChatOnlineGreen
                                )
                                false -> Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = "Username taken",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                null -> {}
                            }
                        }
                    },
                    supportingText = {
                        val helper = when {
                            username.isEmpty() -> "3–24 lowercase letters, numbers, or underscores"
                            !isUsernameFormatValid -> "Use at least 3 characters (a-z, 0-9, _)"
                            usernameAvailable == true -> "@$normalizedUsername is available!"
                            usernameAvailable == false -> "@$normalizedUsername is already taken"
                            else -> "Checking availability..."
                        }
                        Text(
                            text = helper,
                            color = when {
                                username.isNotEmpty() && (!isUsernameFormatValid || usernameAvailable == false) ->
                                    MaterialTheme.colorScheme.error
                                usernameAvailable == true -> MyChatOnlineGreen
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("username_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(160) },
                    label = { Text("Bio (optional)") },
                    maxLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bio_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Avatar Accent Color",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AvatarPaletteHexes.forEach { hex ->
                        val selected = hex == selectedColor
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(parseHexColorOrDefault(hex))
                                .then(
                                    if (selected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else Modifier
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }

                val displayError = localError ?: errorMessage
                if (!displayError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = displayError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = {
                        when {
                            !ChatUtils.isValidFullName(fullName) ->
                                localError = "Please enter your full name (2–60 characters)."
                            !isUsernameFormatValid ->
                                localError = "Username must be 3–24 characters (letters, numbers, underscores)."
                            usernameAvailable == false ->
                                localError = "That username is already taken. Choose another one."
                            else ->
                                onSubmit(fullName, normalizedUsername, bio, selectedColor)
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("complete_registration_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MyChatBluePrimary,
                        contentColor = Color.White
                    )
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text("Create My Chat Account", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
