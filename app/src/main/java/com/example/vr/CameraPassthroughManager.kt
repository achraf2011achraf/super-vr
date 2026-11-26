package com.example.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.vr.hand.HandTrackingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

data class CameraTelemetry(
    val width: Int = 1280,
    val height: Int = 720,
    val displayRotation: Int = Surface.ROTATION_90,
    val isActive: Boolean = false,
    val statusText: String = "Standby (1280×720 Target)"
)

data class CapturedVisionFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int
)

class CameraPassthroughManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val _telemetry = MutableStateFlow(CameraTelemetry())
    val telemetry: StateFlow<CameraTelemetry> = _telemetry.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var imageCaptureUseCase: ImageCapture? = null
    private var activeSurface: Surface? = null
    private var analysisExecutor: ExecutorService? = null

    @Volatile
    var cameraRotationOffsetDeg: Int = 0

    /**
     * Binds CameraX rear camera Preview (~1280x720 into [surfaceTexture]), low-resolution
     * (~320x240) [ImageAnalysis] stream for MediaPipe Hand Landmarker, and an on-demand
     * [ImageCapture] use case (which never captures unless triggered by the user).
     */
    fun bindToSurfaceTexture(
        lifecycleOwner: LifecycleOwner,
        surfaceTexture: SurfaceTexture,
        handTrackingManager: HandTrackingManager
    ) {
        val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                @Suppress("DEPRECATION")
                val currentDisplayRot = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_90

                // 1. 1280x720 Preview UseCase for stereo OpenGL OES passthrough
                val previewResSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(previewResSelector)
                    .setTargetRotation(currentDisplayRot)
                    .build()

                preview.setSurfaceProvider(mainExecutor) { request ->
                    val res = request.resolution
                    surfaceTexture.setDefaultBufferSize(res.width, res.height)

                    activeSurface?.release()
                    val surface = Surface(surfaceTexture)
                    activeSurface = surface

                    @Suppress("DEPRECATION")
                    val rot = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_90
                    _telemetry.value = CameraTelemetry(
                        width = maxOf(res.width, res.height),
                        height = minOf(res.width, res.height),
                        displayRotation = rot,
                        isActive = true,
                        statusText = "${maxOf(res.width, res.height)}×${minOf(res.width, res.height)} OES + 320×240 Hand AI"
                    )

                    request.provideSurface(surface, mainExecutor) {
                        surface.release()
                        if (activeSurface === surface) {
                            activeSurface = null
                        }
                    }
                }

                // 2. Low-res (~320x240) ImageAnalysis UseCase on a background thread for 15 FPS Hand Tracking
                val analysisResSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val executor = analysisExecutor?.takeIf { !it.isShutdown }
                    ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResSelector)
                    .setTargetRotation(currentDisplayRot)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(executor) { imageProxy ->
                    handTrackingManager.analyzeFrame(imageProxy, cameraRotationOffsetDeg)
                }

                // 3. On-demand ImageCapture (~1280x720) strictly for user-triggered "What am I seeing?"
                val captureResSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(captureResSelector)
                    .setTargetRotation(currentDisplayRot)
                    .build()

                provider.unbindAll()
                try {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                        capture
                    )
                    imageCaptureUseCase = capture
                } catch (tripleBindError: Exception) {
                    Log.w("CameraPassthrough", "3-use-case bind failed, falling back to Preview + Analysis", tripleBindError)
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    imageCaptureUseCase = null
                }

                previewUseCase = preview
                imageAnalysisUseCase = analysis
            } catch (e: Exception) {
                Log.e("CameraPassthrough", "Failed to bind CameraX preview + hand analysis", e)
                _telemetry.value = CameraTelemetry(
                    width = 1280,
                    height = 720,
                    displayRotation = Surface.ROTATION_90,
                    isActive = false,
                    statusText = "Camera Unavailable (${e.javaClass.simpleName})"
                )
            }
        }, mainExecutor)
    }

    /**
     * Captures a single camera frame on demand when explicitly triggered by the user,
     * applies upright orientation, downscales to max dimension 1024px, and compresses to JPEG.
     */
    suspend fun captureJpegFrame1024(): Result<CapturedVisionFrame> = withContext(Dispatchers.IO) {
        val capture = imageCaptureUseCase
            ?: return@withContext Result.failure(
                IllegalStateException("Camera is not active yet. Grant camera permission and wait for passthrough.")
            )

        val executor = analysisExecutor?.takeIf { !it.isShutdown }
            ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }

        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        var decoded: Bitmap? = null
                        var rotated: Bitmap? = null
                        var scaled: Bitmap? = null
                        try {
                            decoded = decodeImageProxyToBitmap(image)
                            if (decoded == null) {
                                if (cont.isActive) {
                                    cont.resume(Result.failure(IllegalStateException("Unable to decode camera frame.")))
                                }
                                return
                            }

                            val totalRot = ((image.imageInfo.rotationDegrees + cameraRotationOffsetDeg) % 360 + 360) % 360
                            rotated = if (totalRot != 0) {
                                val mat = Matrix().apply { postRotate(totalRot.toFloat()) }
                                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, mat, true)
                            } else {
                                decoded
                            }

                            val maxDim = max(rotated.width, rotated.height)
                            scaled = if (maxDim > TARGET_MAX_DIMENSION_PX) {
                                val ratio = TARGET_MAX_DIMENSION_PX.toFloat() / maxDim.toFloat()
                                val targetW = (rotated.width * ratio).roundToInt().coerceAtLeast(1)
                                val targetH = (rotated.height * ratio).roundToInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
                            } else {
                                rotated
                            }

                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                            val jpegBytes = baos.toByteArray()

                            if (cont.isActive) {
                                cont.resume(
                                    Result.success(
                                        CapturedVisionFrame(
                                            jpegBytes = jpegBytes,
                                            width = scaled.width,
                                            height = scaled.height
                                        )
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("CameraPassthrough", "Failed to process captured JPEG frame", e)
                            if (cont.isActive) {
                                cont.resume(Result.failure(e))
                            }
                        } finally {
                            if (scaled != null && scaled !== rotated && scaled !== decoded) scaled.recycle()
                            if (rotated != null && rotated !== decoded) rotated.recycle()
                            decoded?.recycle()
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraPassthrough", "ImageCapture failed", exception)
                        if (cont.isActive) {
                            cont.resume(Result.failure(exception))
                        }
                    }
                }
            )
        }
    }

    private fun decodeImageProxyToBitmap(image: ImageProxy): Bitmap? {
        return if (image.format == ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val maxDim = max(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > TARGET_MAX_DIMENSION_PX * 2) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } else {
            image.toBitmap()
        }
    }

    fun getCurrentDisplayRotation(): Int {
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_90
    }

    fun release() {
        val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
        mainExecutor.execute {
            try {
                imageAnalysisUseCase?.clearAnalyzer()
                imageCaptureUseCase = null
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            activeSurface?.release()
            activeSurface = null
            _telemetry.value = _telemetry.value.copy(
                isActive = false,
                statusText = "Released"
            )
        }
    }

    fun shutdownExecutor() {
        try {
            analysisExecutor?.shutdownNow()
        } catch (_: Exception) {
        }
        analysisExecutor = null
    }

    companion object {
        private const val TARGET_MAX_DIMENSION_PX = 1024
        private const val JPEG_QUALITY = 85
    }
}
