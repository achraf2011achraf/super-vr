package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.SpatialWindowState
import com.example.data.VrSettings
import com.example.data.VrSettingsRepository
import com.example.data.WindowKind
import com.example.vr.BatteryMonitor
import com.example.vr.BatteryTelemetry
import com.example.vr.CameraPassthroughManager
import com.example.vr.CameraTelemetry
import com.example.vr.GazeController
import com.example.vr.HeadEulerAngles
import com.example.vr.HeadTracker
import com.example.vr.VideoPlayerState
import com.example.vr.VrVideoController
import com.example.vr.ai.VisionAiAssistant
import com.example.vr.ai.VisionAiState
import com.example.vr.depth.DefaultFallbackDepthEstimator
import com.example.vr.depth.DepthEstimator
import com.example.vr.hand.HandTrackingManager
import com.example.vr.hand.HandTrackingState
import com.example.vr.input.GamepadController
import com.example.vr.input.GamepadInputState
import com.example.vr.pose.GyroPoseProvider
import com.example.vr.pose.PoseProvider
import com.example.vr.pose.RoomBounds
import com.example.vr.pose.Vec3
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class AppScreen {
    HOME_2D,
    CALIBRATION_2D,
    ABOUT_2D,
    VR_IMMERSIVE
}

enum class PendingVoiceAction {
    NEW_CAPTURE,
    FOLLOW_UP
}

class VisionBoxViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VrSettingsRepository(application)
    val headTracker = HeadTracker(application)
    val roomBounds = RoomBounds()
    val gyroPoseProvider = GyroPoseProvider(headTracker, roomBounds)
    val poseProvider: PoseProvider = gyroPoseProvider
    val gazeController = GazeController()

    val batteryMonitor = BatteryMonitor(application)
    val cameraManager = CameraPassthroughManager(application)
    val videoController = VrVideoController(application)
    val handTrackingManager = HandTrackingManager(application)
    val visionAiAssistant = VisionAiAssistant(application, viewModelScope, cameraManager)

    val depthEstimator: DepthEstimator = DefaultFallbackDepthEstimator().apply {
        initialize()
    }

    val gamepadController: GamepadController = GamepadController(
        onSnapTurn = { deltaDeg ->
            gyroPoseProvider.snapTurn(deltaDeg)
            _activeInputSource.value = "Gamepad Snap Turn"
        },
        onPrimaryAction = {
            gazeController.triggerPrimaryAction()
            _activeInputSource.value = "Gamepad Click"
        },
        onRecenter = {
            recenterYaw()
        }
    )

    val settings: StateFlow<VrSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VrSettings()
    )

    val headEuler: StateFlow<HeadEulerAngles> = headTracker.eulerAngles
    val batteryTelemetry: StateFlow<BatteryTelemetry> = batteryMonitor.telemetry
    val cameraTelemetry: StateFlow<CameraTelemetry> = cameraManager.telemetry
    val videoState: StateFlow<VideoPlayerState> = videoController.state
    val handState: StateFlow<HandTrackingState> = handTrackingManager.state
    val aiState: StateFlow<VisionAiState> = visionAiAssistant.state
    val gamepadState: StateFlow<GamepadInputState> = gamepadController.state

    private val _currentScreen = MutableStateFlow(AppScreen.HOME_2D)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _measuredFps = MutableStateFlow(60)
    val measuredFps: StateFlow<Int> = _measuredFps.asStateFlow()

    private val _headPosition = MutableStateFlow(gyroPoseProvider.headPosition)
    val headPosition: StateFlow<Vec3> = _headPosition.asStateFlow()

    private val _activeInputSource = MutableStateFlow("Gaze")
    val activeInputSource: StateFlow<String> = _activeInputSource.asStateFlow()

    private val _placingWindowId = MutableStateFlow<String?>(null)
    val placingWindowId: StateFlow<String?> = _placingWindowId.asStateFlow()

    private val _pendingVoiceAction = MutableStateFlow<PendingVoiceAction?>(null)
    val pendingVoiceAction: StateFlow<PendingVoiceAction?> = _pendingVoiceAction.asStateFlow()

    private var customNoteCounter = 2

    init {
        viewModelScope.launch {
            settings.collect { s ->
                handTrackingManager.isEnabled = s.handTrackingEnabled
                cameraManager.cameraRotationOffsetDeg = s.cameraRotationOffsetDeg
            }
        }

        viewModelScope.launch {
            while (isActive) {
                if (_currentScreen.value == AppScreen.VR_IMMERSIVE) {
                    videoController.pollPosition()
                }
                delay(500L)
            }
        }
    }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun onFpsUpdated(fps: Int) {
        _measuredFps.value = fps
    }

    fun stepLocomotion(dtSec: Float) {
        val gp = gamepadController.state.value
        if (abs(gp.moveX) > 0.01f || abs(gp.moveZ) > 0.01f) {
            gyroPoseProvider.applyWalkingInput(gp.moveX, gp.moveZ, dtSec)
        }
    }

    fun onRendererTelemetry(headPos: Vec3, gazeInputSource: String) {
        _headPosition.value = headPos
        val gp = gamepadController.state.value
        _activeInputSource.value = if (abs(gp.moveX) > 0.1f || abs(gp.moveZ) > 0.1f) {
            "Gamepad Walk + $gazeInputSource"
        } else {
            gazeInputSource
        }
    }

    fun recenterYaw() {
        gyroPoseProvider.recenter()
        _headPosition.value = gyroPoseProvider.headPosition
        _activeInputSource.value = "Recentered"
    }

    fun teleportToFloor(worldX: Float, worldZ: Float) {
        gyroPoseProvider.teleportTo(worldX, worldZ)
        _headPosition.value = gyroPoseProvider.headPosition
        _activeInputSource.value = "Floor Teleport"
    }

    fun setIpdOffset(value: Float) {
        viewModelScope.launch { repository.updateIpdOffset(value) }
    }

    fun setDistortion(value: Float) {
        viewModelScope.launch { repository.updateDistortion(value) }
    }

    fun setLensScale(value: Float) {
        viewModelScope.launch { repository.updateLensScale(value) }
    }

    fun setPanelDistance(value: Float) {
        viewModelScope.launch { repository.updatePanelDistance(value) }
    }

    fun setGazeDuration(value: Float) {
        viewModelScope.launch { repository.updateGazeDuration(value) }
    }

    fun setHandTrackingEnabled(enabled: Boolean) {
        handTrackingManager.isEnabled = enabled
        viewModelScope.launch { repository.updateHandTrackingEnabled(enabled) }
    }

    fun setVirtualRoomMode(enabled: Boolean) {
        viewModelScope.launch { repository.updateVirtualRoomMode(enabled) }
    }

    fun setFloorTeleportEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.updateFloorTeleportEnabled(enabled) }
    }

    fun rotateCamera90() {
        viewModelScope.launch { repository.rotateCamera90() }
    }

    fun setVideoUrl(url: String) {
        viewModelScope.launch {
            repository.updateVideoUrl(url)
            videoController.loadUrl(url, autoPlay = false)
        }
    }

    fun setShowDebug(show: Boolean) {
        viewModelScope.launch { repository.updateShowDebug(show) }
    }

    fun resetSettingsDefaults() {
        viewModelScope.launch { repository.resetDefaults() }
    }

    fun completeWindowPlacementAtGaze(
        windowId: String,
        headPos: Vec3,
        dirX: Float,
        dirY: Float,
        dirZ: Float
    ) {
        _placingWindowId.value = null
        val depthEstimate = depthEstimator.estimateDepthAtGaze(
            frameBitmap = null,
            normX = 0.5f,
            normY = 0.5f,
            fallbackDistanceMeters = 1.95f
        )
        val dist = depthEstimate.estimatedDistanceMeters
        val rawX = headPos.x + dirX * dist
        val rawY = headPos.y + dirY * dist
        val rawZ = headPos.z + dirZ * dist

        val resolved = GazeController.computeSnappedOrUserFacingPose(
            rawX = rawX,
            rawY = rawY,
            rawZ = rawZ,
            headX = headPos.x,
            headY = headPos.y,
            headZ = headPos.z,
            isVirtualRoomMode = settings.value.isVirtualRoomMode,
            roomBounds = roomBounds
        )
        updateSingleWindow(windowId) { w ->
            w.copy(
                posX = resolved.posX,
                posY = resolved.posY,
                posZ = resolved.posZ,
                yawDeg = resolved.yawDeg,
                pitchDeg = resolved.pitchDeg,
                isWallSnapped = resolved.isWallSnapped,
                isOpen = true
            )
        }
    }

    fun onWindowTransformed(
        windowId: String,
        posX: Float,
        posY: Float,
        posZ: Float,
        yawDeg: Float,
        pitchDeg: Float,
        scale: Float,
        isWallSnapped: Boolean,
        isFinalRelease: Boolean
    ) {
        if (!isFinalRelease) return
        updateSingleWindow(windowId) { w ->
            w.copy(
                posX = posX,
                posY = posY,
                posZ = posZ,
                yawDeg = yawDeg,
                pitchDeg = pitchDeg,
                scale = scale.coerceIn(0.55f, 2.20f),
                isWallSnapped = isWallSnapped
            )
        }
    }

    private fun updateSingleWindow(
        windowId: String,
        transform: (SpatialWindowState) -> SpatialWindowState
    ) {
        viewModelScope.launch {
            val current = settings.value.windows
            val updated = current.map { w ->
                if (w.id == windowId) transform(w) else w
            }
            repository.saveWindows(updated)
        }
    }

    fun spawnNewNoteWindow() {
        val head = gyroPoseProvider.headPosition
        val yawRad = Math.toRadians(gazeController.currentGazeYawDeg.toDouble())
        val offsetYaw = yawRad + Math.toRadians(16.0)
        val dirX = sin(offsetYaw).toFloat()
        val dirZ = -cos(offsetYaw).toFloat()

        val resolved = GazeController.computeSnappedOrUserFacingPose(
            rawX = head.x + dirX * 1.75f,
            rawY = head.y + 0.05f,
            rawZ = head.z + dirZ * 1.75f,
            headX = head.x,
            headY = head.y,
            headZ = head.z,
            isVirtualRoomMode = settings.value.isVirtualRoomMode,
            roomBounds = roomBounds
        )

        val num = customNoteCounter++
        val newWin = SpatialWindowState(
            id = "note_${System.currentTimeMillis()}",
            kind = WindowKind.NOTES,
            title = "SPATIAL NOTE #$num",
            posX = resolved.posX,
            posY = resolved.posY,
            posZ = resolved.posZ,
            yawDeg = resolved.yawDeg,
            pitchDeg = resolved.pitchDeg,
            scale = 0.95f,
            isOpen = true,
            isWallSnapped = resolved.isWallSnapped,
            noteText = NOTE_TEMPLATES[num % NOTE_TEMPLATES.size]
        )

        viewModelScope.launch {
            val current = settings.value.windows.toMutableList()
            // Keep max 8 total windows so Kirin 710 stays comfortably in 30-60 FPS budget
            if (current.size >= 8) {
                val oldestSpawned = current.indexOfFirst { it.id.startsWith("note_") }
                if (oldestSpawned >= 0) current.removeAt(oldestSpawned)
            }
            current.add(newWin)
            repository.saveWindows(current)
        }
    }

    fun requestWhatAmISeeing() {
        positionAiPanelNearLookDirection()
        visionAiAssistant.openPanelOnly()
        _pendingVoiceAction.value = PendingVoiceAction.NEW_CAPTURE
    }

    fun requestAiFollowUp() {
        visionAiAssistant.openPanelOnly()
        _pendingVoiceAction.value = PendingVoiceAction.FOLLOW_UP
    }

    fun executePendingVoiceAction(action: PendingVoiceAction, hasAudioPermission: Boolean) {
        _pendingVoiceAction.value = null
        val isCamActive = cameraTelemetry.value.isActive
        when (action) {
            PendingVoiceAction.NEW_CAPTURE -> {
                visionAiAssistant.triggerWhatAmISeeing(
                    hasMicPermission = hasAudioPermission,
                    isCameraActive = isCamActive
                )
            }
            PendingVoiceAction.FOLLOW_UP -> {
                visionAiAssistant.triggerFollowUpQuestion(
                    hasMicPermission = hasAudioPermission,
                    isCameraActive = isCamActive
                )
            }
        }
    }

    fun onAudioPermissionResult(granted: Boolean) {
        val pending = _pendingVoiceAction.value
        _pendingVoiceAction.value = null
        if (pending != null) {
            if (granted) {
                executePendingVoiceAction(pending, hasAudioPermission = true)
            } else {
                visionAiAssistant.onMicPermissionDenied()
            }
        } else if (!granted) {
            visionAiAssistant.onMicPermissionDenied()
        }
    }

    private fun positionAiPanelNearLookDirection() {
        val head = gyroPoseProvider.headPosition
        val dirX = gazeController.currentGazeDirX
        val dirY = gazeController.currentGazeDirY
        val dirZ = gazeController.currentGazeDirZ

        val resolved = GazeController.computeSnappedOrUserFacingPose(
            rawX = head.x + dirX * 1.85f,
            rawY = head.y + dirY * 1.85f + 0.15f,
            rawZ = head.z + dirZ * 1.85f,
            headX = head.x,
            headY = head.y,
            headZ = head.z,
            isVirtualRoomMode = settings.value.isVirtualRoomMode,
            roomBounds = roomBounds
        )
        updateSingleWindow("ai_vision") { w ->
            w.copy(
                posX = resolved.posX,
                posY = resolved.posY,
                posZ = resolved.posZ,
                yawDeg = resolved.yawDeg,
                pitchDeg = resolved.pitchDeg,
                isWallSnapped = resolved.isWallSnapped,
                isOpen = true
            )
        }
    }

    fun handleGazeButtonCommand(buttonId: String) {
        val s = settings.value
        if (buttonId.startsWith("win:")) {
            val parts = buttonId.split(":", limit = 3)
            if (parts.size == 3) {
                val winId = parts[1]
                val action = parts[2]
                handleWindowAction(winId, action, s)
                return
            }
        }

        when (buttonId) {
            // AI Vision panel buttons
            "ai_follow_up" -> requestAiFollowUp()
            "ai_new_capture" -> requestWhatAmISeeing()
            "ai_describe_quick" -> {
                visionAiAssistant.triggerExplicitQuickPrompt(
                    prompt = "What am I seeing? Describe the main objects or scene in front of me.",
                    useCachedImageIfAvailable = false,
                    isCameraActive = cameraTelemetry.value.isActive
                )
            }
            "ai_toggle_tts" -> visionAiAssistant.toggleSpeakOrRepeat()

            // Video playback controls
            "vid_play_pause" -> videoController.togglePlayPause()
            "vid_back_10" -> videoController.seekByMs(-10_000L)
            "vid_fwd_10" -> videoController.seekByMs(10_000L)

            // Global optics, gaze, teleport & hand tracking settings
            "set_ipd_minus" -> setIpdOffset(s.ipdOffset - 0.01f)
            "set_ipd_plus" -> setIpdOffset(s.ipdOffset + 0.01f)
            "set_dist_minus" -> setDistortion(s.distortionK1 - 0.03f)
            "set_dist_plus" -> setDistortion(s.distortionK1 + 0.03f)
            "set_scale_minus" -> setLensScale(s.lensScale - 0.03f)
            "set_scale_plus" -> setLensScale(s.lensScale + 0.03f)
            "set_gaze_minus" -> setGazeDuration(s.gazeDurationSec - 0.2f)
            "set_gaze_plus" -> setGazeDuration(s.gazeDurationSec + 0.2f)
            "set_hand_toggle" -> setHandTrackingEnabled(!s.handTrackingEnabled)
            "set_teleport_toggle" -> setFloorTeleportEnabled(!s.floorTeleportEnabled)
        }
    }

    private fun handleWindowAction(winId: String, action: String, s: VrSettings) {
        when (action) {
            "toggle_room" -> setVirtualRoomMode(!s.isVirtualRoomMode)
            "new_window" -> spawnNewNoteWindow()
            "toggle_video" -> {
                val vidWin = s.windows.firstOrNull { it.kind == WindowKind.VIDEO }
                val nextOpen = !(vidWin?.isOpen ?: true)
                if (vidWin != null) {
                    updateSingleWindow(vidWin.id) { it.copy(isOpen = nextOpen) }
                }
                if (nextOpen) videoController.play() else videoController.pause()
            }
            "toggle_notes" -> {
                val notesWin = s.windows.firstOrNull { it.id == "notes" }
                    ?: s.windows.firstOrNull { it.kind == WindowKind.NOTES }
                if (notesWin != null) {
                    updateSingleWindow(notesWin.id) { it.copy(isOpen = !it.isOpen) }
                } else {
                    spawnNewNoteWindow()
                }
            }
            "toggle_settings" -> {
                val setWin = s.windows.firstOrNull { it.kind == WindowKind.SETTINGS }
                if (setWin != null) {
                    updateSingleWindow(setWin.id) { it.copy(isOpen = !it.isOpen) }
                }
            }
            "what_am_i_seeing" -> requestWhatAmISeeing()
            "snap_left" -> gyroPoseProvider.snapTurn(30f)
            "snap_right" -> gyroPoseProvider.snapTurn(-30f)
            "recenter" -> recenterYaw()
            "reset_layout" -> {
                _placingWindowId.value = null
                gyroPoseProvider.recenter()
                _headPosition.value = gyroPoseProvider.headPosition
                viewModelScope.launch { repository.resetAllPanelPlacements() }
            }
            "exit" -> {
                _placingWindowId.value = null
                videoController.pause()
                visionAiAssistant.pauseAndCancelTransientOperations()
                navigateTo(AppScreen.HOME_2D)
            }
            "place" -> {
                _placingWindowId.value = if (_placingWindowId.value == winId) null else winId
            }
            "scale_minus" -> {
                updateSingleWindow(winId) { it.copy(scale = (it.scale - 0.15f).coerceIn(0.55f, 2.20f)) }
            }
            "scale_plus" -> {
                updateSingleWindow(winId) { it.copy(scale = (it.scale + 0.15f).coerceIn(0.55f, 2.20f)) }
            }
            "close" -> {
                if (winId == "ai_vision") {
                    visionAiAssistant.closePanel()
                    updateSingleWindow(winId) { it.copy(isOpen = false) }
                } else if (winId == "video") {
                    videoController.pause()
                    updateSingleWindow(winId) { it.copy(isOpen = false) }
                } else if (winId.startsWith("note_")) {
                    viewModelScope.launch {
                        repository.saveWindows(s.windows.filterNot { it.id == winId })
                    }
                } else {
                    updateSingleWindow(winId) { it.copy(isOpen = false) }
                }
            }
            "snap_wall" -> {
                val w = s.windows.firstOrNull { it.id == winId } ?: return
                val wallInset = 0.12f
                val limitX = roomBounds.halfWidth - wallInset
                val limitZ = roomBounds.halfDepth - wallInset
                val distBack = abs(w.posZ - (-limitZ))
                val distFront = abs(limitZ - w.posZ)
                val distLeft = abs(w.posX - (-limitX))
                val distRight = abs(limitX - w.posX)
                val minD = minOf(distBack, distFront, distLeft, distRight)
                val snapped = when (minD) {
                    distBack -> w.copy(posZ = -limitZ, yawDeg = 0f, pitchDeg = 0f, isWallSnapped = true)
                    distFront -> w.copy(posZ = limitZ, yawDeg = 180f, pitchDeg = 0f, isWallSnapped = true)
                    distLeft -> w.copy(posX = -limitX, yawDeg = -90f, pitchDeg = 0f, isWallSnapped = true)
                    else -> w.copy(posX = limitX, yawDeg = 90f, pitchDeg = 0f, isWallSnapped = true)
                }
                updateSingleWindow(winId) { snapped }
            }
            "note_next" -> {
                val w = s.windows.firstOrNull { it.id == winId } ?: return
                val idx = NOTE_TEMPLATES.indexOf(w.noteText)
                val nextText = NOTE_TEMPLATES[(idx + 1).coerceAtLeast(0) % NOTE_TEMPLATES.size]
                updateSingleWindow(winId) { it.copy(noteText = nextText) }
            }
            "note_clear" -> {
                updateSingleWindow(winId) { it.copy(noteText = "") }
            }
        }
    }

    fun onLifecycleResume() {
        poseProvider.start()
        batteryMonitor.start()
    }

    fun onLifecyclePause() {
        poseProvider.stop()
        batteryMonitor.stop()
        cameraManager.release()
        videoController.pause()
        visionAiAssistant.pauseAndCancelTransientOperations()
    }

    override fun onCleared() {
        super.onCleared()
        poseProvider.stop()
        batteryMonitor.stop()
        cameraManager.release()
        cameraManager.shutdownExecutor()
        videoController.release()
        handTrackingManager.release()
        visionAiAssistant.release()
        depthEstimator.close()
    }

    companion object {
        private val NOTE_TEMPLATES = listOf(
            VrSettings.DEFAULT_NOTE_SAMPLE,
            "Daily Spatial Workspace Checklist\n1. Review Cinema Stream at Left Desk\n2. Pin Notes to nearest Room Wall\n3. Walk with Left Stick to inspect depth parallax\n4. Press Vol- for AI Vision scene scan",
            "6DoF Architecture Status\n• Active: GyroPoseProvider (3DoF + Locomotion)\n• Stubs ready: ArCorePoseProvider & RemoteSlamPoseProvider\n• Room: 6.4m x 6.4m x 3.0m Low-Poly GLES2",
            "Pinch & Resize Cheat Sheet\n• 1-Hand Pinch: Drag window in world space\n• Release near wall: Auto-snap flush to wall\n• 2-Hand Pinch Spread: Scale window smoothly"
        )
    }
}
