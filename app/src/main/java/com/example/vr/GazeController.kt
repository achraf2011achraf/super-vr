package com.example.vr

import android.opengl.Matrix
import com.example.vr.hand.HandTrackingState
import com.example.vr.pose.RoomBounds
import com.example.vr.pose.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

data class GazeHitResult(
    val panel: SpatialPanel,
    val u: Float,
    val v: Float,
    val distance: Float,
    val hoveredButton: PanelButton?
)

class GazeController {

    private var currentHoveredButtonId: String? = null
    private var hoverStartTimestampNs: Long = 0L

    // Free-space placement dwell timer when a window is in "PLACE" mode
    private var lastTrackedPlacementWindowId: String? = null
    private var placeModeStartNs: Long = 0L

    // Floor teleport dwell state
    private var floorHoverStartNs: Long = 0L
    private var isGazingFloor: Boolean = false
    private var lastTeleportTimestampNs: Long = 0L

    // Gamepad A/X instant trigger flag (set from UI thread, consumed on GL thread)
    @Volatile
    private var pendingPrimaryTrigger: Boolean = false

    // 1-Hand Pinch-Grab state (World Coordinates)
    private var wasPinchingLastFrame: Boolean = false
    var grabbedWindowId: String? = null
        private set
    private var grabInitialPanelDistance: Float = 2.0f
    private var grabInitialHandSize: Float = 0.22f
    private var grabSmoothedPosX: Float = 0f
    private var grabSmoothedPosY: Float = 1.6f
    private var grabSmoothedPosZ: Float = -2.0f
    private var grabSmoothedYawDeg: Float = 0f
    private var grabSmoothedPitchDeg: Float = 0f
    private var grabSmoothedDistMeters: Float = 2.0f
    private var grabSmoothedWallSnapped: Boolean = false

    // 2-Hand Pinch-Spread Resize state
    private var wasTwoHandPinching: Boolean = false
    var resizingWindowId: String? = null
        private set
    private var resizeInitialScale: Float = 1.0f
    private var resizeInitialSpread: Float = 0.25f
    private var resizeSmoothedScale: Float = 1.0f

    var activeButtonId: String? = null
        private set

    var dwellProgress: Float = 0f
        private set

    var isHoveringPanel: Boolean = false
        private set

    var floorTeleportTarget: Pair<Float, Float>? = null
        private set

    var floorTeleportProgress: Float = 0f
        private set

    var activeInputSource: String = "Gaze"
        private set

    var currentGazeDirX: Float = 0f
        private set
    var currentGazeDirY: Float = 0f
        private set
    var currentGazeDirZ: Float = -1f
        private set

    var currentGazeYawDeg: Float = 0f
        private set
    var currentGazePitchDeg: Float = 0f
        private set

    private val invViewMatrix = FloatArray(16)
    private val localOrigin = FloatArray(4)
    private val localDir = FloatArray(4)

    fun triggerPrimaryAction() {
        pendingPrimaryTrigger = true
    }

