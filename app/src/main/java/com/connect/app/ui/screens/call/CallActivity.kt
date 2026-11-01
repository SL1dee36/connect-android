package com.connect.app.ui.screens.call

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.connect.app.ConnectApplication
import com.connect.app.notifications.NotificationHelper
import com.connect.app.ui.components.UserAvatar
import com.connect.app.ui.theme.ConnectTheme
import com.connect.app.webrtc.CallState
import com.connect.app.webrtc.WebRtcCallManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_USER = "extra_target_user"
        const val EXTRA_TARGET_NAME = "extra_target_name"
        const val EXTRA_ROOM = "extra_room"
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
    }

    private var isInPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during call and show over lock screen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val app = ConnectApplication.instance
        val callManager = app.callManager

        val isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)
        val shouldAnswer = intent.getBooleanExtra(NotificationHelper.EXTRA_ACTION_ANSWER, false)

        if (isOutgoing) {
            val targetUser = intent.getStringExtra(EXTRA_TARGET_USER) ?: ""
            val targetName = intent.getStringExtra(EXTRA_TARGET_NAME) ?: targetUser
            val room = intent.getStringExtra(EXTRA_ROOM) ?: ""
            val callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "audio"
            callManager.startCall(targetUser, targetName, callType, room)
        } else if (shouldAnswer) {
            callManager.answerCall()
        }

        setContent {
            ConnectTheme {
                CallScreen(
                    callManager = callManager,
                    isInPip = isInPipMode,
                    onEnterPip = { enterPip() },
                    onFinish = { finish() }
                )
            }
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }
}

@Composable
fun CallScreen(
    callManager: WebRtcCallManager,
    isInPip: Boolean,
    onEnterPip: () -> Unit,
    onFinish: () -> Unit
) {
    val callState by callManager.callState.collectAsState()
    val peerName by callManager.peerDisplayName.collectAsState()
    val callType by callManager.callType.collectAsState()
    val duration by callManager.callDuration.collectAsState()
    val isMicMuted by callManager.isMicMuted.collectAsState()
    val isVideoEnabled by callManager.isVideoEnabled.collectAsState()
    val isSpeakerOn by callManager.isSpeakerOn.collectAsState()
    val localTrack by callManager.localTrackFlow.collectAsState()
    val remoteTrack by callManager.remoteTrackFlow.collectAsState()

    LaunchedEffect(callState) {
        if (callState == CallState.ENDED) {
            onFinish()
        }
    }

    val isVideo = callType == "video"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // 1. Remote Video (if video call and track available)
        if (isVideo && remoteTrack != null) {
            WebRtcVideoView(
                videoTrack = remoteTrack,
                callManager = callManager,
                isMirror = false,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Audio Call or No Video placeholder
        if (!isVideo || remoteTrack == null) {
            AudioCallVisualizer(
                displayName = peerName,
                callState = callState,
                durationSeconds = duration,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Local Camera Floating Preview (for video call)
        if (isVideo && localTrack != null && isVideoEnabled && !isInPip) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            ) {
                WebRtcVideoView(
                    videoTrack = localTrack,
                    callManager = callManager,
                    isMirror = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 4. Overlays & Controls (Hidden in PiP mode)
        if (!isInPip) {
            // Top Bar: Call status and PiP button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = when (callState) {
                            CallState.OUTGOING -> "Вызов..."
                            CallState.INCOMING -> "Входящий звонок..."
                            CallState.CONNECTED -> formatDuration(duration)
                            CallState.ENDED -> "Завершение..."
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    IconButton(
                        onClick = onEnterPip,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PictureInPicture,
                            contentDescription = "PiP",
                            tint = Color.White
                        )
                    }
                }
            }

            // Bottom Controls Bar
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                if (callState == CallState.INCOMING) {
                    // Incoming call: Answer and Reject buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallActionButton(
                            icon = Icons.Rounded.CallEnd,
                            label = "Отклонить",
                            backgroundColor = Color(0xFFEF4444),
                            onClick = { callManager.rejectIncomingCall() }
                        )

                        CallActionButton(
                            icon = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                            label = "Ответить",
                            backgroundColor = Color(0xFF10B981),
                            onClick = { callManager.answerCall() }
                        )
                    }
                } else {
                    // Active or Outgoing call controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic Mute
                        CallActionButton(
                            icon = if (isMicMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (isMicMuted) "Вкл. звук" else "Выкл. звук",
                            backgroundColor = if (isMicMuted) Color.White else Color.White.copy(alpha = 0.2f),
                            iconTint = if (isMicMuted) Color.Black else Color.White,
                            onClick = { callManager.toggleMute() }
                        )

                        // Speakerphone
                        CallActionButton(
                            icon = if (isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeDown,
                            label = if (isSpeakerOn) "Динамик" else "Телефон",
                            backgroundColor = if (isSpeakerOn) Color.White else Color.White.copy(alpha = 0.2f),
                            iconTint = if (isSpeakerOn) Color.Black else Color.White,
                            onClick = { callManager.toggleSpeaker() }
                        )

                        // Video toggle (if video call)
                        if (isVideo) {
                            CallActionButton(
                                icon = if (isVideoEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                label = if (isVideoEnabled) "Камера" else "Выкл.",
                                backgroundColor = if (!isVideoEnabled) Color.White else Color.White.copy(alpha = 0.2f),
                                iconTint = if (!isVideoEnabled) Color.Black else Color.White,
                                onClick = { callManager.toggleVideo() }
                            )

                            CallActionButton(
                                icon = Icons.Rounded.FlipCameraIos,
                                label = "Повернуть",
                                backgroundColor = Color.White.copy(alpha = 0.2f),
                                onClick = { callManager.switchCamera() }
                            )
                        }

                        // Hang Up
                        CallActionButton(
                            icon = Icons.Rounded.CallEnd,
                            label = "Завершить",
                            backgroundColor = Color(0xFFEF4444),
                            onClick = { callManager.endCall() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioCallVisualizer(
    displayName: String,
    callState: CallState,
    durationSeconds: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(160.dp)
                .scale(if (callState == CallState.OUTGOING || callState == CallState.INCOMING) pulseScale else 1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF3B82F6).copy(alpha = 0.2f))
            )
            UserAvatar(
                name = displayName,
                avatarUrl = null,
                size = 120.dp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when (callState) {
                CallState.OUTGOING -> "Вызов..."
                CallState.INCOMING -> "Входящий вызов..."
                CallState.CONNECTED -> formatDuration(durationSeconds)
                CallState.ENDED -> "Звонок завершён"
                else -> ""
            },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.75f)
        )
    }
}

@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack?,
    callManager: WebRtcCallManager,
    isMirror: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(callManager.rootEglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(isMirror)
                videoTrack?.addSink(this)
            }
        },
        update = { renderer ->
            renderer.setMirror(isMirror)
            videoTrack?.addSink(renderer)
        },
        onRelease = { renderer ->
            videoTrack?.removeSink(renderer)
            renderer.release()
        },
        modifier = modifier
    )
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    iconTint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(backgroundColor)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${if (m < 10) "0$m" else "$m"}:${if (s < 10) "0$s" else "$s"}"
}
