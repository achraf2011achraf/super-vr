package com.example.vr.hand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

data class HandLandmarkPoint(
    val x: Float, // Normalized [0..1] in upright landscape camera space (0 = left, 1 = right)
    val y: Float, // Normalized [0..1] in upright landscape camera space (0 = top, 1 = bottom)
    val z: Float
)

data class HandTrackingState(
    val isEnabled: Boolean = true,
    val isHandDetected: Boolean = false,
    val handCount: Int = 0,
    val landmarks: List<HandLandmarkPoint> = emptyList(), // 21 landmarks for primary hand
    val secondaryLandmarks: List<HandLandmarkPoint> = emptyList(), // 21 landmarks for 2nd hand (for pinch-spread resize)
    val isPinching: Boolean = false,
    val isSecondaryPinching: Boolean = false,
    val isTwoHandPinching: Boolean = false,
    val twoHandPinchSpread: Float = 0f,
    val pinchRatio: Float = 1.0f,       // Thumb-Index distance divided by hand size
    val handSizeNorm: Float = 0.20f,    // Smoothed apparent hand size in normalized frame units
    val pointerNormX: Float = 0.5f,     // Smoothed pinch/index pointer X [0..1]
    val pointerNormY: Float = 0.5f,     // Smoothed pinch/index pointer Y [0..1]
    val handFps: Int = 0,
    val activeDelegate: String = "Standby"
)

class HandTrackingManager(private val context: Context) {

    private val _state = MutableStateFlow(HandTrackingState())
    val state: StateFlow<HandTrackingState> = _state.asStateFlow()