    fun update(
        viewMatrix: FloatArray,
        eyeAspect: Float,
        verticalFovDeg: Float,
        panels: List<SpatialPanel>,
        gazeDurationSec: Float,
        placingWindowId: String?,
        handState: HandTrackingState,
        isVirtualRoomMode: Boolean,
        floorTeleportEnabled: Boolean,
        roomBounds: RoomBounds,
        nowNs: Long,
        onButtonClicked: (String) -> Unit,
        onPlaceWindowAtGaze: (String, Float, Float, Float) -> Unit,
        onWindowTransformed: (
            windowId: String,
            posX: Float,
            posY: Float,
            posZ: Float,
            yawDeg: Float,
            pitchDeg: Float,
            scale: Float,
            isWallSnapped: Boolean,
            isFinalRelease: Boolean
        ) -> Unit,
        onTeleportFloor: (Float, Float) -> Unit
    ) {
        if (!Matrix.invertM(invViewMatrix, 0, viewMatrix, 0)) {
            resetHover()
            return
        }

        val triggerNow = pendingPrimaryTrigger
        pendingPrimaryTrigger = false

        // True 6DoF camera origin and gaze vector in world coordinates
        val headX = invViewMatrix[12]
        val headY = invViewMatrix[13]
        val headZ = invViewMatrix[14]

        val headWorldDirX = -invViewMatrix[8]
        val headWorldDirY = -invViewMatrix[9]
        val headWorldDirZ = -invViewMatrix[10]

        currentGazeDirX = headWorldDirX
        currentGazeDirY = headWorldDirY
        currentGazeDirZ = headWorldDirZ

        currentGazeYawDeg = Math.toDegrees(
            atan2(headWorldDirX.toDouble(), (-headWorldDirZ).toDouble())
        ).toFloat()

        val headHorizMag = sqrt((headWorldDirX * headWorldDirX + headWorldDirZ * headWorldDirZ).toDouble())
            .coerceAtLeast(1e-6)
        currentGazePitchDeg = Math.toDegrees(
            atan2(headWorldDirY.toDouble(), headHorizMag)
        ).toFloat().coerceIn(-55f, 55f)

        val handActive = handState.isEnabled && handState.isHandDetected

        // 1. Two-Hand Pinch-Spread Resize
        if (handActive && handState.isTwoHandPinching) {
            floorTeleportTarget = null
            floorTeleportProgress = 0f
            isGazingFloor = false

            if (!wasTwoHandPinching) {
                // Cancel any active 1-hand grab cleanly first
                grabbedWindowId = null
                wasPinchingLastFrame = false

                // Pick panel from fingertip or gaze ray
                val gazeHit = raycastPanels(
                    headX, headY, headZ,
                    headWorldDirX, headWorldDirY, headWorldDirZ,
                    panels,
                    ignoreWindowId = null
                )
                val targetPanel = gazeHit?.panel?.takeIf { it.id != "warning" }
                    ?: panels.firstOrNull { it.isVisible && it.id == "home" }
                if (targetPanel != null) {
                    resizingWindowId = targetPanel.id
                    resizeInitialScale = targetPanel.panelScale.coerceIn(0.55f, 2.20f)
                    resizeInitialSpread = handState.twoHandPinchSpread.coerceAtLeast(0.08f)
                    resizeSmoothedScale = resizeInitialScale
                }
            }

            val rId = resizingWindowId
            val targetPanel = panels.firstOrNull { it.id == rId }
            if (rId != null && targetPanel != null) {
                wasTwoHandPinching = true
                activeInputSource = "2-Hand Pinch Resize"
                resetHover()

                val spreadRatio = (handState.twoHandPinchSpread / resizeInitialSpread).coerceIn(0.45f, 2.5f)
                val targetScale = (resizeInitialScale * spreadRatio).coerceIn(0.55f, 2.20f)
                resizeSmoothedScale += 0.30f * (targetScale - resizeSmoothedScale)

                onWindowTransformed(
                    rId,
                    targetPanel.posX,
                    targetPanel.posY,
                    targetPanel.posZ,
                    targetPanel.yawAngleDeg,
                    targetPanel.pitchAngleDeg,
                    resizeSmoothedScale,
                    targetPanel.isWallSnapped,
                    false
                )
                return
            }
        } else if (wasTwoHandPinching) {
            wasTwoHandPinching = false
            val rId = resizingWindowId
            resizingWindowId = null
            val targetPanel = panels.firstOrNull { it.id == rId }
            if (rId != null && targetPanel != null) {
                onWindowTransformed(
                    rId,
                    targetPanel.posX,
                    targetPanel.posY,
                    targetPanel.posZ,
                    targetPanel.yawAngleDeg,
                    targetPanel.pitchAngleDeg,
                    resizeSmoothedScale.coerceIn(0.55f, 2.20f),
                    targetPanel.isWallSnapped,
                    true
                )
            }
        }

        // 2. One-Hand Pinch-to-Grab (moves panel in WORLD coordinates, wall-snaps or faces user on release)
        if (handActive) {
            val ndcX = (handState.pointerNormX - 0.5f) * 2.0f
            val ndcY = (0.5f - handState.pointerNormY) * 2.0f
            val tanHalfFov = tan(Math.toRadians((verticalFovDeg * 0.5).toDouble())).toFloat()

            val camRayX = ndcX * eyeAspect * tanHalfFov
            val camRayY = ndcY * tanHalfFov
            val camRayZ = -1.0f
            val camRayLen = sqrt(camRayX * camRayX + camRayY * camRayY + camRayZ * camRayZ)
            val nx = camRayX / camRayLen
            val ny = camRayY / camRayLen
            val nz = camRayZ / camRayLen

            val handWorldDirX = invViewMatrix[0] * nx + invViewMatrix[4] * ny + invViewMatrix[8] * nz
            val handWorldDirY = invViewMatrix[1] * nx + invViewMatrix[5] * ny + invViewMatrix[9] * nz
            val handWorldDirZ = invViewMatrix[2] * nx + invViewMatrix[6] * ny + invViewMatrix[10] * nz

            val handHit = raycastPanels(
                headX, headY, headZ,
                handWorldDirX, handWorldDirY, handWorldDirZ,
                panels,
                ignoreWindowId = null
            )

            if (handState.isPinching) {
                if (!wasPinchingLastFrame) {
                    val hitPanel = handHit?.panel
                    if (hitPanel != null && hitPanel.id != "warning") {
                        grabbedWindowId = hitPanel.id
                        val distFromHead = hypot(
                            hypot((hitPanel.posX - headX).toDouble(), (hitPanel.posZ - headZ).toDouble()),
                            (hitPanel.posY - headY).toDouble()
                        ).toFloat().coerceIn(0.8f, 4.0f)
                        grabInitialPanelDistance = distFromHead
                        grabInitialHandSize = handState.handSizeNorm.coerceAtLeast(0.06f)
                        grabSmoothedPosX = hitPanel.posX
                        grabSmoothedPosY = hitPanel.posY
                        grabSmoothedPosZ = hitPanel.posZ
                        grabSmoothedYawDeg = hitPanel.yawAngleDeg
                        grabSmoothedPitchDeg = hitPanel.pitchAngleDeg
                        grabSmoothedDistMeters = distFromHead
                        grabSmoothedWallSnapped = hitPanel.isWallSnapped
                    }
                }

                val activeGrabId = grabbedWindowId
                val activePanel = panels.firstOrNull { it.id == activeGrabId }
                if (activeGrabId != null && activePanel != null) {
                    wasPinchingLastFrame = true
                    activeInputSource = "Hand Pinch Grab"
                    floorTeleportTarget = null
                    floorTeleportProgress = 0f
                    isGazingFloor = false
                    resetHover()

                    val currentHandSize = handState.handSizeNorm.coerceAtLeast(0.06f)
                    val sizeScale = (grabInitialHandSize / currentHandSize).coerceIn(0.35f, 2.8f)
                    val targetDist = (grabInitialPanelDistance * sizeScale).coerceIn(0.8f, 4.0f)

                    val smoothDistAlpha = 0.28f
                    grabSmoothedDistMeters = (grabSmoothedDistMeters + smoothDistAlpha * (targetDist - grabSmoothedDistMeters))
                        .coerceIn(0.8f, 4.0f)

                    val rawTargetX = headX + handWorldDirX * grabSmoothedDistMeters
                    val rawTargetY = (headY + handWorldDirY * grabSmoothedDistMeters).coerceIn(0.55f, 2.70f)
                    val rawTargetZ = headZ + handWorldDirZ * grabSmoothedDistMeters

                    // Compute pose: snap to nearest wall if near wall in Virtual Room mode, else face user
                    val resolved = computeSnappedOrUserFacingPose(
                        rawX = rawTargetX,
                        rawY = rawTargetY,
                        rawZ = rawTargetZ,
                        headX = headX,
                        headY = headY,
                        headZ = headZ,
                        isVirtualRoomMode = isVirtualRoomMode,
                        roomBounds = roomBounds
                    )

                    val posAlpha = 0.30f
                    grabSmoothedPosX += posAlpha * (resolved.posX - grabSmoothedPosX)
                    grabSmoothedPosY += posAlpha * (resolved.posY - grabSmoothedPosY)
                    grabSmoothedPosZ += posAlpha * (resolved.posZ - grabSmoothedPosZ)
                    grabSmoothedYawDeg = resolved.yawDeg
                    grabSmoothedPitchDeg = resolved.pitchDeg
                    grabSmoothedWallSnapped = resolved.isWallSnapped

                    onWindowTransformed(
                        activeGrabId,
                        grabSmoothedPosX,
                        grabSmoothedPosY,
                        grabSmoothedPosZ,
                        grabSmoothedYawDeg,
                        grabSmoothedPitchDeg,
                        activePanel.panelScale,
                        grabSmoothedWallSnapped,
                        false
                    )
                    return
                }
            } else {
                val releasedId = grabbedWindowId
                val releasedPanel = panels.firstOrNull { it.id == releasedId }
                if (releasedId != null && releasedPanel != null) {
                    grabbedWindowId = null
                    wasPinchingLastFrame = false
                    // Lock at final world position & orientation on release
                    onWindowTransformed(
                        releasedId,
                        grabSmoothedPosX,
                        grabSmoothedPosY,
                        grabSmoothedPosZ,
                        grabSmoothedYawDeg,
                        grabSmoothedPitchDeg,
                        releasedPanel.panelScale,
                        grabSmoothedWallSnapped,
                        true
                    )
                    return
                }
            }
            wasPinchingLastFrame = handState.isPinching
        } else {
            val releasedId = grabbedWindowId
            val releasedPanel = panels.firstOrNull { it.id == releasedId }
            if (releasedId != null && releasedPanel != null) {
                grabbedWindowId = null
                onWindowTransformed(
                    releasedId,
                    grabSmoothedPosX,
                    grabSmoothedPosY,
                    grabSmoothedPosZ,
                    grabSmoothedYawDeg,
                    grabSmoothedPitchDeg,
                    releasedPanel.panelScale,
                    grabSmoothedWallSnapped,
                    true
                )
            }
            wasPinchingLastFrame = false
        }

        // 3. Standard Gaze Raycast (Fallback + UI Button Dwell + Floor Teleport)
        val closestHit = raycastPanels(
            headX = headX,
            headY = headY,
            headZ = headZ,
            worldDirX = headWorldDirX,
            worldDirY = headWorldDirY,
            worldDirZ = headWorldDirZ,
            panels = panels,
            ignoreWindowId = placingWindowId
        )

        // 3a. "PLACE" Window Mode (gaze anywhere off-button for 1.5s or press Gamepad A)
        if (placingWindowId != null && closestHit?.hoveredButton == null) {
            isHoveringPanel = true
            activeButtonId = null
            currentHoveredButtonId = null
            floorTeleportTarget = null
            floorTeleportProgress = 0f
            isGazingFloor = false
            activeInputSource = "Place Window Mode"

            if (triggerNow) {
                lastTrackedPlacementWindowId = null
                dwellProgress = 0f
                onPlaceWindowAtGaze(placingWindowId, headWorldDirX, headWorldDirY, headWorldDirZ)
                return
            }

            if (lastTrackedPlacementWindowId != placingWindowId) {
                lastTrackedPlacementWindowId = placingWindowId
                placeModeStartNs = nowNs
                dwellProgress = 0.02f
            } else {
                val targetDuration = gazeDurationSec.coerceIn(0.8f, 4.0f)
                val elapsedSec = (nowNs - placeModeStartNs) / 1_000_000_000f
                dwellProgress = (elapsedSec / targetDuration).coerceIn(0f, 1f)
                if (elapsedSec >= targetDuration) {
                    lastTrackedPlacementWindowId = null
                    dwellProgress = 0f
                    onPlaceWindowAtGaze(placingWindowId, headWorldDirX, headWorldDirY, headWorldDirZ)
                }
            }
            return
        } else {
            lastTrackedPlacementWindowId = null
        }

        // 3b. Panel Button Hover & Dwell (or Gamepad A click)
        isHoveringPanel = closestHit != null
        val button = closestHit?.hoveredButton

        if (button != null) {
            floorTeleportTarget = null
            floorTeleportProgress = 0f
            isGazingFloor = false
            activeInputSource = if (handActive) "Hand+Gaze Button" else "Gaze Dwell"

            if (triggerNow) {
                dwellProgress = 0f
                hoverStartTimestampNs = nowNs + 250_000_000L
                onButtonClicked(button.id)
                return
            }

            if (button.id != currentHoveredButtonId) {
                currentHoveredButtonId = button.id
                activeButtonId = button.id
                hoverStartTimestampNs = nowNs
                dwellProgress = 0.02f
            } else {
                val elapsedSec = (nowNs - hoverStartTimestampNs) / 1_000_000_000f
                val targetDuration = gazeDurationSec.coerceIn(0.5f, 5.0f)
                dwellProgress = (elapsedSec / targetDuration).coerceIn(0f, 1f)
                if (elapsedSec >= targetDuration) {
                    dwellProgress = 0f
                    hoverStartTimestampNs = nowNs + 280_000_000L
                    onButtonClicked(button.id)
                }
            }
            return
        }

        resetHover()

        // 3c. Floor Teleport (when looking down at Virtual Room floor and not hitting a panel)
        if (isVirtualRoomMode &&
            floorTeleportEnabled &&
            closestHit == null &&
            headWorldDirY < -0.15f &&
            (nowNs - lastTeleportTimestampNs) > 750_000_000L
        ) {
            val tFloor = -headY / headWorldDirY
            if (tFloor in 0.45f..8.2f) {
                val rawHitX = headX + tFloor * headWorldDirX
                val rawHitZ = headZ + tFloor * headWorldDirZ
                val clamped = roomBounds.clampPlayerPosition(Vec3(rawHitX, roomBounds.eyeHeight, rawHitZ))
                floorTeleportTarget = Pair(clamped.x, clamped.z)
                activeInputSource = "Floor Teleport Gaze"

                if (triggerNow) {
                    floorTeleportProgress = 0f
                    isGazingFloor = false
                    lastTeleportTimestampNs = nowNs
                    onTeleportFloor(clamped.x, clamped.z)
                    return
                }

                if (!isGazingFloor) {
                    isGazingFloor = true
                    floorHoverStartNs = nowNs
                    floorTeleportProgress = 0.02f
                } else {
                    val tpDuration = gazeDurationSec.coerceIn(0.8f, 3.0f)
                    val elapsedSec = (nowNs - floorHoverStartNs) / 1_000_000_000f
                    floorTeleportProgress = (elapsedSec / tpDuration).coerceIn(0f, 1f)
                    if (elapsedSec >= tpDuration) {
                        floorTeleportProgress = 0f
                        isGazingFloor = false
                        lastTeleportTimestampNs = nowNs
                        onTeleportFloor(clamped.x, clamped.z)
                    }
                }
                dwellProgress = floorTeleportProgress
                return
            }
        }

        floorTeleportTarget = null
        floorTeleportProgress = 0f
        isGazingFloor = false
        activeInputSource = if (handActive) "Hand Tracking" else "Gaze"
    }

