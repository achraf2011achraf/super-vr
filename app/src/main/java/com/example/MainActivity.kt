package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AboutScreen
import com.example.ui.AppScreen
import com.example.ui.CalibrationScreen
import com.example.ui.HomeStartScreen
import com.example.ui.VisionBoxViewModel
import com.example.ui.VrExperienceScreen
import com.example.ui.theme.VisionBoxTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VisionBoxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VisionBoxTheme {
                val screen by viewModel.currentScreen.collectAsStateWithLifecycle()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val battery by viewModel.batteryTelemetry.collectAsStateWithLifecycle()
                val cameraTelemetry by viewModel.cameraTelemetry.collectAsStateWithLifecycle()
                val headEuler by viewModel.headEuler.collectAsStateWithLifecycle()
                val headPosition by viewModel.headPosition.collectAsStateWithLifecycle()
                val activeInputSource by viewModel.activeInputSource.collectAsStateWithLifecycle()
                val gamepadState by viewModel.gamepadState.collectAsStateWithLifecycle()
                val videoState by viewModel.videoState.collectAsStateWithLifecycle()
                val handState by viewModel.handState.collectAsStateWithLifecycle()
                val aiState by viewModel.aiState.collectAsStateWithLifecycle()
                val measuredFps by viewModel.measuredFps.collectAsStateWithLifecycle()
                val placingWindowId by viewModel.placingWindowId.collectAsStateWithLifecycle()
                val pendingVoiceAction by viewModel.pendingVoiceAction.collectAsStateWithLifecycle()

                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                var hasAudioPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                var pendingEnterVrAfterPermission by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasCameraPermission = granted
                    if (pendingEnterVrAfterPermission) {
                        pendingEnterVrAfterPermission = false
                        viewModel.navigateTo(AppScreen.VR_IMMERSIVE)
                    }
                }

                val audioPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasAudioPermission = granted
                    viewModel.onAudioPermissionResult(granted)
                }

                LaunchedEffect(pendingVoiceAction) {
                    val action = pendingVoiceAction ?: return@LaunchedEffect
                    val currentlyGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    hasAudioPermission = currentlyGranted
                    if (currentlyGranted) {
                        viewModel.executePendingVoiceAction(action, hasAudioPermission = true)
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                // Manage immersive fullscreen & keepScreenOn when entering/exiting VR mode
                LaunchedEffect(screen) {
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    if (screen == AppScreen.VR_IMMERSIVE) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        insetsController.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                when (screen) {
                    AppScreen.HOME_2D -> {
                        HomeStartScreen(
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
                            hasAudioPermission = hasAudioPermission,
                            onRequestCameraPermission = {
                                pendingEnterVrAfterPermission = false
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            onRequestAudioPermission = {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onEnterVr = {
                                if (!hasCameraPermission) {
                                    pendingEnterVrAfterPermission = true
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                } else {
                                    viewModel.navigateTo(AppScreen.VR_IMMERSIVE)
                                }
                            },
                            onOpenCalibration = {
                                viewModel.navigateTo(AppScreen.CALIBRATION_2D)
                            },
                            onOpenAbout = {
                                viewModel.navigateTo(AppScreen.ABOUT_2D)
                            },
                            onVideoUrlChange = { url ->
                                viewModel.setVideoUrl(url)
                            },
                            onToggleVirtualRoom = { enabled ->
                                viewModel.setVirtualRoomMode(enabled)
                            },
                            onToggleFloorTeleport = { enabled ->
                                viewModel.setFloorTeleportEnabled(enabled)
                            },
                            onToggleHandTracking = { enabled ->
                                viewModel.setHandTrackingEnabled(enabled)
                            },
                            onToggleDebugOverlay = { show ->
                                viewModel.setShowDebug(show)
                            }
                        )
                    }

                    AppScreen.CALIBRATION_2D -> {
                        CalibrationScreen(
                            settings = settings,
                            onIpdChange = { viewModel.setIpdOffset(it) },
                            onDistortionChange = { viewModel.setDistortion(it) },
                            onScaleChange = { viewModel.setLensScale(it) },
                            onPanelDistanceChange = { viewModel.setPanelDistance(it) },
                            onGazeDurationChange = { viewModel.setGazeDuration(it) },
                            onToggleVirtualRoom = { viewModel.setVirtualRoomMode(it) },
                            onToggleFloorTeleport = { viewModel.setFloorTeleportEnabled(it) },
                            onToggleHandTracking = { viewModel.setHandTrackingEnabled(it) },
                            onToggleDebugOverlay = { viewModel.setShowDebug(it) },
                            onResetDefaults = { viewModel.resetSettingsDefaults() },
                            onEnterVr = {
                                if (!hasCameraPermission) {
                                    pendingEnterVrAfterPermission = true
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                } else {
                                    viewModel.navigateTo(AppScreen.VR_IMMERSIVE)
                                }
                            },
                            onBack = {
                                viewModel.navigateTo(AppScreen.HOME_2D)
                            }
                        )
                    }

                    AppScreen.ABOUT_2D -> {
                        AboutScreen(
                            onBack = {
                                viewModel.navigateTo(AppScreen.HOME_2D)
                            }
                        )
                    }

                    AppScreen.VR_IMMERSIVE -> {
                        VrExperienceScreen(
                            settings = settings,
                            battery = battery,
                            cameraTelemetry = cameraTelemetry,
                            headPosition = headPosition,
                            activeInputSource = activeInputSource,
                            videoState = videoState,
                            handState = handState,
                            aiState = aiState,
                            measuredFps = measuredFps,
                            hasCameraPermission = hasCameraPermission,
                            placingWindowId = placingWindowId,
                            poseProvider = viewModel.poseProvider,
                            roomBounds = viewModel.roomBounds,
                            gazeController = viewModel.gazeController,
                            cameraManager = viewModel.cameraManager,
                            videoController = viewModel.videoController,
                            handTrackingManager = viewModel.handTrackingManager,
                            onStepLocomotion = { dt -> viewModel.stepLocomotion(dt) },
                            onGazeCommand = { cmd -> viewModel.handleGazeButtonCommand(cmd) },
                            onPlaceWindowAtGaze = { id, headPos, dx, dy, dz ->
                                viewModel.completeWindowPlacementAtGaze(id, headPos, dx, dy, dz)
                            },
                            onWindowTransformed = { id, px, py, pz, yaw, pitch, scale, wallSnapped, isFinal ->
                                viewModel.onWindowTransformed(id, px, py, pz, yaw, pitch, scale, wallSnapped, isFinal)
                            },
                            onTeleportFloor = { tx, tz -> viewModel.teleportToFloor(tx, tz) },
                            onFpsMeasured = { fps -> viewModel.onFpsUpdated(fps) },
                            onTelemetrySample = { pos, input -> viewModel.onRendererTelemetry(pos, input) },
                            onToggleVirtualRoom = { viewModel.setVirtualRoomMode(!settings.isVirtualRoomMode) },
                            onNewNoteWindow = { viewModel.spawnNewNoteWindow() },
                            onRecenter = { viewModel.recenterYaw() },
                            onTriggerWhatAmISeeing = { viewModel.requestWhatAmISeeing() },
                            onRotateCamera90 = { viewModel.rotateCamera90() },
                            onExitVr = { viewModel.navigateTo(AppScreen.HOME_2D) }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onLifecycleResume()
    }

    override fun onPause() {
        viewModel.onLifecyclePause()
        super.onPause()
    }

    override fun onStop() {
        viewModel.onLifecyclePause()
        super.onStop()
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && viewModel.gamepadController.onGenericMotionEvent(event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * Handles Bluetooth/USB gamepad buttons plus hardware Volume keys:
     * - Volume-Up recenters head yaw & room position
     * - Volume-Down triggers "What am I seeing?" voice + vision capture
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.gamepadController.onKeyDown(keyCode, event)) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            viewModel.recenterYaw()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            viewModel.currentScreen.value == AppScreen.VR_IMMERSIVE
        ) {
            viewModel.requestWhatAmISeeing()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.gamepadController.onKeyUp(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