    @Volatile
    var isEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                resetDetectionState()
            } else {
                _state.value = _state.value.copy(isEnabled = true)
            }
        }

    private var handLandmarker: HandLandmarker? = null
    private var activeDelegateName: String = "CPU"
    private val lock = Any()

    // 15 FPS cap -> minimum ~66.6 ms between analyzed frames
    private var lastAnalyzedTimestampMs: Long = 0L
    private var processedFrameCount: Int = 0
    private var lastFpsWindowMs: Long = System.currentTimeMillis()
    private var currentHandFps: Int = 0
    private var lastHandSeenMs: Long = 0L

    // Hysteresis pinch state & low-pass smoothed landmarks (primary + optional secondary hand)
    private var isCurrentlyPinching: Boolean = false
    private var isSecondaryCurrentlyPinching: Boolean = false
    private val smoothedLandmarks = Array(21) { FloatArray(3) }
    private val smoothedSecondaryLandmarks = Array(21) { FloatArray(3) }
    private var hasPriorLandmarks: Boolean = false
    private var hasPriorSecondaryLandmarks: Boolean = false
    private var smoothedHandSize: Float = 0.22f
    private var smoothedPointerX: Float = 0.5f
    private var smoothedPointerY: Float = 0.5f
    private var smoothedTwoHandSpread: Float = 0f

    // Smoothing alpha (0.38f gives smooth, low-jitter skeleton & grab tracking at 15 FPS)
    private val landmarkAlpha = 0.38f
    private val pointerAlpha = 0.34f

    fun ensureInitialized() {
        synchronized(lock) {
            if (handLandmarker != null) return

            // Try GPU delegate first, fallback to CPU automatically
            handLandmarker = tryCreateLandmarker(Delegate.GPU)?.also {
                activeDelegateName = "GPU"
            } ?: tryCreateLandmarker(Delegate.CPU)?.also {
                activeDelegateName = "CPU"
            }

            _state.value = _state.value.copy(
                activeDelegate = if (handLandmarker != null) activeDelegateName else "Unavailable"
            )
        }
    }

    private fun tryCreateLandmarker(delegate: Delegate): HandLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .setDelegate(delegate)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.w("HandTrackingManager", "Delegate $delegate init failed, trying fallback", e)
            null
        }
    }

    /**
     * Called on the background ImageAnalysis executor thread for each CameraX frame.
     * Throttles inference to <= 15 FPS (~66ms interval) at ~320x240 resolution.
     */
    fun analyzeFrame(imageProxy: ImageProxy, extraCameraRotationOffsetDeg: Int) {
        try {
            if (!isEnabled) {
                return
            }

            val nowMs = System.currentTimeMillis()
            // Cap inference strictly at 15 FPS (66 ms interval)
            if (nowMs - lastAnalyzedTimestampMs < 66L) {
                return
            }
            lastAnalyzedTimestampMs = nowMs

            ensureInitialized()
            val landmarker = synchronized(lock) { handLandmarker } ?: return

            // Convert ImageProxy (RGBA_8888) to upright landscape Bitmap matching the passthrough view
            val rawBitmap = imageProxy.toBitmap()
            val totalRotation = (imageProxy.imageInfo.rotationDegrees + extraCameraRotationOffsetDeg) % 360
            val uprightBitmap = if (totalRotation != 0) {
                val matrix = Matrix().apply { postRotate(totalRotation.toFloat()) }
                Bitmap.createBitmap(
                    rawBitmap,
                    0,
                    0,
                    rawBitmap.width,
                    rawBitmap.height,
                    matrix,
                    true
                )
            } else {
                rawBitmap
            }

            val mpImage = BitmapImageBuilder(uprightBitmap).build()
            val result: HandLandmarkerResult = synchronized(lock) {
                landmarker.detect(mpImage)
            }

            // Update 15 FPS counter
            processedFrameCount++
            if (nowMs - lastFpsWindowMs >= 1000L) {
                currentHandFps = processedFrameCount
                processedFrameCount = 0
                lastFpsWindowMs = nowMs
            }

            processResult(result, nowMs)
        } catch (e: Exception) {
            Log.w("HandTrackingManager", "Frame analysis error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun processResult(result: HandLandmarkerResult, nowMs: Long) {
        val hands = result.landmarks()
        if (hands.isNullOrEmpty() || hands[0].size < 21) {
            // Allow 180ms grace period before dropping hand detection to prevent single-frame dropout
            if (nowMs - lastHandSeenMs > 180L) {
                hasPriorLandmarks = false
                hasPriorSecondaryLandmarks = false
                isCurrentlyPinching = false
                isSecondaryCurrentlyPinching = false
                _state.value = _state.value.copy(
                    isEnabled = isEnabled,
                    isHandDetected = false,
                    handCount = 0,
                    landmarks = emptyList(),
                    secondaryLandmarks = emptyList(),
                    isPinching = false,
                    isSecondaryPinching = false,
                    isTwoHandPinching = false,
                    twoHandPinchSpread = 0f,
                    handFps = currentHandFps,
                    activeDelegate = activeDelegateName
                )
            }
            return
        }

        lastHandSeenMs = nowMs
        val rawLm = hands[0]

        val outPoints = ArrayList<HandLandmarkPoint>(21)
        for (i in 0 until 21) {
            val rx = rawLm[i].x().coerceIn(0f, 1f)
            val ry = rawLm[i].y().coerceIn(0f, 1f)
            val rz = rawLm[i].z()

            if (!hasPriorLandmarks) {
                smoothedLandmarks[i][0] = rx
                smoothedLandmarks[i][1] = ry
                smoothedLandmarks[i][2] = rz
            } else {
                smoothedLandmarks[i][0] += landmarkAlpha * (rx - smoothedLandmarks[i][0])
                smoothedLandmarks[i][1] += landmarkAlpha * (ry - smoothedLandmarks[i][1])
                smoothedLandmarks[i][2] += landmarkAlpha * (rz - smoothedLandmarks[i][2])
            }
            outPoints.add(
                HandLandmarkPoint(
                    x = smoothedLandmarks[i][0],
                    y = smoothedLandmarks[i][1],
                    z = smoothedLandmarks[i][2]
                )
            )
        }

        val wrist = outPoints[0]
        val indexMcp = outPoints[5]
        val middleMcp = outPoints[9]
        val pinkyMcp = outPoints[17]

        val palmLength = hypot((middleMcp.x - wrist.x).toDouble(), (middleMcp.y - wrist.y).toDouble()).toFloat()
        val palmWidth = hypot((pinkyMcp.x - indexMcp.x).toDouble(), (pinkyMcp.y - indexMcp.y).toDouble()).toFloat()
        val rawHandSize = ((palmLength + palmWidth) * 0.5f).coerceIn(0.05f, 0.75f)

        if (!hasPriorLandmarks) {
            smoothedHandSize = rawHandSize
        } else {
            smoothedHandSize += pointerAlpha * (rawHandSize - smoothedHandSize)
        }

        val thumbTip = outPoints[4]
        val indexTip = outPoints[8]
        val tipDist = hypot((thumbTip.x - indexTip.x).toDouble(), (thumbTip.y - indexTip.y).toDouble()).toFloat()
        val pinchRatio = (tipDist / smoothedHandSize.coerceAtLeast(0.06f)).coerceIn(0f, 3f)

        isCurrentlyPinching = if (isCurrentlyPinching) {
            pinchRatio < PINCH_RELEASE_THRESHOLD
        } else {
            pinchRatio < PINCH_ENTER_THRESHOLD
        }

        val rawPtrX = (thumbTip.x + indexTip.x) * 0.5f
        val rawPtrY = (thumbTip.y + indexTip.y) * 0.5f
        if (!hasPriorLandmarks) {
            smoothedPointerX = rawPtrX
            smoothedPointerY = rawPtrY
            hasPriorLandmarks = true
        } else {
            smoothedPointerX += pointerAlpha * (rawPtrX - smoothedPointerX)
            smoothedPointerY += pointerAlpha * (rawPtrY - smoothedPointerY)
        }

        // Process optional 2nd hand for two-hand pinch-spread resizing
        val secondaryPoints = ArrayList<HandLandmarkPoint>(21)
        var twoHandSpread = 0f
        if (hands.size >= 2 && hands[1].size >= 21) {
            val rawLm2 = hands[1]
            for (i in 0 until 21) {
                val rx = rawLm2[i].x().coerceIn(0f, 1f)
                val ry = rawLm2[i].y().coerceIn(0f, 1f)
                val rz = rawLm2[i].z()
                if (!hasPriorSecondaryLandmarks) {
                    smoothedSecondaryLandmarks[i][0] = rx
                    smoothedSecondaryLandmarks[i][1] = ry
                    smoothedSecondaryLandmarks[i][2] = rz
                } else {
                    smoothedSecondaryLandmarks[i][0] += landmarkAlpha * (rx - smoothedSecondaryLandmarks[i][0])
                    smoothedSecondaryLandmarks[i][1] += landmarkAlpha * (ry - smoothedSecondaryLandmarks[i][1])
                    smoothedSecondaryLandmarks[i][2] += landmarkAlpha * (rz - smoothedSecondaryLandmarks[i][2])
                }
                secondaryPoints.add(
                    HandLandmarkPoint(
                        x = smoothedSecondaryLandmarks[i][0],
                        y = smoothedSecondaryLandmarks[i][1],
                        z = smoothedSecondaryLandmarks[i][2]
                    )
                )
            }
            val palmLen2 = hypot(
                (secondaryPoints[9].x - secondaryPoints[0].x).toDouble(),
                (secondaryPoints[9].y - secondaryPoints[0].y).toDouble()
            ).toFloat()
            val palmW2 = hypot(
                (secondaryPoints[17].x - secondaryPoints[5].x).toDouble(),
                (secondaryPoints[17].y - secondaryPoints[5].y).toDouble()
            ).toFloat()
            val handSize2 = ((palmLen2 + palmW2) * 0.5f).coerceIn(0.06f, 0.75f)
            val tipDist2 = hypot(
                (secondaryPoints[4].x - secondaryPoints[8].x).toDouble(),
                (secondaryPoints[4].y - secondaryPoints[8].y).toDouble()
            ).toFloat()
            val pinchRatio2 = tipDist2 / handSize2
            isSecondaryCurrentlyPinching = if (isSecondaryCurrentlyPinching) {
                pinchRatio2 < PINCH_RELEASE_THRESHOLD
            } else {
                pinchRatio2 < PINCH_ENTER_THRESHOLD
            }

            val ptr2X = (secondaryPoints[4].x + secondaryPoints[8].x) * 0.5f
            val ptr2Y = (secondaryPoints[4].y + secondaryPoints[8].y) * 0.5f
            val rawSpread = hypot((rawPtrX - ptr2X).toDouble(), (rawPtrY - ptr2Y).toDouble()).toFloat()
            smoothedTwoHandSpread = if (!hasPriorSecondaryLandmarks) {
                hasPriorSecondaryLandmarks = true
                rawSpread
            } else {
                smoothedTwoHandSpread + pointerAlpha * (rawSpread - smoothedTwoHandSpread)
            }
            twoHandSpread = smoothedTwoHandSpread
        } else {
            hasPriorSecondaryLandmarks = false
            isSecondaryCurrentlyPinching = false
        }

        val twoHandPinching = isCurrentlyPinching && isSecondaryCurrentlyPinching && secondaryPoints.isNotEmpty()

        _state.value = HandTrackingState(
            isEnabled = isEnabled,
            isHandDetected = true,
            handCount = if (secondaryPoints.isNotEmpty()) 2 else 1,
            landmarks = outPoints,
            secondaryLandmarks = secondaryPoints,
            isPinching = isCurrentlyPinching,
            isSecondaryPinching = isSecondaryCurrentlyPinching,
            isTwoHandPinching = twoHandPinching,
            twoHandPinchSpread = twoHandSpread,
            pinchRatio = pinchRatio,
            handSizeNorm = smoothedHandSize,
            pointerNormX = smoothedPointerX,
            pointerNormY = smoothedPointerY,
            handFps = currentHandFps,
            activeDelegate = activeDelegateName
        )
    }

    private fun resetDetectionState() {
        hasPriorLandmarks = false
        hasPriorSecondaryLandmarks = false
        isCurrentlyPinching = false
        isSecondaryCurrentlyPinching = false
        currentHandFps = 0
        _state.value = HandTrackingState(
            isEnabled = false,
            isHandDetected = false,
            handCount = 0,
            landmarks = emptyList(),
            secondaryLandmarks = emptyList(),
            isPinching = false,
            isSecondaryPinching = false,
            isTwoHandPinching = false,
            twoHandPinchSpread = 0f,
            handFps = 0,
            activeDelegate = activeDelegateName
        )
    }

    fun release() {
        synchronized(lock) {
            try {
                handLandmarker?.close()
            } catch (_: Exception) {
            }
            handLandmarker = null
        }
        resetDetectionState()
    }

    companion object {
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"
        private const val PINCH_ENTER_THRESHOLD = 0.35f
        private const val PINCH_RELEASE_THRESHOLD = 0.49f

        /**
         * Standard 21-landmark MediaPipe hand bone connections (pairs of landmark indices).
         */
        val HAND_BONE_CONNECTIONS: IntArray = intArrayOf(
            // Thumb
            0, 1, 1, 2, 2, 3, 3, 4,
            // Index finger
            0, 5, 5, 6, 6, 7, 7, 8,
            // Middle finger
            5, 9, 9, 10, 10, 11, 11, 12,
            // Ring finger
            9, 13, 13, 14, 14, 15, 15, 16,
            // Pinky finger
            13, 17, 0, 17, 17, 18, 18, 19, 19, 20
        )
    }
}