    private fun raycastPanels(
        headX: Float,
        headY: Float,
        headZ: Float,
        worldDirX: Float,
        worldDirY: Float,
        worldDirZ: Float,
        panels: List<SpatialPanel>,
        ignoreWindowId: String?
    ): GazeHitResult? {
        var closestHit: GazeHitResult? = null

        for (panel in panels) {
            if (!panel.isVisible) continue
            if (ignoreWindowId == panel.id) continue

            val invM = panel.inverseModelMatrix
            // Transform 6DoF world headOrigin into panel-local coordinates
            localOrigin[0] = invM[0] * headX + invM[4] * headY + invM[8] * headZ + invM[12]
            localOrigin[1] = invM[1] * headX + invM[5] * headY + invM[9] * headZ + invM[13]
            localOrigin[2] = invM[2] * headX + invM[6] * headY + invM[10] * headZ + invM[14]

            localDir[0] = invM[0] * worldDirX + invM[4] * worldDirY + invM[8] * worldDirZ
            localDir[1] = invM[1] * worldDirX + invM[5] * worldDirY + invM[9] * worldDirZ
            localDir[2] = invM[2] * worldDirX + invM[6] * worldDirY + invM[10] * worldDirZ

            val dz = localDir[2]
            if (abs(dz) < 1e-5f) continue

            val t = -localOrigin[2] / dz
            if (t <= 0.12f) continue

            val hitX = localOrigin[0] + t * localDir[0]
            val hitY = localOrigin[1] + t * localDir[1]

            if (hitX in -0.5f..0.5f && hitY in -0.5f..0.5f) {
                val u = (hitX + 0.5f).coerceIn(0f, 1f)
                val v = (0.5f - hitY).coerceIn(0f, 1f)

                val hitButton = panel.buttons.firstOrNull { btn ->
                    btn.containsUv(u, v, panel.bitmapWidth, panel.bitmapHeight)
                }

                if (closestHit == null || t < closestHit.distance) {
                    closestHit = GazeHitResult(
                        panel = panel,
                        u = u,
                        v = v,
                        distance = t,
                        hoveredButton = hitButton
                    )
                }
            }
        }
        return closestHit
    }

