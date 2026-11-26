package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Vrpano
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.VrSettings
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.DeepSpace
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldOk
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.SpatialIndigo
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThermalAmber
import com.example.ui.theme.VoidNavy
import com.example.vr.BatteryTelemetry
import com.example.vr.CameraTelemetry
import com.example.vr.HeadEulerAngles
import com.example.vr.hand.HandTrackingState
import com.example.vr.input.GamepadInputState
import com.example.vr.pose.Vec3
import java.util.Locale

@Composable
fun HomeStartScreen(
    settings: VrSettings,
    battery: BatteryTelemetry,
    cameraTelemetry: CameraTelemetry,
    headEuler: HeadEulerAngles,
    headPosition: Vec3,
    activeInputSource: String,
    gamepadState: GamepadInputState,
    handState: HandTrackingState,
    measuredFps: Int,
    hasCameraPermission: Boolean,
    hasAudioPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onEnterVr: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenAbout: () -> Unit,
    onVideoUrlChange: (String) -> Unit,
    onToggleVirtualRoom: (Boolean) -> Unit,
    onToggleFloorTeleport: (Boolean) -> Unit,
    onToggleHandTracking: (Boolean) -> Unit,
    onToggleDebugOverlay: (Boolean) -> Unit
) {
    var urlDraft by remember(settings.videoUrl) { mutableStateOf(settings.videoUrl) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = VoidNavy
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter
        ) {
            val isCompactWidth = maxWidth < 680.dp
            val isVeryNarrow = maxWidth < 500.dp
            val isCompactHeight = maxHeight < 460.dp
            val hPad = if (isVeryNarrow) 12.dp else if (isCompactWidth) 14.dp else 20.dp
            val vPad = if (isCompactHeight) 8.dp else 12.dp
            val gap = if (isVeryNarrow || isCompactHeight) 10.dp else 14.dp

            if (isCompactWidth) {
                // Single-column vertically scrollable Dashboard for small phones, portrait, or split-screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = hPad, vertical = vPad),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    DashboardHeroBanner(isCompact = true)
                    DashboardActionButtons(
                        isVeryNarrow = isVeryNarrow,
                        onEnterVr = onEnterVr,
                        onOpenCalibration = onOpenCalibration,
                        onOpenAbout = onOpenAbout
                    )
                    ModeAndMovementCard(
                        settings = settings,
                        isCompact = isVeryNarrow,
                        onToggleVirtualRoom = onToggleVirtualRoom,
                        onToggleFloorTeleport = onToggleFloorTeleport
                    )
                    VideoUrlCard(
                        urlDraft = urlDraft,
                        isCompact = isVeryNarrow,
                        onUrlDraftChange = { urlDraft = it },
                        onVideoUrlChange = onVideoUrlChange
                    )
                    HandAndDebugCard(
                        settings = settings,
                        battery = battery,
                        cameraTelemetry = cameraTelemetry,
                        headEuler = headEuler,
                        headPosition = headPosition,
                        activeInputSource = activeInputSource,
                        gamepadState = gamepadState,
                        handState = handState,
                        measuredFps = measuredFps,
                        hasCameraPermission = hasCameraPermission,
                        isVeryNarrow = isVeryNarrow,
                        onToggleHandTracking = onToggleHandTracking,
                        onToggleDebugOverlay = onToggleDebugOverlay
                    )
                    CoolingStatusCard(battery = battery)
                    if (!hasCameraPermission) {
                        PermissionBannerCard(
                            icon = Icons.Default.CameraAlt,
                            accent = AlertCrimson,
                            containerColor = Color(0xFF2A121A),
                            title = stringResource(R.string.camera_permission_title),
                            rationale = stringResource(R.string.camera_permission_rationale),
                            buttonText = stringResource(R.string.grant_camera),
                            cardTag = "camera_permission_card",
                            buttonTag = "grant_camera_button",
                            isCompact = isVeryNarrow,
                            onAction = onRequestCameraPermission
                        )
                    }
                    if (!hasAudioPermission) {
                        PermissionBannerCard(
                            icon = Icons.Default.Mic,
                            accent = SpatialIndigo,
                            containerColor = Color(0xFF142238),
                            title = stringResource(R.string.mic_permission_title),
                            rationale = stringResource(R.string.mic_permission_rationale),
                            buttonText = stringResource(R.string.grant_mic),
                            cardTag = "mic_permission_card",
                            buttonTag = "grant_mic_button",
                            isCompact = isVeryNarrow,
                            onAction = onRequestAudioPermission
                        )
                    }
                }
            } else {
                // Two-column responsive Dashboard for landscape handhelds, foldables, and tablets
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1140.dp)
                        .padding(horizontal = hPad, vertical = vPad),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.02f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        DashboardHeroBanner(isCompact = isCompactHeight)
                        DashboardActionButtons(
                            isVeryNarrow = false,
                            onEnterVr = onEnterVr,
                            onOpenCalibration = onOpenCalibration,
                            onOpenAbout = onOpenAbout
                        )
                        ModeAndMovementCard(
                            settings = settings,
                            isCompact = false,
                            onToggleVirtualRoom = onToggleVirtualRoom,
                            onToggleFloorTeleport = onToggleFloorTeleport
                        )
                        CoolingStatusCard(battery = battery)
                        if (!hasCameraPermission) {
                            PermissionBannerCard(
                                icon = Icons.Default.CameraAlt,
                                accent = AlertCrimson,
                                containerColor = Color(0xFF2A121A),
                                title = stringResource(R.string.camera_permission_title),
                                rationale = stringResource(R.string.camera_permission_rationale),
                                buttonText = stringResource(R.string.grant_camera),
                                cardTag = "camera_permission_card",
                                buttonTag = "grant_camera_button",
                                isCompact = false,
                                onAction = onRequestCameraPermission
                            )
                        }
                        if (!hasAudioPermission) {
                            PermissionBannerCard(
                                icon = Icons.Default.Mic,
                                accent = SpatialIndigo,
                                containerColor = Color(0xFF142238),
                                title = stringResource(R.string.mic_permission_title),
                                rationale = stringResource(R.string.mic_permission_rationale),
                                buttonText = stringResource(R.string.grant_mic),
                                cardTag = "mic_permission_card",
                                buttonTag = "grant_mic_button",
                                isCompact = false,
                                onAction = onRequestAudioPermission
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.98f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        VideoUrlCard(
                            urlDraft = urlDraft,
                            isCompact = false,
                            onUrlDraftChange = { urlDraft = it },
                            onVideoUrlChange = onVideoUrlChange
                        )
                        HandAndDebugCard(
                            settings = settings,
                            battery = battery,
                            cameraTelemetry = cameraTelemetry,
                            headEuler = headEuler,
                            headPosition = headPosition,
                            activeInputSource = activeInputSource,
                            gamepadState = gamepadState,
                            handState = handState,
                            measuredFps = measuredFps,
                            hasCameraPermission = hasCameraPermission,
                            isVeryNarrow = false,
                            onToggleHandTracking = onToggleHandTracking,
                            onToggleDebugOverlay = onToggleDebugOverlay
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHeroBanner(isCompact: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompact) 114.dp else 144.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_hero_vr_visor_1795643878296),
            contentDescription = "VisionBox Spatial Visor Hero Banner",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            VoidNavy.copy(alpha = 0.94f),
                            VoidNavy.copy(alpha = 0.45f)
                        )
                    )
                )
                .padding(if (isCompact) 12.dp else 16.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Vrpano,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = if (isCompact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isCompact) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Virtual Room & Passthrough • World-Locked Tabs • 6DoF PoseProvider",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardActionButtons(
    isVeryNarrow: Boolean,
    onEnterVr: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenAbout: () -> Unit
) {
    if (isVeryNarrow) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onEnterVr,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = VoidNavy
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("enter_vr_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Vrpano,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.enter_vr),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenCalibration,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("open_calibration_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.calibration),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onOpenAbout,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(0.85f)
                        .heightIn(min = 48.dp)
                        .testTag("open_about_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.about),
                        tint = SpatialIndigo,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "About",
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onEnterVr,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = VoidNavy
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1.3f)
                    .heightIn(min = 50.dp)
                    .testTag("enter_vr_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Vrpano,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.enter_vr),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onOpenCalibration,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp)
                    .testTag("open_calibration_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.calibration),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onOpenAbout,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .heightIn(min = 50.dp)
                    .testTag("open_about_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.about),
                    tint = SpatialIndigo,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "About",
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ModeAndMovementCard(
    settings: VrSettings,
    isCompact: Boolean,
    onToggleVirtualRoom: (Boolean) -> Unit,
    onToggleFloorTeleport: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DeepSpace)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(if (isCompact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MeetingRoom,
                    contentDescription = null,
                    tint = if (settings.isVirtualRoomMode) EmeraldOk else ElectricCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (settings.isVirtualRoomMode) "Mode: Virtual Room (3D)" else "Mode: Camera Passthrough (AR)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = if (settings.isVirtualRoomMode) {
                            "6.4m low-poly grid room with wall collision & motion parallax"
                        } else {
                            "1280×720 rear camera stereo feed with world-locked tabs"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
            Switch(
                checked = settings.isVirtualRoomMode,
                onCheckedChange = onToggleVirtualRoom,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidNavy,
                    checkedTrackColor = EmeraldOk
                ),
                modifier = Modifier.testTag("virtual_room_mode_switch")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsWalk,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Floor Gaze Teleport & Gamepad Walk",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
            Switch(
                checked = settings.floorTeleportEnabled,
                onCheckedChange = onToggleFloorTeleport,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidNavy,
                    checkedTrackColor = ElectricCyan
                ),
                modifier = Modifier.testTag("floor_teleport_switch")
            )
        }
    }
}

@Composable
private fun CoolingStatusCard(battery: BatteryTelemetry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (battery.hasWarning) Color(0xFF2D1908) else GlassSurface
            )
            .border(
                1.dp,
                if (battery.hasWarning) ThermalAmber else GlassBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp)
            .testTag("keep_phone_cool_card"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (battery.hasWarning) Icons.Default.Warning else Icons.Default.AcUnit,
            contentDescription = null,
            tint = if (battery.hasWarning) ThermalAmber else ElectricCyan,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = if (battery.hasWarning) {
                    "Thermal / Battery Alert: ${battery.temperatureCelsius}°C • ${battery.levelPercent}%"
                } else {
                    stringResource(R.string.keep_phone_cool_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (battery.hasWarning) ThermalAmber else Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.keep_phone_cool_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun PermissionBannerCard(
    icon: ImageVector,
    accent: Color,
    containerColor: Color,
    title: String,
    rationale: String,
    buttonText: String,
    cardTag: String,
    buttonTag: String,
    isCompact: Boolean,
    onAction: () -> Unit
) {
    val contentColumn = @Composable { modifier: Modifier ->
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = rationale,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }

    if (isCompact) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(containerColor)
                .border(1.dp, accent, RoundedCornerShape(14.dp))
                .padding(12.dp)
                .testTag(cardTag),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            contentColumn(Modifier.fillMaxWidth())
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(buttonTag)
            ) {
                Text(buttonText)
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(containerColor)
                .border(1.dp, accent, RoundedCornerShape(14.dp))
                .padding(12.dp)
                .testTag(cardTag),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            contentColumn(Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(buttonTag)
            ) {
                Text(buttonText)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoUrlCard(
    urlDraft: String,
    isCompact: Boolean,
    onUrlDraftChange: (String) -> Unit,
    onVideoUrlChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DeepSpace)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(if (isCompact) 12.dp else 14.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.video_url_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        onUrlDraftChange(VrSettings.DEFAULT_SAMPLE_VIDEO_URL)
                        onVideoUrlChange(VrSettings.DEFAULT_SAMPLE_VIDEO_URL)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("sample_url_1_button")
                ) {
                    Text(
                        text = stringResource(R.string.reset_sample_url),
                        color = ElectricCyan
                    )
                }
                TextButton(
                    onClick = {
                        onUrlDraftChange(VrSettings.SECOND_SAMPLE_VIDEO_URL)
                        onVideoUrlChange(VrSettings.SECOND_SAMPLE_VIDEO_URL)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("sample_url_2_button")
                ) {
                    Text(
                        text = stringResource(R.string.sample_url_2),
                        color = SpatialIndigo
                    )
                }
            }
        }

        OutlinedTextField(
            value = urlDraft,
            onValueChange = {
                onUrlDraftChange(it)
                onVideoUrlChange(it)
            },
            placeholder = { Text(stringResource(R.string.video_url_placeholder)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = GlassBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("video_url_input")
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = EmeraldOk,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Plays inside the World-Locked Cinema Tab via Media3 ExoPlayer.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun HandAndDebugCard(
    settings: VrSettings,
    battery: BatteryTelemetry,
    cameraTelemetry: CameraTelemetry,
    headEuler: HeadEulerAngles,
    headPosition: Vec3,
    activeInputSource: String,
    gamepadState: GamepadInputState,
    handState: HandTrackingState,
    measuredFps: Int,
    hasCameraPermission: Boolean,
    isVeryNarrow: Boolean,
    onToggleHandTracking: (Boolean) -> Unit,
    onToggleDebugOverlay: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DeepSpace)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(if (isVeryNarrow) 12.dp else 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PanTool,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "MediaPipe Hand Tracking (2 Hands • 15 FPS)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            Switch(
                checked = settings.handTrackingEnabled,
                onCheckedChange = onToggleHandTracking,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidNavy,
                    checkedTrackColor = EmeraldOk
                ),
                modifier = Modifier.testTag("hand_tracking_toggle_switch")
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.debug_toggle_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            Switch(
                checked = settings.showDebugOverlay,
                onCheckedChange = onToggleDebugOverlay,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidNavy,
                    checkedTrackColor = ElectricCyan
                ),
                modifier = Modifier.testTag("debug_toggle_switch")
            )
        }

        if (settings.showDebugOverlay) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .testTag("debug_telemetry_panel"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DebugMetricRow(
                    label = "Render Rate / Target",
                    value = "$measuredFps FPS (Kirin 710 30-60 FPS)",
                    stacked = isVeryNarrow
                )
                DebugMetricRow(
                    label = "PoseProvider Head Position",
                    value = String.format(
                        Locale.US,
                        "(%.2f, %.2f, %.2f) m • Y:%+.0f° P:%+.0f°",
                        headPosition.x,
                        headPosition.y,
                        headPosition.z,
                        headEuler.yawDeg,
                        headEuler.pitchDeg
                    ),
                    stacked = isVeryNarrow
                )
                DebugMetricRow(
                    label = "Active Input / Gamepad",
                    value = if (gamepadState.isConnected) {
                        "$activeInputSource • ${gamepadState.deviceName}"
                    } else {
                        "$activeInputSource • Saved Tabs: ${settings.windows.size}"
                    },
                    stacked = isVeryNarrow
                )
                DebugMetricRow(
                    label = "Hand Landmarker AI",
                    value = if (settings.handTrackingEnabled) {
                        "${handState.handFps} FPS / 15 FPS Cap (${handState.activeDelegate})"
                    } else {
                        "Disabled (Gaze Fallback Active)"
                    },
                    valueColor = if (settings.handTrackingEnabled) EmeraldOk else ThermalAmber,
                    stacked = isVeryNarrow
                )
                DebugMetricRow(
                    label = "Battery & Thermal",
                    value = String.format(
                        Locale.US,
                        "%.1f°C • %d%% %s",
                        battery.temperatureCelsius,
                        battery.levelPercent,
                        if (battery.hasWarning) "[ALERT]" else "[NOMINAL]"
                    ),
                    valueColor = if (battery.hasWarning) ThermalAmber else EmeraldOk,
                    stacked = isVeryNarrow
                )
                DebugMetricRow(
                    label = "CameraX Status",
                    value = if (hasCameraPermission) {
                        "${cameraTelemetry.width}×${cameraTelemetry.height} (${cameraTelemetry.statusText})"
                    } else {
                        "Permission Pending (Virtual Room Ready)"
                    },
                    stacked = isVeryNarrow
                )
            }
        }
    }
}

@Composable
private fun DebugMetricRow(
    label: String,
    value: String,
    valueColor: Color = ElectricCyan,
    stacked: Boolean = false
) {
    if (stacked) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.weight(0.45f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.55f)
            )
        }
    }
}
