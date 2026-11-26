package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.VrSettings
import com.example.ui.theme.DeepSpace
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldOk
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.SpatialIndigo
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidNavy
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationScreen(
    settings: VrSettings,
    onIpdChange: (Float) -> Unit,
    onDistortionChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onPanelDistanceChange: (Float) -> Unit,
    onGazeDurationChange: (Float) -> Unit,
    onToggleVirtualRoom: (Boolean) -> Unit = {},
    onToggleFloorTeleport: (Boolean) -> Unit = {},
    onToggleHandTracking: (Boolean) -> Unit = {},
    onToggleDebugOverlay: (Boolean) -> Unit = {},
    onResetDefaults: () -> Unit,
    onEnterVr: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

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
            val isVeryNarrow = maxWidth < 400.dp
            val isCompactHeight = maxHeight < 460.dp
            val hPad = if (isVeryNarrow) 12.dp else if (isCompactWidth) 14.dp else 20.dp
            val vPad = if (isCompactHeight) 8.dp else 12.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 1140.dp)
                    .padding(horizontal = hPad, vertical = vPad)
            ) {
                // Responsive Top Bar (wraps action buttons cleanly on small screens)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("calib_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back_to_home),
                                tint = ElectricCyan
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(if (isVeryNarrow) 20.dp else 22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCompactWidth) "Settings & Optics" else stringResource(R.string.calib_title),
                            style = if (isCompactWidth) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onResetDefaults,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("calib_reset_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reset_defaults),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isCompactWidth) "Reset" else stringResource(R.string.reset_defaults),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onEnterVr,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = VoidNavy
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("calib_enter_vr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isVeryNarrow) "Open VR" else "Test in VR",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

                if (isCompactWidth) {
                    // Single scrollable Settings + Preview column for small phones, portrait, and split-screen
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StereoOpticsPreviewCard(
                            settings = settings,
                            isCompactHeight = isCompactHeight,
                            fixedPreviewHeight = if (isCompactHeight) 160.dp else 196.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppPreferencesSettingsCard(
                            settings = settings,
                            isCompact = isVeryNarrow,
                            onToggleVirtualRoom = onToggleVirtualRoom,
                            onToggleFloorTeleport = onToggleFloorTeleport,
                            onToggleHandTracking = onToggleHandTracking,
                            onToggleDebugOverlay = onToggleDebugOverlay
                        )
                        OpticsSlidersColumn(
                            settings = settings,
                            isCompact = isVeryNarrow,
                            onIpdChange = onIpdChange,
                            onDistortionChange = onDistortionChange,
                            onScaleChange = onScaleChange,
                            onPanelDistanceChange = onPanelDistanceChange,
                            onGazeDurationChange = onGazeDurationChange,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    // Two-column responsive layout for landscape phones, foldables, and tablets
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OpticsSlidersColumn(
                                settings = settings,
                                isCompact = false,
                                onIpdChange = onIpdChange,
                                onDistortionChange = onDistortionChange,
                                onScaleChange = onScaleChange,
                                onPanelDistanceChange = onPanelDistanceChange,
                                onGazeDurationChange = onGazeDurationChange,
                                modifier = Modifier.fillMaxWidth()
                            )
                            AppPreferencesSettingsCard(
                                settings = settings,
                                isCompact = false,
                                onToggleVirtualRoom = onToggleVirtualRoom,
                                onToggleFloorTeleport = onToggleFloorTeleport,
                                onToggleHandTracking = onToggleHandTracking,
                                onToggleDebugOverlay = onToggleDebugOverlay
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(0.95f)
                                .fillMaxHeight()
                                .then(
                                    if (isCompactHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
                                )
                        ) {
                            StereoOpticsPreviewCard(
                                settings = settings,
                                isCompactHeight = isCompactHeight,
                                fixedPreviewHeight = if (isCompactHeight) 170.dp else null,
                                modifier = if (isCompactHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpticsSlidersColumn(
    settings: VrSettings,
    isCompact: Boolean,
    onIpdChange: (Float) -> Unit,
    onDistortionChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onPanelDistanceChange: (Float) -> Unit,
    onGazeDurationChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DeepSpace)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(if (isCompact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CalibrationSliderCard(
            title = stringResource(R.string.calib_ipd_label),
            valueText = String.format(
                Locale.US,
                "%d mm (%+.2f)",
                settings.ipdMillimeters,
                settings.ipdOffset
            ),
            value = settings.ipdOffset,
            valueRange = -0.12f..0.12f,
            onValueChange = onIpdChange,
            testTag = "slider_ipd"
        )

        CalibrationSliderCard(
            title = stringResource(R.string.calib_distortion_label),
            valueText = String.format(Locale.US, "k1 = %.2f", settings.distortionK1),
            value = settings.distortionK1,
            valueRange = 0.00f..0.55f,
            onValueChange = onDistortionChange,
            testTag = "slider_distortion"
        )

        CalibrationSliderCard(
            title = stringResource(R.string.calib_scale_label),
            valueText = String.format(Locale.US, "%d%%", (settings.lensScale * 100).toInt()),
            value = settings.lensScale,
            valueRange = 0.65f..1.15f,
            onValueChange = onScaleChange,
            testTag = "slider_scale"
        )

        CalibrationSliderCard(
            title = stringResource(R.string.calib_distance_label),
            valueText = String.format(Locale.US, "%.1f m", settings.panelDistanceMeters),
            value = settings.panelDistanceMeters,
            valueRange = 1.2f..3.5f,
            onValueChange = onPanelDistanceChange,
            testTag = "slider_panel_distance"
        )

        CalibrationSliderCard(
            title = stringResource(R.string.calib_gaze_time_label),
            valueText = String.format(Locale.US, "%.1f s", settings.gazeDurationSec),
            value = settings.gazeDurationSec,
            valueRange = 0.8f..3.0f,
            onValueChange = onGazeDurationChange,
            testTag = "slider_gaze_time"
        )
    }
}

@Composable
private fun AppPreferencesSettingsCard(
    settings: VrSettings,
    isCompact: Boolean,
    onToggleVirtualRoom: (Boolean) -> Unit,
    onToggleFloorTeleport: (Boolean) -> Unit,
    onToggleHandTracking: (Boolean) -> Unit,
    onToggleDebugOverlay: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DeepSpace)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(if (isCompact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "ENVIRONMENT & INPUT SETTINGS",
            style = MaterialTheme.typography.labelLarge,
            color = ElectricCyan
        )
        SettingsSwitchRow(
            icon = Icons.Default.MeetingRoom,
            tint = if (settings.isVirtualRoomMode) EmeraldOk else ElectricCyan,
            title = "Virtual Room Mode (vs Passthrough)",
            checked = settings.isVirtualRoomMode,
            onCheckedChange = onToggleVirtualRoom,
            testTag = "settings_virtual_room_switch"
        )
        SettingsSwitchRow(
            icon = Icons.Default.DirectionsWalk,
            tint = ElectricCyan,
            title = "Floor Gaze Teleport",
            checked = settings.floorTeleportEnabled,
            onCheckedChange = onToggleFloorTeleport,
            testTag = "settings_floor_teleport_switch"
        )
        SettingsSwitchRow(
            icon = Icons.Default.PanTool,
            tint = EmeraldOk,
            title = "MediaPipe Hand Tracking (15 FPS)",
            checked = settings.handTrackingEnabled,
            onCheckedChange = onToggleHandTracking,
            testTag = "settings_hand_tracking_switch"
        )
        SettingsSwitchRow(
            icon = Icons.Default.BugReport,
            tint = ElectricCyan,
            title = stringResource(R.string.debug_toggle_label),
            checked = settings.showDebugOverlay,
            onCheckedChange = onToggleDebugOverlay,
            testTag = "settings_debug_overlay_switch"
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
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
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VoidNavy,
                checkedTrackColor = ElectricCyan
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun StereoOpticsPreviewCard(
    settings: VrSettings,
    isCompactHeight: Boolean,
    fixedPreviewHeight: androidx.compose.ui.unit.Dp?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DeepSpace)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(if (isCompactHeight) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LIVE STEREO OPTICS PREVIEW",
                style = MaterialTheme.typography.labelLarge,
                color = ElectricCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Saved to DataStore",
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldOk
            )
        }

        val canvasBoxModifier = if (fixedPreviewHeight != null) {
            Modifier
                .fillMaxWidth()
                .height(fixedPreviewHeight)
        } else {
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(min = 160.dp)
        }

        Box(
            modifier = canvasBoxModifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                .testTag("stereo_calibration_preview")
        ) {
            StereoLensPreviewCanvas(
                ipdOffset = settings.ipdOffset,
                distortionK1 = settings.distortionK1,
                lensScale = settings.lensScale
            )
        }

        Text(
            text = "Align the cyan crosshairs with your Cardboard lenses. Increase k1 to counter edge pincushion distortion.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun CalibrationSliderCard(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = ElectricCyan,
                activeTrackColor = ElectricCyan,
                inactiveTrackColor = VoidNavy
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(testTag)
        )
    }
}

@Composable
private fun StereoLensPreviewCanvas(
    ipdOffset: Float,
    distortionK1: Float,
    lensScale: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val eyeWidth = size.width / 2f
        val eyeHeight = size.height

        fun projectPoint(
            nx: Float,
            ny: Float,
            eyeIndex: Int,
            eyeSign: Float
        ): Offset {
            val centerX = -eyeSign * ipdOffset * 0.5f
            val dx = (nx * lensScale)
            val dy = (ny * lensScale)
            val r2 = dx * dx + dy * dy
            val barrel = 1f / (1f + distortionK1 * r2 * 0.85f)
            val wx = centerX + dx * barrel
            val wy = dy * barrel

            val px = (eyeIndex * eyeWidth) + (wx * 0.5f + 0.5f) * eyeWidth
            val py = (wy * 0.5f + 0.5f) * eyeHeight
            return Offset(px, py)
        }

        for (eyeIdx in 0..1) {
            val eyeSign = if (eyeIdx == 0) -1f else 1f
            val gridSteps = 8
            val lineSteps = 20

            for (gx in -gridSteps..gridSteps step 2) {
                val nx = gx / gridSteps.toFloat()
                val path = Path()
                for (gy in -lineSteps..lineSteps) {
                    val ny = gy / lineSteps.toFloat()
                    val pt = projectPoint(nx, ny, eyeIdx, eyeSign)
                    if (gy == -lineSteps) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = path,
                    color = if (gx == 0) ElectricCyan else SpatialIndigo.copy(alpha = 0.55f),
                    style = Stroke(width = if (gx == 0) 3f else 1.5f)
                )
            }

            for (gy in -gridSteps..gridSteps step 2) {
                val ny = gy / gridSteps.toFloat()
                val path = Path()
                for (gx in -lineSteps..lineSteps) {
                    val nx = gx / lineSteps.toFloat()
                    val pt = projectPoint(nx, ny, eyeIdx, eyeSign)
                    if (gx == -lineSteps) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = path,
                    color = if (gy == 0) ElectricCyan else SpatialIndigo.copy(alpha = 0.55f),
                    style = Stroke(width = if (gy == 0) 3f else 1.5f)
                )
            }

            val panelTopLeft = projectPoint(-0.35f, -0.25f, eyeIdx, eyeSign)
            val panelBottomRight = projectPoint(0.35f, 0.25f, eyeIdx, eyeSign)
            drawRect(
                color = ElectricCyan.copy(alpha = 0.16f),
                topLeft = panelTopLeft,
                size = Size(
                    (panelBottomRight.x - panelTopLeft.x).coerceAtLeast(4f),
                    (panelBottomRight.y - panelTopLeft.y).coerceAtLeast(4f)
                )
            )

            val center = projectPoint(0f, 0f, eyeIdx, eyeSign)
            drawCircle(
                color = EmeraldOk,
                radius = 7f,
                center = center,
                style = Stroke(width = 2.5f)
            )
        }

        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = Offset(eyeWidth, 0f),
            end = Offset(eyeWidth, eyeHeight),
            strokeWidth = 3f
        )
    }
}
