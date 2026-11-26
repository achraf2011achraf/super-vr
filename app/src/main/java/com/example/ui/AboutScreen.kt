package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.DeepSpace
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldOk
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidNavy

data class AssumptionSpec(
    val category: String,
    val title: String,
    val detail: String
)

private val ARCHITECTURAL_ASSUMPTIONS = listOf(
    AssumptionSpec(
        category = "VIRTUAL ROOM & KIRIN 710 RENDERING",
        title = "Low-Poly 6.4m × 6.4m OpenGL ES 2.0 Room + Passthrough Toggle",
        detail = "Draws a procedural 512×512 mipmapped grid floor, 4 walls with cyan baseboard trim, skylight ceiling, directional+hemisphere lighting, and low-poly props (desk + monitor, rotating orb pedestal, lounge bench, shelf pillar) to hold 30–60 FPS on Mali-G51 MP4. Users can switch cleanly between Virtual Room and Camera Passthrough anytime."
    ),
    AssumptionSpec(
        category = "6DoF POSEPROVIDER ARCHITECTURE (NO ARCORE)",
        title = "GyroPoseProvider Active + ArCorePoseProvider & RemoteSlamPoseProvider Stubs",
        detail = "All rendering, raycasting, and room coordinates read from com.example.vr.pose.PoseProvider (headPosition: Vec3, headRotation: Quaternion). GyroPoseProvider fuses 3DoF gyroscope rotation with walking locomotion, snap-turns, floor teleport, and wall/prop collision. ArCorePoseProvider and RemoteSlamPoseProvider (PC WebSocket) stubs drop in cleanly without touching room or window code."
    ),
    AssumptionSpec(
        category = "GAMEPAD LOCOMOTION & FLOOR TELEPORT",
        title = "Bluetooth Left-Stick Walking, Right-Stick/L1-R1 Snap Turn & Gaze Floor Teleport",
        detail = "Moving with a Bluetooth gamepad left stick walks across the room with natural 3D motion parallax against world-locked windows and wall collision clamping. Right stick or L1/R1 snap-turns ±30°. Looking at the floor (1.5s dwell or Gamepad A) teleports to the targeted floor ring; Button Y / Volume-Up recenters."
    ),
    AssumptionSpec(
        category = "WORLD-COORDINATE WINDOWS, WALL-SNAP & 2-HAND RESIZE",
        title = "Persistent Spatial Tabs (Home, Video, Notes, Settings, AI Vision, +New Window)",
        detail = "All windows live at persistent world coordinates (posX, posY, posZ, yaw, pitch, scale) saved in DataStore. One-hand pinch grabs and moves a window along your hand ray—releasing near a wall snaps flush facing inward, or locks facing you in open space. Two-hand pinch spread (or S-/S+ buttons) scales windows, and X closes tabs."
    ),
    AssumptionSpec(
        category = "MEDIAPIPE HAND TRACKING & GAZE FALLBACK",
        title = "320×240 ImageAnalysis @ 15 FPS Cap (GPU Delegate + CPU Fallback)",
        detail = "Detects up to 2 hands on a background thread at 15 FPS, rendering 21 landmarks and bone overlays in both eyes with pinch hysteresis (enter < 0.35, release > 0.49). Standard 1.5s gaze dwell + PLACE mode always works automatically as a fallback when no hand is detected."
    ),
    AssumptionSpec(
        category = "WHAT AM I SEEING? (VISION AI ASSISTANT)",
        title = "Volume-Down / Gaze Trigger + SpeechRecognizer + 1024px JPEG + TTS",
        detail = "Triggered exclusively by user action (gazing at 'WHAT AM I SEEING?' or pressing Volume-Down). Shows a stereo 'LISTENING...' indicator while recording your spoken question, downscales the camera frame to <=1024px JPEG with a visible 'AI CAMERA ACTIVE' badge, calls the configured FreeLLMAPI endpoint (via BuildConfig.FREELLMAPI_API_KEY), reads the response aloud via TextToSpeech, and supports follow-up questions."
    )
)

@Composable
fun AboutScreen(
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
            val isCompact = maxWidth < 600.dp
            val hPad = if (isCompact) 12.dp else 20.dp
            val vPad = if (maxHeight < 460.dp) 8.dp else 12.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 920.dp)
                    .padding(horizontal = hPad, vertical = vPad)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("about_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_to_home),
                            tint = ElectricCyan
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.about_title),
                        style = if (isCompact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DeepSpace)
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(if (isCompact) 12.dp else 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (item in ARCHITECTURAL_ASSUMPTIONS) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlassSurface)
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = item.category,
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldOk
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = ElectricCyan
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