    private fun resetHover() {
        currentHoveredButtonId = null
        activeButtonId = null
        dwellProgress = 0f
    }

    data class ResolvedPanelPose(
        val posX: Float,
        val posY: Float,
        val posZ: Float,
        val yawDeg: Float,
        val pitchDeg: Float,
        val isWallSnapped: Boolean
    )

    companion object {
        /**
         * Resolves a world-space panel pose:
         * - In Virtual Room mode, if the point is within 0.48m of any room wall, snaps flush
         *   against that wall facing inward into the room.
         * - Otherwise clamps within the room and orients the panel upright toward [headX, headY, headZ].
         */
        fun computeSnappedOrUserFacingPose(
            rawX: Float,
            rawY: Float,
            rawZ: Float,
            headX: Float,
            headY: Float,
            headZ: Float,
            isVirtualRoomMode: Boolean,
            roomBounds: RoomBounds
        ): ResolvedPanelPose {
            val wallInset = 0.12f
            val wallLimitX = roomBounds.halfWidth - wallInset
            val wallLimitZ = roomBounds.halfDepth - wallInset
            val snapThreshold = 0.48f

            val clampedX = rawX.coerceIn(-wallLimitX, wallLimitX)
            val clampedY = rawY.coerceIn(0.65f, roomBounds.ceilingHeight - 0.35f)
            val clampedZ = rawZ.coerceIn(-wallLimitZ, wallLimitZ)

            if (isVirtualRoomMode) {
                val distBack = abs(clampedZ - (-wallLimitZ))
                val distFront = abs(wallLimitZ - clampedZ)
                val distLeft = abs(clampedX - (-wallLimitX))
                val distRight = abs(wallLimitX - clampedX)
                val minWallDist = minOf(distBack, distFront, distLeft, distRight)

                if (minWallDist < snapThreshold) {
                    return when (minWallDist) {
                        distBack -> ResolvedPanelPose(clampedX, clampedY, -wallLimitZ, 0f, 0f, true)
                        distFront -> ResolvedPanelPose(clampedX, clampedY, wallLimitZ, 180f, 0f, true)
                        distLeft -> ResolvedPanelPose(-wallLimitX, clampedY, clampedZ, -90f, 0f, true)
                        else -> ResolvedPanelPose(wallLimitX, clampedY, clampedZ, 90f, 0f, true)
                    }
                }
            }

            // Face the user from (clampedX, clampedY, clampedZ) -> (headX, headY, headZ)
            val dx = headX - clampedX
            val dy = headY - clampedY
            val dz = headZ - clampedZ
            val horiz = hypot(dx.toDouble(), dz.toDouble()).coerceAtLeast(1e-4)
            val yawDeg = -Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
            val pitchDeg = -Math.toDegrees(atan2(dy.toDouble(), horiz)).toFloat().coerceIn(-35f, 35f)

            return ResolvedPanelPose(
                posX = clampedX,
                posY = clampedY,
                posZ = clampedZ,
                yawDeg = yawDeg,
                pitchDeg = pitchDeg,
                isWallSnapped = false
            )
        }
    }
}
