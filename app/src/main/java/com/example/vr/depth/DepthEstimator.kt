package com.example.vr.depth

import android.graphics.Bitmap

/**
 * Result returned by a [DepthEstimator] for a given normalized gaze point in the camera frame.
 *
 * @param estimatedDistanceMeters Estimated metric distance in meters (clamped to valid spatial range 0.8m .. 5.0m).
 * @param confidence Confidence score in [0.0 .. 1.0]. Values < 0.25 indicate fallback to default distance.
 * @param sourceName Identifier of the active depth backend (e.g., "Default2mFallback" or "TFLite-MiDaS-v2.1").
 */
data class DepthEstimateResult(
    val estimatedDistanceMeters: Float,
    val confidence: Float,
    val sourceName: String
)

/**
 * Modular interface for monocular depth estimation from the rear passthrough camera.
 *
 * Designed so a TensorFlow Lite monocular depth model (such as MiDaS Small or DepthAnything-TFLite)
 * can be plugged in without changing the VR renderer, gaze placement workflow, or ViewModel.
 *
 * Future TFLite integration steps:
 * 1. Add `org.tensorflow:tensorflow-lite` and `tensorflow-lite-gpu` to `libs.versions.toml`.
 * 2. Implement `TfLiteMonocularDepthEstimator(context)` loading `midas_v21_small_256.tflite` from `assets/`.
 * 3. Sample the relative inverse depth map at `(normX, normY)` and convert to metric distance `d = scale / (disp + shift)`.
 */
interface DepthEstimator {

    /**
     * Initializes the depth model or hardware accelerator if needed.
     */
    fun initialize()

    /**
     * Estimates metric depth in meters at normalized screen/camera coordinates [normX], [normY] in [0..1].
     *
     * @param frameBitmap Optional low-res snapshot of the current camera frame (null when running zero-overhead fallback).
     * @param normX Normalized horizontal coordinate (0.5f = center gaze reticle).
     * @param normY Normalized vertical coordinate (0.5f = center gaze reticle).
     * @param fallbackDistanceMeters Default distance in meters (typically 2.0m) when no depth model is loaded.
     */
    fun estimateDepthAtGaze(
        frameBitmap: Bitmap? = null,
        normX: Float = 0.5f,
        normY: Float = 0.5f,
        fallbackDistanceMeters: Float = 2.0f
    ): DepthEstimateResult

    /**
     * Releases any native buffers or TFLite Interpreter resources.
     */
    fun close()
}

/**
 * Lightweight default implementation used on Kirin 710 when no TFLite model is bundled.
 * Returns the default 2.0m placement distance with zero CPU/GPU thermal overhead, while keeping
 * the full placement pipeline ready for automatic depth-assisted window placement.
 */
class DefaultFallbackDepthEstimator : DepthEstimator {

    override fun initialize() {
        // No-op for default 2.0m fallback; a TFLite implementation loads its Interpreter here.
    }

    override fun estimateDepthAtGaze(
        frameBitmap: Bitmap?,
        normX: Float,
        normY: Float,
        fallbackDistanceMeters: Float
    ): DepthEstimateResult {
        return DepthEstimateResult(
            estimatedDistanceMeters = fallbackDistanceMeters.coerceIn(1.0f, 4.0f),
            confidence = 0.0f,
            sourceName = "Default 2.0m (TFLite Slot Ready)"
        )
    }

    override fun close() {
        // No-op
    }
}
