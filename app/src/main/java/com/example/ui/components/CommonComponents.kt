package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MessageStatus
import com.example.ui.theme.MyChatBlueDark
import com.example.ui.theme.MyChatBluePrimary
import com.example.ui.theme.MyChatOnlineGreen
import com.example.ui.theme.MyChatSeenCyan

fun parseHexColorOrDefault(hex: String?, fallback: Color = MyChatBluePrimary): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun MyChatBrandLogo(
    modifier: Modifier = Modifier,
    lightOnDark: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (lightOnDark) {
                        Brush.linearGradient(listOf(Color.White, Color(0xFFDBEAFE)))
                    } else {
                        Brush.linearGradient(listOf(MyChatBluePrimary, MyChatBlueDark))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubble,
                contentDescription = "My Chat logo",
                tint = if (lightOnDark) MyChatBluePrimary else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "My Chat",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp
            ),
            color = if (lightOnDark) Color.White else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun UserAvatar(
    fullName: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isOnline: Boolean = false,
    showOnlineDot: Boolean = true
) {
    val baseColor = parseHexColorOrDefault(colorHex)
    val initials = fullName
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "M" }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            baseColor,
                            baseColor.copy(alpha = 0.78f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (showOnlineDot && isOnline) {
            val dotSize = (size.value * 0.28f).dp.coerceIn(10.dp, 16.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(MyChatOnlineGreen)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
fun MessageStatusTicks(
    status: MessageStatus,
    onBlueBubble: Boolean,
    modifier: Modifier = Modifier
) {
    val tint = when (status) {
        MessageStatus.SEEN -> if (onBlueBubble) MyChatSeenCyan else MyChatBluePrimary
        MessageStatus.DELIVERED -> if (onBlueBubble) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
        MessageStatus.SENT -> if (onBlueBubble) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    }
    val icon = when (status) {
        MessageStatus.SENT -> Icons.Rounded.Done
        MessageStatus.DELIVERED, MessageStatus.SEEN -> Icons.Rounded.DoneAll
    }
    val description = when (status) {
        MessageStatus.SENT -> "Sent"
        MessageStatus.DELIVERED -> "Delivered"
        MessageStatus.SEEN -> "Seen"
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = modifier.size(16.dp)
    )
}
