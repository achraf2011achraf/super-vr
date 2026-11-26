package com.example.vr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.data.SpatialWindowState
import com.example.data.VrSettings
import com.example.data.WindowKind
import com.example.vr.ai.VisionAiState
import com.example.vr.hand.HandLandmarkPoint
import com.example.vr.hand.HandTrackingManager
import com.example.vr.hand.HandTrackingState
import com.example.vr.pose.PoseProvider
import com.example.vr.pose.RoomBounds
import com.example.vr.pose.Vec3
import com.example.vr.room.VirtualRoomScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class StereoVrRenderer(
    private val poseProvider: PoseProvider,
    private val roomBounds: RoomBounds,
    private val gazeController: GazeController,
    private val onCameraSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val onVideoSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val onStepLocomotion: (Float) -> Unit,
    private val onGazeButtonCommand: (String) -> Unit,
    private val onPlaceWindowAtGaze: (String, Vec3, Float, Float, Float) -> Unit,
    private val onWindowTransformed: (String, Float, Float, Float, Float, Float, Float, Boolean, Boolean) -> Unit,
    private val onTeleportFloor: (Float, Float) -> Unit,
    private val onFpsMeasured: (Int) -> Unit,
    private val onTelemetrySample: (Vec3, String) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var currentSettings: VrSettings = VrSettings()

    @Volatile
    var currentBattery: BatteryTelemetry = BatteryTelemetry()

    @Volatile
    var currentVideoState: VideoPlayerState = VideoPlayerState()

    @Volatile
    var currentHandState: HandTrackingState = HandTrackingState()

    @Volatile
    var currentAiState: VisionAiState = VisionAiState()

    @Volatile
    var isCameraActive: Boolean = false

    @Volatile
    var cameraWidth: Int = 1280

    @Volatile
    var cameraHeight: Int = 720

    @Volatile
    var displayRotation: Int = android.view.Surface.ROTATION_90

    @Volatile
    var placingWindowId: String? = null

    private var surfaceWidth: Int = 1920
    private var surfaceHeight: Int = 1080

    // FBO for single-eye offscreen rendering before lens barrel distortion
    private var eyeFboId: Int = 0
    private var eyeColorTexId: Int = 0
    private var eyeDepthRbId: Int = 0
    private var eyeFboWidth: Int = 960
    private var eyeFboHeight: Int = 1080

    // External OES textures for CameraX passthrough and Media3 video player
    private var cameraOesTexId: Int = 0
    private var videoOesTexId: Int = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var videoSurfaceTexture: SurfaceTexture? = null

    @Volatile
    private var cameraFrameAvailable: Boolean = false

    @Volatile
    private var videoFrameAvailable: Boolean = false

    private val cameraTexMatrix = FloatArray(16)
    private val videoTexMatrix = FloatArray(16)

    // Virtual Room 3D environment
    private val virtualRoomScene = VirtualRoomScene(roomBounds)

    // Shader programs
    private var passthroughProgram = 0
    private var quad2dProgram = 0
    private var videoQuadProgram = 0
    private var distortionProgram = 0
    private var solidColorProgram = 0

    // Geometry buffers
    private lateinit var unitQuadVertexBuffer: FloatBuffer
    private lateinit var unitQuadUvBuffer: FloatBuffer
    private lateinit var fullScreenQuadBuffer: FloatBuffer
    private lateinit var reticleRingBuffer: FloatBuffer
    private lateinit var handBoneVertexBuffer: FloatBuffer

    // Matrices
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val eyeViewMatrix = FloatArray(16)
    private val convergedView = FloatArray(16)
    private val toeInMat = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val videoSubQuadMatrix = FloatArray(16)
    private val reticleModelMatrix = FloatArray(16)
    private val handOrthoMatrix = FloatArray(16)

    // Dynamic world-space windows + warning/HUD badges
    private val windowPanels = LinkedHashMap<String, SpatialPanel>()
    private val renderPanelList = mutableListOf<SpatialPanel>()
    private lateinit var warningPanel: SpatialPanel
    private lateinit var aiHudBadgePanel: SpatialPanel

    // Transient live transform override while user pinches/resizes a window
    private var liveOverrideWindowId: String? = null
    private var livePosX: Float = 0f
    private var livePosY: Float = 1.6f
    private var livePosZ: Float = -2.0f
    private var liveYawDeg: Float = 0f
    private var livePitchDeg: Float = 0f
    private var liveScale: Float = 1.0f
    private var liveWallSnapped: Boolean = false

    // FPS & UI dirty tracking
    private var frameCount = 0
    private var lastFrameNs = System.nanoTime()
    private var lastFpsTimestampNs = System.nanoTime()
    private var lastCanvasRedrawNs = 0L
    private var lastTelemetryPushNs = 0L
    private var lastHoveredButtonId: String? = null
    private var lastRenderedAiState: VisionAiState? = null
    private var lastWindowCount: Int = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.04f, 0.08f, 1.0f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        initGeometryBuffers()
        initShaderPrograms()
        virtualRoomScene.initGl()
        initAuxiliaryPanels()

        cameraOesTexId = createOesTexture()
        videoOesTexId = createOesTexture()

        Matrix.setIdentityM(cameraTexMatrix, 0)
        Matrix.setIdentityM(videoTexMatrix, 0)

        val camSt = SurfaceTexture(cameraOesTexId).apply {
            setDefaultBufferSize(1280, 720)
            setOnFrameAvailableListener { cameraFrameAvailable = true }
        }
        cameraSurfaceTexture = camSt
        onCameraSurfaceTextureReady(camSt)

        val vidSt = SurfaceTexture(videoOesTexId).apply {
            setDefaultBufferSize(1280, 720)
            setOnFrameAvailableListener { videoFrameAvailable = true }
        }
        videoSurfaceTexture = vidSt
        onVideoSurfaceTextureReady(vidSt)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(2)
        surfaceHeight = height.coerceAtLeast(2)

        eyeFboWidth = (surfaceWidth / 2).coerceAtLeast(1)
        eyeFboHeight = surfaceHeight

        setupEyeFramebuffer(eyeFboWidth, eyeFboHeight)

        val eyeAspect = eyeFboWidth.toFloat() / eyeFboHeight.toFloat()
        Matrix.perspectiveM(projMatrix, 0, VERTICAL_FOV_DEG, eyeAspect, 0.08f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        lastFrameNs = nowNs

        // Measure FPS every second
        frameCount++
        if (nowNs - lastFpsTimestampNs >= 1_000_000_000L) {
            onFpsMeasured(frameCount)
            frameCount = 0
            lastFpsTimestampNs = nowNs
        }

        // Update OES SurfaceTextures on GL thread
        if (cameraFrameAvailable) {
            cameraFrameAvailable = false
            try {
                cameraSurfaceTexture?.updateTexImage()
                cameraSurfaceTexture?.getTransformMatrix(cameraTexMatrix)
            } catch (_: Exception) {
            }
        }
        if (videoFrameAvailable) {
            videoFrameAvailable = false
            try {
                videoSurfaceTexture?.updateTexImage()
                videoSurfaceTexture?.getTransformMatrix(videoTexMatrix)
            } catch (_: Exception) {
            }
        }

        val settings = currentSettings
        val battery = currentBattery
        val videoState = currentVideoState
        val handState = currentHandState
        val aiState = currentAiState
        val activePlacingId = placingWindowId

        // 1. Step gamepad / walking locomotion and fetch 6DoF PoseProvider view matrix
        onStepLocomotion(dtSec)
        poseProvider.getViewMatrix(viewMatrix)
        val headPos = poseProvider.headPosition

        // 2. Sync dynamic world-coordinate window panels with settings
        syncPanelsWithSettings(settings, headPos, aiState, battery, activePlacingId)

        for (panel in renderPanelList) {
            if (panel.isVisible) {
                panel.updateTransform()
            }
        }

        val eyeAspect = eyeFboWidth.toFloat() / eyeFboHeight.toFloat().coerceAtLeast(1f)

        // 3. Run 6DoF raycasting for 2-hand resize, 1-hand pinch grab, floor teleport, and gaze dwell
        gazeController.update(
            viewMatrix = viewMatrix,
            eyeAspect = eyeAspect,
            verticalFovDeg = VERTICAL_FOV_DEG,
            panels = renderPanelList,
            gazeDurationSec = settings.gazeDurationSec,
            placingWindowId = activePlacingId,
            handState = handState,
            isVirtualRoomMode = settings.isVirtualRoomMode,
            floorTeleportEnabled = settings.floorTeleportEnabled,
            roomBounds = roomBounds,
            nowNs = nowNs,
            onButtonClicked = { btnId -> onGazeButtonCommand(btnId) },
            onPlaceWindowAtGaze = { windowId, dirX, dirY, dirZ ->
                onPlaceWindowAtGaze(windowId, headPos, dirX, dirY, dirZ)
            },
            onWindowTransformed = { windowId, posX, posY, posZ, yawDeg, pitchDeg, scale, isWallSnapped, isFinalRelease ->
                if (isFinalRelease) {
                    liveOverrideWindowId = null
                } else {
                    liveOverrideWindowId = windowId
                    livePosX = posX
                    livePosY = posY
                    livePosZ = posZ
                    liveYawDeg = yawDeg
                    livePitchDeg = pitchDeg
                    liveScale = scale
                    liveWallSnapped = isWallSnapped
                }
                onWindowTransformed(windowId, posX, posY, posZ, yawDeg, pitchDeg, scale, isWallSnapped, isFinalRelease)
            },
            onTeleportFloor = { tx, tz ->
                onTeleportFloor(tx, tz)
            }
        )

        if (nowNs - lastTelemetryPushNs >= 100_000_000L) {
            lastTelemetryPushNs = nowNs
            onTelemetrySample(headPos, gazeController.activeInputSource)
        }

        val hoveredId = gazeController.activeButtonId
        val dwellProg = gazeController.dwellProgress
        val highlightedWindowId = gazeController.resizingWindowId
            ?: gazeController.grabbedWindowId
            ?: activePlacingId

        // 4. Redraw panel canvases when hover/state changes or at ~12Hz
        val shouldRedrawPanels =
            hoveredId != lastHoveredButtonId ||
                aiState != lastRenderedAiState ||
                settings.windows.size != lastWindowCount ||
                (nowNs - lastCanvasRedrawNs) > 80_000_000L

        if (shouldRedrawPanels) {
            lastHoveredButtonId = hoveredId
            lastRenderedAiState = aiState
            lastWindowCount = settings.windows.size
            lastCanvasRedrawNs = nowNs

            val videoOpen = settings.windows.any { it.kind == WindowKind.VIDEO && it.isOpen }
            val notesOpen = settings.windows.any { it.kind == WindowKind.NOTES && it.isOpen }
            val settingsOpen = settings.windows.any { it.kind == WindowKind.SETTINGS && it.isOpen }

            for (panel in windowPanels.values) {
                if (!panel.isVisible) continue
                when (panel.kind) {
                    WindowKind.HOME -> SpatialPanelCanvasRenderer.renderHomePanel(
                        panel = panel,
                        hoveredButtonId = hoveredId,
                        hoverProgress = dwellProg,
                        isVirtualRoom = settings.isVirtualRoomMode,
                        videoOpen = videoOpen,
                        notesOpen = notesOpen,
                        settingsOpen = settingsOpen,
                        aiState = aiState,
                        headPos = headPos,
                        placingWindowId = highlightedWindowId,
                        battery = battery
                    )
                    WindowKind.VIDEO -> SpatialPanelCanvasRenderer.renderVideoControlOverlay(
                        panel = panel,
                        videoState = videoState,
                        placingWindowId = highlightedWindowId,
                        hoveredButtonId = hoveredId,
                        hoverProgress = dwellProg
                    )
                    WindowKind.NOTES -> SpatialPanelCanvasRenderer.renderNotesPanel(
                        panel = panel,
                        placingWindowId = highlightedWindowId,
                        hoveredButtonId = hoveredId,
                        hoverProgress = dwellProg
                    )
                    WindowKind.SETTINGS -> SpatialPanelCanvasRenderer.renderSettingsPanel(
                        panel = panel,
                        settings = settings,
                        placingWindowId = highlightedWindowId,
                        hoveredButtonId = hoveredId,
                        hoverProgress = dwellProg
                    )
                    WindowKind.AI_VISION -> SpatialPanelCanvasRenderer.renderAiVisionPanel(
                        panel = panel,
                        aiState = aiState,
                        placingWindowId = highlightedWindowId,
                        hoveredButtonId = hoveredId,
                        hoverProgress = dwellProg
                    )
                }
            }

            if (warningPanel.isVisible) {
                SpatialPanelCanvasRenderer.renderWarningPanel(warningPanel, battery)
            }
            if (aiState.isListening || aiState.isAiCameraActive) {
                SpatialPanelCanvasRenderer.renderAiHudBadge(aiHudBadgePanel, aiState)
            }
        }

        for (panel in renderPanelList) {
            if (panel.isVisible) {
                panel.uploadTextureIfNeeded()
            }
        }
        if (aiState.isListening || aiState.isAiCameraActive) {
            aiHudBadgePanel.uploadTextureIfNeeded()
        }

        // 5. Render Left Eye (-1) and Right Eye (+1) with stereo convergence & parallax
        renderEye(eyeSign = -1f, settings = settings, handState = handState, aiState = aiState, dtSec = dtSec)
        renderEye(eyeSign = 1f, settings = settings, handState = handState, aiState = aiState, dtSec = dtSec)
    }

    private fun syncPanelsWithSettings(
        settings: VrSettings,
        headPos: Vec3,
        aiState: VisionAiState,
        battery: BatteryTelemetry,
        activePlacingId: String?
    ) {
        val activeIds = HashSet<String>(settings.windows.size)
        for (w in settings.windows) {
            activeIds.add(w.id)
            val panel = windowPanels.getOrPut(w.id) { createSpatialPanelForWindow(w) }

            when {
                liveOverrideWindowId == w.id -> {
                    panel.posX = livePosX
                    panel.posY = livePosY
                    panel.posZ = livePosZ
                    panel.yawAngleDeg = liveYawDeg
                    panel.pitchAngleDeg = livePitchDeg
                    panel.panelScale = liveScale
                    panel.isWallSnapped = liveWallSnapped
                    panel.isVisible = if (w.kind == WindowKind.AI_VISION) aiState.isPanelOpen else w.isOpen
                    panel.title = w.title
                    panel.noteText = w.noteText
                }
                activePlacingId == w.id -> {
                    // Live preview 1.85m in front of user's gaze inside room bounds
                    val rawX = headPos.x + gazeController.currentGazeDirX * 1.85f
                    val rawY = headPos.y + gazeController.currentGazeDirY * 1.85f
                    val rawZ = headPos.z + gazeController.currentGazeDirZ * 1.85f
                    val resolved = GazeController.computeSnappedOrUserFacingPose(
                        rawX = rawX,
                        rawY = rawY,
                        rawZ = rawZ,
                        headX = headPos.x,
                        headY = headPos.y,
                        headZ = headPos.z,
                        isVirtualRoomMode = settings.isVirtualRoomMode,
                        roomBounds = roomBounds
                    )
                    panel.posX = resolved.posX
                    panel.posY = resolved.posY
                    panel.posZ = resolved.posZ
                    panel.yawAngleDeg = resolved.yawDeg
                    panel.pitchAngleDeg = resolved.pitchDeg
                    panel.isWallSnapped = resolved.isWallSnapped
                    panel.panelScale = w.scale
                    panel.isVisible = true
                    panel.title = w.title
                    panel.noteText = w.noteText
                }
                else -> {
                    panel.applyState(w)
                    if (w.kind == WindowKind.AI_VISION) {
                        panel.isVisible = aiState.isPanelOpen
                    }
                }
            }
        }

        // Remove any deleted windows cleanly
        val iterator = windowPanels.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!activeIds.contains(entry.key)) {
                entry.value.releaseGlTexture()
                iterator.remove()
            }
        }

        val home = windowPanels["home"]
        warningPanel.isVisible = battery.hasWarning
        if (home != null) {
            warningPanel.posX = home.posX
            warningPanel.posY = (home.posY + 0.62f).coerceAtMost(roomBounds.ceilingHeight - 0.25f)
            warningPanel.posZ = home.posZ
            warningPanel.yawAngleDeg = home.yawAngleDeg
            warningPanel.pitchAngleDeg = home.pitchAngleDeg
            warningPanel.panelScale = 1.0f
        }

        renderPanelList.clear()
        renderPanelList.addAll(windowPanels.values)
        renderPanelList.add(warningPanel)
    }

    private fun createSpatialPanelForWindow(w: SpatialWindowState): SpatialPanel {
        val (bw, bh, mw, mh) = when (w.kind) {
            WindowKind.HOME -> QuadSpec(820, 510, 1.32f, 0.82f)
            WindowKind.VIDEO -> QuadSpec(840, 530, 1.48f, 0.93f)
            WindowKind.NOTES -> QuadSpec(820, 520, 1.34f, 0.85f)
            WindowKind.SETTINGS -> QuadSpec(800, 530, 1.36f, 0.90f)
            WindowKind.AI_VISION -> QuadSpec(840, 520, 1.44f, 0.89f)
        }
        return SpatialPanel(
            id = w.id,
            kind = w.kind,
            bitmapWidth = bw,
            bitmapHeight = bh,
            baseWidthMeters = mw,
            baseHeightMeters = mh,
            posX = w.posX,
            posY = w.posY,
            posZ = w.posZ,
            yawAngleDeg = w.yawDeg,
            pitchAngleDeg = w.pitchDeg,
            panelScale = w.scale,
            isVisible = w.isOpen,
            isWallSnapped = w.isWallSnapped,
            title = w.title,
            noteText = w.noteText
        )
    }

    private data class QuadSpec(
        val bw: Int,
        val bh: Int,
        val mw: Float,
        val mh: Float
    )

    private fun renderEye(
        eyeSign: Float,
        settings: VrSettings,
        handState: HandTrackingState,
        aiState: VisionAiState,
        dtSec: Float
    ) {
        // 1. Bind offscreen eye FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, eyeFboId)
        GLES20.glViewport(0, 0, eyeFboWidth, eyeFboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 2. Compute converged per-eye 6DoF View-Projection matrix (parallax works natively from headPosition!)
        val halfIpd = settings.halfIpdMeters
        val convergenceDist = settings.panelDistanceMeters.coerceIn(0.8f, 4.0f)
        val toeInAngleDeg = Math.toDegrees(atan2(halfIpd.toDouble(), convergenceDist.toDouble())).toFloat()

        Matrix.setRotateM(toeInMat, 0, eyeSign * toeInAngleDeg, 0f, 1f, 0f)
        System.arraycopy(viewMatrix, 0, eyeViewMatrix, 0, 16)
        eyeViewMatrix[12] += -eyeSign * halfIpd
        Matrix.multiplyMM(convergedView, 0, toeInMat, 0, eyeViewMatrix, 0)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, convergedView, 0)

        // 3. Render either the 3D Low-Poly Virtual Room OR CameraX Passthrough background
        val (cropScaleX, cropScaleY) = computeCoverFitScales()
        if (settings.isVirtualRoomMode) {
            virtualRoomScene.draw(vpMatrix, dtSec)
            val tpTarget = gazeController.floorTeleportTarget
            if (tpTarget != null) {
                virtualRoomScene.drawFloorTeleportMarker(
                    vpMatrix = vpMatrix,
                    targetX = tpTarget.first,
                    targetZ = tpTarget.second,
                    dwellProgress = gazeController.floorTeleportProgress
                )
            }
        } else {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            drawCameraPassthroughBackground(cropScaleX, cropScaleY)
        }

        // 4. Render floating 3D world-locked panels
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        for (panel in renderPanelList) {
            if (!panel.isVisible) continue
            drawSpatialPanel(panel)
            if (panel.kind == WindowKind.VIDEO) {
                drawVideoStreamSubQuad(panel)
            }
        }

        // 5. Draw 21-landmark Hand Skeleton & Bones overlay (both primary & optional 2nd hand for resize)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        if (handState.isEnabled && handState.isHandDetected && handState.landmarks.size == 21) {
            drawHandSkeletonOverlay(
                landmarks = handState.landmarks,
                isPinching = handState.isPinching,
                isGrabbingOrResizing = gazeController.grabbedWindowId != null || gazeController.resizingWindowId != null,
                cropScaleX = cropScaleX,
                cropScaleY = cropScaleY
            )
            if (handState.secondaryLandmarks.size == 21) {
                drawHandSkeletonOverlay(
                    landmarks = handState.secondaryLandmarks,
                    isPinching = handState.isSecondaryPinching,
                    isGrabbingOrResizing = gazeController.resizingWindowId != null,
                    cropScaleX = cropScaleX,
                    cropScaleY = cropScaleY
                )
            }
        }

        // 6. Draw head-locked stereo AI status badge ("LISTENING..." / "AI CAMERA ACTIVE")
        if (aiState.isListening || aiState.isAiCameraActive) {
            drawHeadLockedHudBadge(aiHudBadgePanel)
        }

        // 7. Draw centered gaze reticle (fallback input + teleport/place indicator)
        drawGazeReticle(
            progress = gazeController.dwellProgress,
            isHovering = gazeController.isHoveringPanel || gazeController.floorTeleportTarget != null,
            isPlacingMode = placingWindowId != null
        )
        GLES20.glDisable(GLES20.GL_BLEND)

        // 8. Present FBO into side-by-side stereo viewports with lens barrel distortion
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val viewportX = if (eyeSign < 0f) 0 else eyeFboWidth
        GLES20.glViewport(viewportX, 0, eyeFboWidth, eyeFboHeight)
        if (eyeSign < 0f) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        drawBarrelDistortedEye(eyeSign, settings)
    }

    private fun drawHandSkeletonOverlay(
        landmarks: List<HandLandmarkPoint>,
        isPinching: Boolean,
        isGrabbingOrResizing: Boolean,
        cropScaleX: Float,
        cropScaleY: Float
    ) {
        GLES20.glUseProgram(solidColorProgram)

        val posLoc = GLES20.glGetAttribLocation(solidColorProgram, "aPosition")
        val mvpLoc = GLES20.glGetUniformLocation(solidColorProgram, "uMVPMatrix")
        val colorLoc = GLES20.glGetUniformLocation(solidColorProgram, "uColor")

        val safeScaleX = cropScaleX.coerceAtLeast(0.05f)
        val safeScaleY = cropScaleY.coerceAtLeast(0.05f)

        fun toNdcX(normX: Float): Float = ((normX - 0.5f) / safeScaleX) * 2.0f
        fun toNdcY(normY: Float): Float = ((0.5f - normY) / safeScaleY) * 2.0f

        val connections = HandTrackingManager.HAND_BONE_CONNECTIONS
        handBoneVertexBuffer.position(0)
        for (i in connections.indices) {
            val pt = landmarks[connections[i]]
            handBoneVertexBuffer.put(toNdcX(pt.x))
            handBoneVertexBuffer.put(toNdcY(pt.y))
            handBoneVertexBuffer.put(0f)
        }
        handBoneVertexBuffer.position(0)

        Matrix.setIdentityM(handOrthoMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, handOrthoMatrix, 0)

        if (isPinching || isGrabbingOrResizing) {
            GLES20.glUniform4f(colorLoc, 0.0f, 1.0f, 0.46f, 0.92f)
        } else {
            GLES20.glUniform4f(colorLoc, 0.0f, 0.90f, 1.0f, 0.85f)
        }

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, handBoneVertexBuffer)
        GLES20.glLineWidth(4.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, connections.size)

        val aspect = eyeFboWidth.toFloat() / eyeFboHeight.toFloat().coerceAtLeast(1f)
        unitQuadVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, unitQuadVertexBuffer)

        for (idx in landmarks.indices) {
            val pt = landmarks[idx]
            val nx = toNdcX(pt.x)
            val ny = toNdcY(pt.y)
            val isPinchFingerTip = (idx == 4 || idx == 8)

            Matrix.setIdentityM(handOrthoMatrix, 0)
            Matrix.translateM(handOrthoMatrix, 0, nx, ny, 0f)
            val jointSize = if (isPinchFingerTip) 0.032f else 0.020f
            Matrix.scaleM(handOrthoMatrix, 0, jointSize / aspect, jointSize, 1f)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, handOrthoMatrix, 0)

            if (isPinchFingerTip) {
                if (isPinching) {
                    GLES20.glUniform4f(colorLoc, 0.0f, 1.0f, 0.46f, 1.0f)
                } else {
                    GLES20.glUniform4f(colorLoc, 1.0f, 0.67f, 0.0f, 1.0f)
                }
            } else {
                GLES20.glUniform4f(colorLoc, 1.0f, 1.0f, 1.0f, 0.95f)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val midX = toNdcX((thumbTip.x + indexTip.x) * 0.5f)
        val midY = toNdcY((thumbTip.y + indexTip.y) * 0.5f)

        Matrix.setIdentityM(handOrthoMatrix, 0)
        Matrix.translateM(handOrthoMatrix, 0, midX, midY, 0f)
        val ringRadius = if (isPinching) 0.045f else 0.034f
        Matrix.scaleM(handOrthoMatrix, 0, ringRadius / aspect, ringRadius, 1f)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, handOrthoMatrix, 0)

        if (isPinching) {
            GLES20.glUniform4f(colorLoc, 0.0f, 1.0f, 0.46f, 1.0f)
        } else {
            GLES20.glUniform4f(colorLoc, 0.0f, 0.90f, 1.0f, 0.85f)
        }

        reticleRingBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, reticleRingBuffer)
        GLES20.glLineWidth(if (isPinching) 5.5f else 3.0f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, RETICLE_SEGMENTS)

        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun computeCoverFitScales(): Pair<Float, Float> {
        val camW = maxOf(cameraWidth, cameraHeight).coerceAtLeast(1).toFloat()
        val camH = minOf(cameraWidth, cameraHeight).coerceAtLeast(1).toFloat()
        val cameraAspect = camW / camH
        val eyeAspect = eyeFboWidth.toFloat() / eyeFboHeight.toFloat().coerceAtLeast(1f)
        return if (eyeAspect > cameraAspect) {
            Pair(1.0f, cameraAspect / eyeAspect)
        } else {
            Pair(eyeAspect / cameraAspect, 1.0f)
        }
    }

    private fun drawCameraPassthroughBackground(scaleX: Float, scaleY: Float) {
        if (!isCameraActive) {
            drawFallbackGridBackground()
            return
        }

        GLES20.glUseProgram(passthroughProgram)

        val posLoc = GLES20.glGetAttribLocation(passthroughProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(passthroughProgram, "aTexCoord")
        val texMatLoc = GLES20.glGetUniformLocation(passthroughProgram, "uTexMatrix")
        val cropScaleLoc = GLES20.glGetUniformLocation(passthroughProgram, "uCropScale")
        val rotRadLoc = GLES20.glGetUniformLocation(passthroughProgram, "uExtraRotRad")
        val texLoc = GLES20.glGetUniformLocation(passthroughProgram, "uCameraTex")

        val baseDisplayAngleDeg = when (displayRotation) {
            android.view.Surface.ROTATION_90 -> -90f
            android.view.Surface.ROTATION_270 -> 90f
            android.view.Surface.ROTATION_180 -> 180f
            else -> 0f
        }
        val totalAngleDeg = baseDisplayAngleDeg + currentSettings.cameraRotationOffsetDeg.toFloat()
        val rotRad = Math.toRadians(totalAngleDeg.toDouble()).toFloat()

        GLES20.glUniform2f(cropScaleLoc, scaleX, scaleY)
        GLES20.glUniform1f(rotRadLoc, rotRad)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, cameraTexMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTexId)
        GLES20.glUniform1i(texLoc, 0)

        unitQuadVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, unitQuadVertexBuffer)

        unitQuadUvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, unitQuadUvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun drawFallbackGridBackground() {
        GLES20.glUseProgram(solidColorProgram)
        val posLoc = GLES20.glGetAttribLocation(solidColorProgram, "aPosition")
        val mvpLoc = GLES20.glGetUniformLocation(solidColorProgram, "uMVPMatrix")
        val colorLoc = GLES20.glGetUniformLocation(solidColorProgram, "uColor")

        val identity = FloatArray(16)
        Matrix.setIdentityM(identity, 0)
        Matrix.scaleM(identity, 0, 2f, 2f, 1f)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, identity, 0)
        GLES20.glUniform4f(colorLoc, 0.05f, 0.08f, 0.14f, 1.0f)

        unitQuadVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, unitQuadVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun drawSpatialPanel(panel: SpatialPanel) {
        GLES20.glUseProgram(quad2dProgram)

        val posLoc = GLES20.glGetAttribLocation(quad2dProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(quad2dProgram, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(quad2dProgram, "uMVPMatrix")
        val texLoc = GLES20.glGetUniformLocation(quad2dProgram, "uTexture")

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, panel.modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panel.glTextureId)
        GLES20.glUniform1i(texLoc, 0)

        unitQuadVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, unitQuadVertexBuffer)

        unitQuadUvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, unitQuadUvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun drawHeadLockedHudBadge(badgePanel: SpatialPanel) {
        GLES20.glUseProgram(quad2dProgram)

        val posLoc = GLES20.glGetAttribLocation(quad2dProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(quad2dProgram, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(quad2dProgram, "uMVPMatrix")
        val texLoc = GLES20.glGetUniformLocation(quad2dProgram, "uTexture")

        val aspect = eyeFboWidth.toFloat() / eyeFboHeight.toFloat().coerceAtLeast(1f)
        val ortho = FloatArray(16)
        Matrix.orthoM(ortho, 0, -aspect, aspect, -1f, 1f, -1f, 1f)
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0.42f, 0f)
        Matrix.scaleM(model, 0, 0.92f, 0.125f, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, ortho, 0, model, 0)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, badgePanel.glTextureId)
        GLES20.glUniform1i(texLoc, 0)

        unitQuadVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, unitQuadVertexBuffer)

        unitQuadUvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, unitQuadUvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun drawVideoStreamSubQuad(videoPanel: SpatialPanel) {
        GLES20.glUseProgram(videoQuadProgram)

        val posLoc = GLES20.glGetAttribLocation(videoQuadProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(videoQuadProgram, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(videoQuadProgram, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(videoQuadProgram, "uTexMatrix")
        val texLoc = GLES20.glGetUniformLocation(videoQuadProgram, "uVideoTex")

        System.arraycopy(videoPanel.modelMatrix, 0, videoSubQuadMatrix, 0, 16)
        Matrix.translateM(videoSubQuadMatrix, 0, 0f, 0.025f, 0.004f)
        Matrix.scaleM(videoSubQuadMatrix, 0, 0.91f, 0.62f, 1f)

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, videoSubQuadMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, videoTexMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoOesTexId)
        GLES20.glUniform1i(texLoc, 0)

        unitQuadVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, unitQuadVertexBuffer)

        unitQuadUvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, unitQuadUvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun drawGazeReticle(progress: Float, isHovering: Boolean, isPlacingMode: Boolean) {
        GLES20.glUseProgram(solidColorProgram)

        val posLoc = GLES20.glGetAttribLocation(solidColorProgram, "aPosition")
        val mvpLoc = GLES20.glGetUniformLocation(solidColorProgram, "uMVPMatrix")
        val colorLoc = GLES20.glGetUniformLocation(solidColorProgram, "uColor")

        val aspect = eyeFboWidth.toFloat() / eyeFboHeight.toFloat()
        val ortho = FloatArray(16)
        Matrix.orthoM(ortho, 0, -aspect, aspect, -1f, 1f, -1f, 1f)

        val ringScale = if (isPlacingMode) 0.052f else if (isHovering) 0.042f else 0.026f
        System.arraycopy(ortho, 0, reticleModelMatrix, 0, 16)
        Matrix.scaleM(reticleModelMatrix, 0, ringScale, ringScale, 1f)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, reticleModelMatrix, 0)
        when {
            isPlacingMode -> GLES20.glUniform4f(colorLoc, 0.0f, 1.0f, 0.46f, 0.98f)
            isHovering -> GLES20.glUniform4f(colorLoc, 0.0f, 0.90f, 1.0f, 0.95f)
            else -> GLES20.glUniform4f(colorLoc, 1.0f, 1.0f, 1.0f, 0.78f)
        }

        reticleRingBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, reticleRingBuffer)
        GLES20.glLineWidth(3f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, RETICLE_SEGMENTS)

        if (progress > 0.01f) {
            System.arraycopy(ortho, 0, reticleModelMatrix, 0, 16)
            val progRadius = if (isPlacingMode) 0.068f else 0.056f
            Matrix.scaleM(reticleModelMatrix, 0, progRadius, progRadius, 1f)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, reticleModelMatrix, 0)
            GLES20.glUniform4f(colorLoc, 0.0f, 1.0f, 0.46f, 0.98f)

            val activeSegments = (RETICLE_SEGMENTS * progress.coerceIn(0f, 1f)).toInt().coerceAtLeast(2)
            GLES20.glLineWidth(6f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, activeSegments)
        }

        System.arraycopy(ortho, 0, reticleModelMatrix, 0, 16)
        Matrix.scaleM(reticleModelMatrix, 0, 0.008f, 0.008f, 1f)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, reticleModelMatrix, 0)
        GLES20.glUniform4f(colorLoc, 0.0f, 0.90f, 1.0f, 1.0f)

        unitQuadVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, unitQuadVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun drawBarrelDistortedEye(eyeSign: Float, settings: VrSettings) {
        GLES20.glUseProgram(distortionProgram)

        val posLoc = GLES20.glGetAttribLocation(distortionProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(distortionProgram, "aTexCoord")
        val k1Loc = GLES20.glGetUniformLocation(distortionProgram, "uDistortionK1")
        val scaleLoc = GLES20.glGetUniformLocation(distortionProgram, "uLensScale")
        val ipdLoc = GLES20.glGetUniformLocation(distortionProgram, "uLensCenterOffset")
        val texLoc = GLES20.glGetUniformLocation(distortionProgram, "uEyeTexture")

        GLES20.glUniform1f(k1Loc, settings.distortionK1)
        GLES20.glUniform1f(scaleLoc, settings.lensScale)
        GLES20.glUniform2f(ipdLoc, eyeSign * settings.ipdOffset * 0.45f, 0.0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, eyeColorTexId)
        GLES20.glUniform1i(texLoc, 0)

        fullScreenQuadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, fullScreenQuadBuffer)

        unitQuadUvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, unitQuadUvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun initAuxiliaryPanels() {
        warningPanel = SpatialPanel(
            id = "warning",
            kind = WindowKind.HOME,
            bitmapWidth = 760,
            bitmapHeight = 140,
            baseWidthMeters = 1.30f,
            baseHeightMeters = 0.25f,
            posX = 0f,
            posY = 2.25f,
            posZ = -2.0f,
            yawAngleDeg = 0f,
            pitchAngleDeg = 12f,
            isVisible = false
        )

        aiHudBadgePanel = SpatialPanel(
            id = "ai_hud_badge",
            kind = WindowKind.AI_VISION,
            bitmapWidth = 580,
            bitmapHeight = 76,
            baseWidthMeters = 0.92f,
            baseHeightMeters = 0.125f,
            posX = 0f,
            posY = 1.6f,
            posZ = -1.5f,
            yawAngleDeg = 0f,
            pitchAngleDeg = 0f,
            isVisible = false
        )
    }

    private fun initGeometryBuffers() {
        val unitVertices = floatArrayOf(
            -0.5f, -0.5f, 0f,
             0.5f, -0.5f, 0f,
            -0.5f,  0.5f, 0f,
             0.5f,  0.5f, 0f
        )
        unitQuadVertexBuffer = createFloatBuffer(unitVertices)

        val fullVertices = floatArrayOf(
            -1.0f, -1.0f, 0f,
             1.0f, -1.0f, 0f,
            -1.0f,  1.0f, 0f,
             1.0f,  1.0f, 0f
        )
        fullScreenQuadBuffer = createFloatBuffer(fullVertices)

        val uvs = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )
        unitQuadUvBuffer = createFloatBuffer(uvs)

        val ringCoords = FloatArray(RETICLE_SEGMENTS * 3)
        for (i in 0 until RETICLE_SEGMENTS) {
            val angle = (Math.PI / 2.0) - (2.0 * Math.PI * i / (RETICLE_SEGMENTS - 1))
            ringCoords[i * 3] = cos(angle).toFloat()
            ringCoords[i * 3 + 1] = sin(angle).toFloat()
            ringCoords[i * 3 + 2] = 0f
        }
        reticleRingBuffer = createFloatBuffer(ringCoords)

        handBoneVertexBuffer = createFloatBuffer(FloatArray(HandTrackingManager.HAND_BONE_CONNECTIONS.size * 3))
    }

    private fun setupEyeFramebuffer(width: Int, height: Int) {
        if (eyeFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(eyeFboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(eyeColorTexId), 0)
            GLES20.glDeleteRenderbuffers(1, intArrayOf(eyeDepthRbId), 0)
        }

        val fbo = IntArray(1)
        val tex = IntArray(1)
        val rb = IntArray(1)

        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glGenRenderbuffers(1, rb, 0)

        eyeFboId = fbo[0]
        eyeColorTexId = tex[0]
        eyeDepthRbId = rb[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, eyeColorTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, eyeDepthRbId)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, eyeFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            eyeColorTexId,
            0
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER,
            eyeDepthRbId
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun createFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(array)
                position(0)
            }
    }

    private fun initShaderPrograms() {
        val passthroughVs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec2 uCropScale;
            uniform float uExtraRotRad;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * 2.0, 0.0, 1.0);
                vec2 uv = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
                vec2 p = (uv - 0.5) * uCropScale;
                float c = cos(uExtraRotRad);
                float s = sin(uExtraRotRad);
                vec2 rotated = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + 0.5;
                rotated = clamp(rotated, 0.01, 0.99);
                vTexCoord = (uTexMatrix * vec4(rotated, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val passthroughFs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCameraTex;
            void main() {
                gl_FragColor = texture2D(uCameraTex, vTexCoord);
            }
        """.trimIndent()
        passthroughProgram = buildProgram(passthroughVs, passthroughFs)

        val quadVs = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val quadFs = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
        quad2dProgram = buildProgram(quadVs, quadFs)

        val videoVs = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vec2 flipped = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
                vTexCoord = (uTexMatrix * vec4(flipped, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val videoFs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uVideoTex;
            void main() {
                gl_FragColor = texture2D(uVideoTex, vTexCoord);
            }
        """.trimIndent()
        videoQuadProgram = buildProgram(videoVs, videoFs)

        val distortionVs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """.trimIndent()

        val distortionFs = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uEyeTexture;
            uniform float uDistortionK1;
            uniform float uLensScale;
            uniform vec2 uLensCenterOffset;
            void main() {
                vec2 lensCenter = vec2(0.5, 0.5) + uLensCenterOffset;
                vec2 p = (vUv - lensCenter) / max(uLensScale, 0.35);
                float r2 = dot(p, p);
                float factor = 1.0 + uDistortionK1 * r2 + (uDistortionK1 * 0.45) * r2 * r2;
                vec2 distortedUv = lensCenter + p * factor;
                if (distortedUv.x < 0.0 || distortedUv.x > 1.0 || distortedUv.y < 0.0 || distortedUv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    vec2 edge = abs(distortedUv - 0.5) * 2.0;
                    float vig = smoothstep(1.02, 0.94, max(edge.x, edge.y));
                    vec4 col = texture2D(uEyeTexture, distortedUv);
                    gl_FragColor = vec4(col.rgb * vig, 1.0);
                }
            }
        """.trimIndent()
        distortionProgram = buildProgram(distortionVs, distortionFs)

        val solidVs = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
            }
        """.trimIndent()

        val solidFs = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent()
        solidColorProgram = buildProgram(solidVs, solidFs)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    fun releaseGlTextures() {
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        for (p in windowPanels.values) {
            p.releaseGlTexture()
        }
        windowPanels.clear()
        if (::warningPanel.isInitialized) warningPanel.releaseGlTexture()
        if (::aiHudBadgePanel.isInitialized) aiHudBadgePanel.releaseGlTexture()
    }

    companion object {
        private const val RETICLE_SEGMENTS = 48
        private const val VERTICAL_FOV_DEG = 60f
    }
}
