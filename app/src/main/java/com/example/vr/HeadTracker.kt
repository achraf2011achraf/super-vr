package com.example.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

data class HeadEulerAngles(
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val sensorName: String = "3DoF Sensor"
)

class HeadTracker(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rawMatrix = FloatArray(16)
    private val remappedMatrix = FloatArray(16)
    private val viewRotationMatrix = FloatArray(16)
    private val yawOffsetMatrix = FloatArray(16)

    // Smooth quaternion buffer for low-pass NLERP filtering
    private val currentQuat = FloatArray(4) { if (it == 0) 1f else 0f }
    private val smoothedQuat = FloatArray(4) { if (it == 0) 1f else 0f }
    private var hasInitialReading = false
    private var needsRecenterOnNextSample = true

    @Volatile
    private var recenteredYawDeg: Float = 0f

    // Smoothing factor (0.28f keeps responsiveness at 60Hz while eliminating micro-jitter)
    private val smoothingAlpha = 0.28f

    private val _eulerAngles = MutableStateFlow(
        HeadEulerAngles(
            sensorName = rotationSensor?.name ?: "Simulated 3DoF (No Gyro)"
        )
    )
    val eulerAngles: StateFlow<HeadEulerAngles> = _eulerAngles.asStateFlow()

    private var isTracking = false

    init {
        Matrix.setIdentityM(rawMatrix, 0)
        Matrix.setIdentityM(remappedMatrix, 0)
        Matrix.setIdentityM(viewRotationMatrix, 0)
        Matrix.setIdentityM(yawOffsetMatrix, 0)
    }

    fun start() {
        if (isTracking) return
        rotationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            isTracking = true
        }
    }

    fun stop() {
        if (!isTracking) return
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    /**
     * Resets the current horizontal heading (yaw) so that the floating spatial panels
     * appear directly in front of the user's current gaze direction.
     */
    @Synchronized
    fun recenter() {
        if (!hasInitialReading) {
            needsRecenterOnNextSample = true
            return
        }
        // In OpenGL viewMatrix (world-to-camera), the camera's forward vector (-Z_cam) in world space
        // is the negative 3rd row of viewRotationMatrix: (-m[2], -m[6], -m[10]).
        val forwardWorldX = -viewRotationMatrix[2]
        val forwardWorldZ = -viewRotationMatrix[10]
        val currentYawRad = atan2(forwardWorldX.toDouble(), (-forwardWorldZ).toDouble())
        recenteredYawDeg = Math.toDegrees(currentYawRad).toFloat()

        // Rotate world coordinates by +recenteredYawDeg around World Y so current heading becomes 0°
        Matrix.setRotateM(
            yawOffsetMatrix,
            0,
            recenteredYawDeg,
            0f,
            1f,
            0f
        )
    }

    /**
     * Copies the thread-safe 4x4 OpenGL view rotation matrix into [dest] (length 16).
     */
    @Synchronized
    fun getViewMatrix(dest: FloatArray) {
        Matrix.multiplyMM(dest, 0, viewRotationMatrix, 0, yawOffsetMatrix, 0)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return

        // Convert rotation vector to quaternion [w, x, y, z]
        SensorManager.getQuaternionFromVector(currentQuat, event.values)

        if (!hasInitialReading) {
            System.arraycopy(currentQuat, 0, smoothedQuat, 0, 4)
            hasInitialReading = true
        } else {
            var dot = 0f
            for (i in 0..3) dot += smoothedQuat[i] * currentQuat[i]
            val sign = if (dot < 0f) -1f else 1f
            var norm = 0f
            for (i in 0..3) {
                smoothedQuat[i] =
                    (1f - smoothingAlpha) * smoothedQuat[i] + smoothingAlpha * (sign * currentQuat[i])
                norm += smoothedQuat[i] * smoothedQuat[i]
            }
            val invNorm = 1f / sqrt(norm.coerceAtLeast(1e-6f))
            for (i in 0..3) smoothedQuat[i] *= invNorm
        }

        val rotVec = floatArrayOf(smoothedQuat[1], smoothedQuat[2], smoothedQuat[3], smoothedQuat[0])
        SensorManager.getRotationMatrixFromVector(rawMatrix, rotVec)

        // Remap device axes to match the current display orientation (ROTATION_90 or ROTATION_270):
        // Screen X = right, Screen Y = up, Screen Z = out of screen toward user.
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_90
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }

        SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, remappedMatrix)

        // SensorManager.getRotationMatrixFromVector fills a row-major array where:
        //   row 0 (indices 0,1,2)   = ENU World East  (X) in Camera/Screen space
        //   row 1 (indices 4,5,6)   = ENU World North (Y) in Camera/Screen space
        //   row 2 (indices 8,9,10)  = ENU World Up    (Z) in Camera/Screen space
        //
        // When passed to OpenGL (which reads column-major), we need the columns of viewRotationMatrix
        // to be the OpenGL World axes (X_gl = East, Y_gl = Up, Z_gl = South) expressed in Camera space:
        //   Col 0 (X_gl = East)  =  row 0 of remappedMatrix (indices 0, 1, 2)
        //   Col 1 (Y_gl = Up)    =  row 2 of remappedMatrix (indices 8, 9, 10)
        //   Col 2 (Z_gl = South) = -row 1 of remappedMatrix (indices -4, -5, -6)
        synchronized(this) {
            // Column 0: OpenGL World +X (East) in camera coordinates
            viewRotationMatrix[0] = remappedMatrix[0]
            viewRotationMatrix[1] = remappedMatrix[1]
            viewRotationMatrix[2] = remappedMatrix[2]
            viewRotationMatrix[3] = 0f

            // Column 1: OpenGL World +Y (Up) in camera coordinates
            viewRotationMatrix[4] = remappedMatrix[8]
            viewRotationMatrix[5] = remappedMatrix[9]
            viewRotationMatrix[6] = remappedMatrix[10]
            viewRotationMatrix[7] = 0f

            // Column 2: OpenGL World +Z (South) in camera coordinates
            viewRotationMatrix[8] = -remappedMatrix[4]
            viewRotationMatrix[9] = -remappedMatrix[5]
            viewRotationMatrix[10] = -remappedMatrix[6]
            viewRotationMatrix[11] = 0f

            // Column 3: homogeneous translation (0, 0, 0, 1)
            viewRotationMatrix[12] = 0f
            viewRotationMatrix[13] = 0f
            viewRotationMatrix[14] = 0f
            viewRotationMatrix[15] = 1f

            if (needsRecenterOnNextSample) {
                needsRecenterOnNextSample = false
                recenter()
            }
        }

        // Compute human-readable Yaw, Pitch, Roll degrees from the active view matrix
        val viewM = FloatArray(16)
        getViewMatrix(viewM)
        // Forward vector (-Z_cam) in OpenGL world space is (-viewM[2], -viewM[6], -viewM[10])
        val forwardX = -viewM[2]
        val forwardY = -viewM[6]
        val forwardZ = -viewM[10]

        val yawDeg = Math.toDegrees(atan2(forwardX.toDouble(), (-forwardZ).toDouble())).toFloat()
        val pitchDeg = Math.toDegrees(
            atan2(
                forwardY.toDouble(),
                sqrt((forwardX * forwardX + forwardZ * forwardZ).toDouble())
            )
        ).toFloat()
        val rollDeg = Math.toDegrees(atan2(viewM[1].toDouble(), viewM[5].toDouble())).toFloat()

        _eulerAngles.value = HeadEulerAngles(
            yawDeg = yawDeg,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            sensorName = rotationSensor?.name ?: "3DoF Rotation Vector"
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
