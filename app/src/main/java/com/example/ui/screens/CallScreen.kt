package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.ChatUtils
import com.example.model.CallType
import com.example.ui.components.UserAvatar
import com.example.ui.theme.MyChatBlueDark
import com.example.ui.theme.MyChatBluePrimary
import com.example.viewmodel.ActiveCallUiState
import com.example.viewmodel.CallStage
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

@Composable
fun CallScreen(
    callState: ActiveCallUiState,
    currentUserId: String,
    hasRemoteVideo: Boolean,
    eglBaseContext: EglBase.Context?,
    onAttachLocalRenderer: (VideoSink?) -> Unit,
    onAttachRemoteRenderer: (VideoSink?) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndCall: () -> Unit
) {
    val session = callState.session ?: return
    val isVideoCall = session.parsedType == CallType.VIDEO
    val peerName = session.peerName(currentUserId)
    val peerColor = session.peerColorHex(currentUserId)

    // Prevent accidental back-button exit without ending the active call cleanly
    BackHandler { onEndCall() }

    val statusText = when (callState.stage) {
        CallStage.RINGING_OUTGOING -> "Calling..."
        CallStage.RINGING_INCOMING -> "Incoming call..."
        CallStage.CONNECTING -> "Connecting..."
        CallStage.CONNECTED -> ChatUtils.formatDurationSeconds(callState.elapsedSeconds)
        CallStage.IDLE -> "Ending..."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A2540), MyChatBlueDark, Color(0xFF071A33))
                )
            )
    ) {
        if (isVideoCall && eglBaseContext != null) {
            // Remote full-screen video feed
            WebRtcVideoSurface(
                eglContext = eglBaseContext,
                mirror = false,
                isOverlay = false,
                onAttach = onAttachRemoteRenderer,
                modifier = Modifier.fillMaxSize()
            )
            if (!hasRemoteVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A2540).copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    CallerCenterAvatar(
                        peerName = peerName,
                        peerColor = peerColor,
                        isPulsing = callState.stage != CallStage.CONNECTED
                    )
                }
            }

            // Local picture-in-picture self view
            if (callState.isCameraEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 74.dp, end = 16.dp)
                        .size(width = 108.dp, height = 152.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                ) {
                    WebRtcVideoSurface(
                        eglContext = eglBaseContext,
                        mirror = true,
                        isOverlay = true,
                        onAttach = onAttachLocalRenderer,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CallerCenterAvatar(
                    peerName = peerName,
                    peerColor = peerColor,
                    isPulsing = callState.stage != CallStage.CONNECTED
                )
            }
        }

        // Top caller status overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color(0xFF93C5FD),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = callState.iceStatusNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${if (isVideoCall) "Video" else "Audio"} • $statusText",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBFDBFE)
            )
        }

        // Bottom call controls bar
        Surface(
            color = Color(0xFF0B1F38).copy(alpha = 0.9f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        label = if (callState.isMuted) "Unmute" else "Mute",
                        active = callState.isMuted,
                        tag = "call_mute_button",
                        onClick = onToggleMute
                    )

                    if (isVideoCall) {
                        CallControlButton(
                            icon = if (callState.isCameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                            label = if (callState.isCameraEnabled) "Camera" else "Cam Off",
                            active = !callState.isCameraEnabled,
                            tag = "call_camera_button",
                            onClick = onToggleCamera
                        )
                        CallControlButton(
                            icon = Icons.Rounded.Cameraswitch,
                            label = "Flip",
                            active = false,
                            tag = "call_switch_camera_button",
                            onClick = onSwitchCamera
                        )
                    }

                    CallControlButton(
                        icon = if (callState.isSpeakerOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.VolumeDown,
                        label = if (callState.isSpeakerOn) "Speaker" else "Earpiece",
                        active = callState.isSpeakerOn,
                        tag = "call_speaker_button",
                        onClick = onToggleSpeaker
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                FilledIconButton(
                    onClick = onEndCall,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(68.dp)
                        .testTag("end_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = "End call",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CallerCenterAvatar(
    peerName: String,
    peerColor: String,
    isPulsing: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "call_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.16f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    Box(contentAlignment = Alignment.Center) {
        if (isPulsing) {
            Box(
                modifier = Modifier
                    .size(144.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(MyChatBluePrimary.copy(alpha = 0.25f))
            )
        }
        UserAvatar(
            fullName = peerName,
            colorHex = peerColor,
            size = 116.dp,
            showOnlineDot = false
        )
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) Color.White else Color.White.copy(alpha = 0.16f),
                contentColor = if (active) MyChatBlueDark else Color.White
            ),
            modifier = Modifier
                .size(54.dp)
                .testTag(tag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun WebRtcVideoSurface(
    eglContext: EglBase.Context,
    mirror: Boolean,
    isOverlay: Boolean,
    onAttach: (VideoSink?) -> Unit,
    modifier: Modifier = Modifier
) {
    val rendererHolder = remember { arrayOfNulls<SurfaceViewRenderer>(1) }

    DisposableEffect(eglContext) {
        onDispose {
            onAttach(null)
            runCatching { rendererHolder[0]?.release() }
            rendererHolder[0] = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(isOverlay)
                rendererHolder[0] = this
                onAttach(this)
            }
        }
    )
}
