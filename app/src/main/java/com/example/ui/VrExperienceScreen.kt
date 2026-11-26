package com.example.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Vrpano
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.VrSettings
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldOk
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.ThermalAmber
import com.example.ui.theme.VoidNavy
import com.example.vr.BatteryTelemetry
import com.example.vr.CameraPassthroughManager
import com.example.vr.CameraTelemetry
import com.example.vr.GazeController
import com.example.vr.StereoVrRenderer
import com.example.vr.VideoPlayerState
import com.example.vr.VrVideoController
import com.example.vr.ai.VisionAiState
import com.example.vr.hand.HandTrackingManager
import com.example.vr.hand.HandTrackingState
import com.example.vr.pose.PoseProvider
import com.example.vr.pose.RoomBounds
import com.example.vr.pose.Vec3
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VrExperienceScreen(
    settings: VrSettings,
    battery: BatteryTelemetry,
    cameraTelemetry: CameraTelemetry,
    headPosition: Vec3,
    activeInputSource: String,
    videoState: VideoPlayerState,
    handState: HandTrackingState,
    aiState: VisionAiState,
    measuredFps: Int,
    hasCameraPermission: Boolean,
    placingWindowId: String?,
    poseProvider: PoseProvider,
    roomBounds: RoomBounds,
    gazeController: GazeController,
    cameraManager: CameraPassthroughManager,
    videoController: VrVideoController,
    handTrackingManager: HandTrackingManager,
    onStepLocomotion: (Float) -> Unit,
    onGazeCommand: (String) -> Unit,
    onPlaceWindowAtGaze: (String, Vec3, Float, Float, Float) -> Unit,
    onWindowTransformed: (String, Float, Float, Float, Float, Float, Float, Boolean, Boolean) -> Unit,
    onTeleportFloor: (Float, Float) -> Unit,
    onFpsMeasured: (Int) -> Unit,
    onTelemetrySample: (Vec3, String) -> Unit,
    onToggleVirtualRoom: () -> Unit,
    onNewNoteWindow: () -> Unit,
    onRecenter: () -> Unit,
    onTriggerWhatAmISeeing: () -> Unit,
    onRotateCamera90: () -> Unit,
    onExitVr: () -> Unit
) {
    BackHandler {
        onExitVr()
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val renderer = remember {
        StereoVrRenderer(
            poseProvider = poseProvider,
            roomBounds = roomBounds,
            gazeController = gazeController,
            onCameraSurfaceTextureReady = { surfaceTexture ->
                if (hasCameraPermission) {
                    cameraManager.bindToSurfaceTexture(
                        lifecycleOwner = lifecycleOwner,
                        surfaceTexture = surfaceTexture,
                        handTrackingManager = handTrackingManager
                    )
                }
            },
            onVideoSurfaceTextureReady = { surfaceTexture ->
                videoController.attachSurfaceTexture(surfaceTexture, settings.videoUrl)
            },
            onStepLocomotion = onStepLocomotion,
            onGazeButtonCommand = onGazeCommand,
            onPlaceWindowAtGaze = onPlaceWindowAtGaze,
            onWindowTransformed = onWindowTransformed,
            onTeleportFloor = onTeleportFloor,
            onFpsMeasured = onFpsMeasured,
            onTelemetrySample = onTelemetrySample
        )
    }

    LaunchedEffect(
        settings,
        battery,
        videoState,
        handState,
        aiState,
        hasCameraPermission,
        cameraTelemetry,
        placingWindowId
    ) {
        renderer.currentSettings = settings
        renderer.currentBattery = battery
        renderer.currentVideoState = videoState
        renderer.currentHandState = handState
        renderer.currentAiState = aiState
        renderer.isCameraActive = hasCameraPermission && cameraTelemetry.isActive
        renderer.cameraWidth = cameraTelemetry.width
        renderer.cameraHeight = cameraTelemetry.height
        renderer.displayRotation = cameraManager.getCurrentDisplayRotation()
        renderer.placingWindowId = placingWindowId
    }

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            keepScreenOn = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        glSurfaceView.onResume()
        poseProvider.recenter()
        onDispose {
            glSurfaceView.onPause()
            cameraManager.release()
            videoController.pause()
            renderer.releaseGlTextures()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("vr_gl_container")
    ) {
        val isCompactHud = maxWidth < 600.dp

        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        // Center Stereo Divider Line for Cardboard lens separation
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.25f))
                .align(Alignment.Center)
        )

        // Responsive Top Control Bar + Debug Telemetry Overlay (never clips on narrow screens)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 6.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isCompactHud) 6.dp else 8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(VoidNavy.copy(alpha = 0.80f))
                        .border(
                            1.dp,
                            if (settings.isVirtualRoomMode) EmeraldOk else ElectricCyan,
                            CircleShape
                        )
                        .clickable { onToggleVirtualRoom() }
                        .padding(horizontal = if (isCompactHud) 8.dp else 10.dp, vertical = 6.dp)
                        .testTag("vr_room_toggle_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (settings.isVirtualRoomMode) Icons.Default.MeetingRoom else Icons.Default.Vrpano,
                        contentDescription = "Toggle Virtual Room or Passthrough",
                        tint = if (settings.isVirtualRoomMode) EmeraldOk else ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            isCompactHud && settings.isVirtualRoomMode -> "Room"
                            isCompactHud -> "AR Cam"
                            settings.isVirtualRoomMode -> "Virtual Room"
                            else -> "Passthrough"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(VoidNavy.copy(alpha = 0.80f))
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable { onNewNoteWindow() }
                        .padding(horizontal = if (isCompactHud) 8.dp else 10.dp, vertical = 6.dp)
                        .testTag("vr_new_window_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AddBox,
                        contentDescription = "New Spatial Window",
                        tint = ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "+Win",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(VoidNavy.copy(alpha = 0.80f))
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable { onRecenter() }
                        .padding(horizontal = if (isCompactHud) 8.dp else 10.dp, vertical = 6.dp)
                        .testTag("vr_recenter_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = "Recenter Pose",
                        tint = ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Recenter",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(VoidNavy.copy(alpha = 0.80f))
                        .border(
                            1.dp,
                            when {
                                aiState.isAiCameraActive -> Color(0xFFFF6D00)
                                aiState.isListening -> EmeraldOk
                                else -> GlassBorder
                            },
                            CircleShape
                        )
                        .clickable { onTriggerWhatAmISeeing() }
                        .padding(horizontal = if (isCompactHud) 8.dp else 10.dp, vertical = 6.dp)
                        .testTag("vr_ai_vision_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (aiState.isListening) Icons.Default.Mic else Icons.Default.Visibility,
                        contentDescription = "What am I seeing?",
                        tint = when {
                            aiState.isAiCameraActive -> Color(0xFFFF6D00)
                            aiState.isListening -> EmeraldOk
                            else -> ElectricCyan
                        },
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            aiState.isAiCameraActive -> "AI CAM"
                            aiState.isListening -> "Listening..."
                            else -> "AI"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            aiState.isAiCameraActive -> Color(0xFFFF6D00)
                            aiState.isListening -> EmeraldOk
                            else -> Color.White
                        }
                    )
                }

                if (!settings.isVirtualRoomMode) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(VoidNavy.copy(alpha = 0.80f))
                            .border(1.dp, GlassBorder, CircleShape)
                            .clickable {
                                renderer.displayRotation = cameraManager.getCurrentDisplayRotation()
                                onRotateCamera90()
                            }
                            .padding(horizontal = if (isCompactHud) 8.dp else 10.dp, vertical = 6.dp)
                            .testTag("vr_rotate_cam_pill"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rot+${settings.cameraRotationOffsetDeg}°",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(VoidNavy.copy(alpha = 0.80f))
                        .border(1.dp, AlertCrimson.copy(alpha = 0.75f), CircleShape)
                        .clickable { onExitVr() }
                        .padding(horizontal = if (isCompactHud) 8.dp else 10.dp, vertical = 6.dp)
                        .testTag("vr_exit_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Exit VR",
                        tint = AlertCrimson,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Exit",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }

            if (settings.showDebugOverlay) {
                Spacer(modifier = Modifier.height(6.dp))
                val hudText = String.format(
                    Locale.US,
                    "FPS: %d | Pos: (%.2f, %.2f, %.2f)m | Input: %s | Hand: %dFPS (%s) | %.1f°C",
                    measuredFps,
                    headPosition.x,
                    headPosition.y,
                    headPosition.z,
                    activeInputSource,
                    handState.handFps,
                    handState.activeDelegate,
                    battery.temperatureCelsius
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(VoidNavy.copy(alpha = 0.84f))
                        .border(
                            1.dp,
                            if (battery.hasWarning) ThermalAmber else EmeraldOk.copy(alpha = 0.65f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("vr_debug_hud")
                ) {
                    Text(
                        text = hudText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (battery.hasWarning) ThermalAmber else ElectricCyan,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (battery.hasWarning) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xDD3E1B00))
                    .border(1.5.dp, ThermalAmber, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("vr_thermal_warning_banner"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Thermal or Battery Warning",
                    tint = ThermalAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (battery.isHighTempWarning) {
                            "THERMAL WARNING: ${battery.temperatureCelsius}°C (> 42°C)"
                        } else {
                            "LOW BATTERY WARNING: ${battery.levelPercent}% (< 15%)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = ThermalAmber
                    )
                }
            }
        }
    }
}
