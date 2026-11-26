package com.example.vr.pose

import android.opengl.Matrix
import com.example.vr.HeadTracker
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight 3D vector for world-space head and window coordinates (meters, Y-up).
 */
data class Vec3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun distanceTo(other: Vec3): Float = (this - other).length()

    fun normalized(): Vec3 {
        val len = length().coerceAtLeast(1e-6f)
        return Vec3(x / len, y / len, z / len)
    }
}

/**
 * Unit quaternion representing 3D orientation [w, x, y, z].
 */
data class Quaternion(
    val w: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    fun normalized(): Quaternion {
        val len = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(1e-6f)
        return Quaternion(w / len, x / len, y / len, z / len)
    }

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /**
         * Extracts a unit quaternion from an OpenGL 4x4 column-major rotation matrix.
         */
        fun fromRotationMatrix(m: FloatArray): Quaternion {
            val m00 = m[0]; val m01 = m[4]; val m02 = m[8]
            val m10 = m[1]; val m11 = m[5]; val m12 = m[9]
            val m20 = m[2]; val m21 = m[6]; val m22 = m[10]
            val trace = m00 + m11 + m22
            return if (trace > 0f) {
                val s = sqrt(trace + 1.0f) * 2f
                Quaternion(
                    w = 0.25f * s,
                    x = (m21 - m12) / s,
                    y = (m02 - m20) / s,
                    z = (m10 - m01) / s
                ).normalized()
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1.0f + m00 - m11 - m22) * 2f
                Quaternion(
                    w = (m21 - m12) / s,
                    x = 0.25f * s,
                    y = (m01 + m10) / s,
                    z = (m02 + m20) / s
                ).normalized()
            } else if (m11 > m22) {
                val s = sqrt(1.0f + m11 - m00 - m22) * 2f
                Quaternion(
                    w = (m02 - m20) / s,
                    x = (m01 + m10) / s,
                    y = 0.25f * s,
                    z = (m12 + m21) / s
                ).normalized()
            } else {
                val s = sqrt(1.0f + m22 - m00 - m11) * 2f
                Quaternion(
                    w = (m10 - m01) / s,
                    x = (m02 + m20) / s,
                    y = (m12 + m21) / s,
                    z = 0.25f * s
                ).normalized()
            }
        }
    }
}

/**
 * Axis-aligned boundaries of the virtual room (in meters).
 */
data class RoomBounds(
    val halfWidth: Float = 3.2f,
    val halfDepth: Float = 3.2f,
    val ceilingHeight: Float = 3.0f,
    val playerRadius: Float = 0.42f,
    val eyeHeight: Float = 1.60f
) {
    val minX: Float get() = -halfWidth + playerRadius
    val maxX: Float get() = halfWidth - playerRadius
    val minZ: Float get() = -halfDepth + playerRadius
    val maxZ: Float get() = halfDepth - playerRadius

    fun clampPlayerPosition(pos: Vec3): Vec3 {
        return Vec3(
            x = pos.x.coerceIn(minX, maxX),
            y = eyeHeight,
            z = pos.z.coerceIn(minZ, maxZ)
        )
    }
}

/**
 * Abstraction for 6DoF head tracking so Virtual Room, world-locked windows, and gaze/hand input
 * work identically across 3DoF+locomotion ([GyroPoseProvider]), ARCore ([ArCorePoseProvider]),
 * or PC-streamed SLAM ([RemoteSlamPoseProvider]).
 */
interface PoseProvider {
    val providerName: String
    val headPosition: Vec3
    val headRotation: Quaternion

    fun start()
    fun stop()
    fun recenter()

    /**
     * Writes the 4x4 column-major OpenGL world-to-camera view matrix (R * T(-headPosition)) into [dest].
     */
    fun getViewMatrix(dest: FloatArray)
}

/**
 * Default 6DoF-compatible pose provider combining 3DoF gyroscope orientation from [HeadTracker]
 * with locomotion-controlled world position, snap-turning, floor teleport, and room wall collision.
 */
class GyroPoseProvider(
    private val headTracker: HeadTracker,
    val roomBounds: RoomBounds = RoomBounds()
) : PoseProvider {

    override val providerName: String = "GyroPoseProvider (3DoF + Locomotion)"

    @Volatile
    private var _headPosition: Vec3 = Vec3(0f, roomBounds.eyeHeight, 0f)
    override val headPosition: Vec3
        get() = _headPosition

    @Volatile
    private var _headRotation: Quaternion = Quaternion.IDENTITY
    override val headRotation: Quaternion
        get() = _headRotation

    @Volatile
    private var snapTurnYawDeg: Float = 0f

    private val baseRotationMatrix = FloatArray(16)
    private val snapTurnMatrix = FloatArray(16)
    private val combinedRotationMatrix = FloatArray(16)
    private val translationMatrix = FloatArray(16)

    init {
        Matrix.setIdentityM(baseRotationMatrix, 0)
        Matrix.setIdentityM(snapTurnMatrix, 0)
        Matrix.setIdentityM(combinedRotationMatrix, 0)
        Matrix.setIdentityM(translationMatrix, 0)
    }

    override fun start() {
        headTracker.start()
    }

    override fun stop() {
        headTracker.stop()
    }

    @Synchronized
    override fun recenter() {
        headTracker.recenter()
        snapTurnYawDeg = 0f
        _headPosition = Vec3(0f, roomBounds.eyeHeight, 0f)
    }

    @Synchronized
    fun recenterYawOnly() {
        headTracker.recenter()
        snapTurnYawDeg = 0f
    }

    @Synchronized
    fun snapTurn(deltaDegrees: Float) {
        snapTurnYawDeg = ((snapTurnYawDeg + deltaDegrees) % 360f + 360f) % 360f
    }

    @Synchronized
    fun teleportTo(worldX: Float, worldZ: Float) {
        _headPosition = roomBounds.clampPlayerPosition(
            Vec3(x = worldX, y = roomBounds.eyeHeight, z = worldZ)
        )
    }

    /**
     * Moves the user horizontally in the Virtual Room at walking speed (1.9 m/s) along their
     * current look direction on the XZ plane, enforcing collision against room walls and props.
     */
    @Synchronized
    fun applyWalkingInput(strafeRight: Float, moveForward: Float, dtSec: Float) {
        if (kotlin.math.abs(strafeRight) < 0.01f && kotlin.math.abs(moveForward) < 0.01f) return
        val dt = dtSec.coerceIn(0.001f, 0.1f)

        getCombinedRotationMatrix(combinedRotationMatrix)
        // Forward vector (-Z_cam) in world space = (-m[2], -m[6], -m[10])
        var fwdX = -combinedRotationMatrix[2]
        var fwdZ = -combinedRotationMatrix[10]
        val fwdLen = sqrt(fwdX * fwdX + fwdZ * fwdZ).coerceAtLeast(1e-5f)
        fwdX /= fwdLen
        fwdZ /= fwdLen

        // Right vector (+X_cam) on XZ plane = (-fwdZ, fwdX)
        val rightX = -fwdZ
        val rightZ = fwdX

        val walkSpeedMps = 1.95f
        val dx = (fwdX * moveForward + rightX * strafeRight) * walkSpeedMps * dt
        val dz = (fwdZ * moveForward + rightZ * strafeRight) * walkSpeedMps * dt

        val candidate = roomBounds.clampPlayerPosition(
            Vec3(
                x = _headPosition.x + dx,
                y = roomBounds.eyeHeight,
                z = _headPosition.z + dz
            )
        )
        _headPosition = resolvePropCollisions(candidate)
    }

    private fun resolvePropCollisions(pos: Vec3): Vec3 {
        // Keep a comfortable collision buffer around the low-poly desk and pedestal
        var x = pos.x
        var z = pos.z

        // Desk AABB near back-left (-2.25, -2.35)
        if (x in -2.95f..-1.55f && z in -2.95f..-1.75f) {
            val pushZ = -1.75f
            val pushX = -1.55f
            if (kotlin.math.abs(z - pushZ) < kotlin.math.abs(x - pushX)) {
                z = pushZ
            } else {
                x = pushX
            }
        }
        // Pedestal AABB near back-right (2.25, -2.25)
        if (x in 1.75f..2.85f && z in -2.85f..-1.75f) {
            val dx = x - 2.25f
            val dz = z + 2.25f
            val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
            if (dist < 0.62f) {
                x = 2.25f + (dx / dist) * 0.62f
                z = -2.25f + (dz / dist) * 0.62f
            }
        }
        return roomBounds.clampPlayerPosition(Vec3(x, roomBounds.eyeHeight, z))
    }

    @Synchronized
    private fun getCombinedRotationMatrix(dest: FloatArray) {
        headTracker.getViewMatrix(baseRotationMatrix)
        // Apply snap-turn rotation around world Y before gyro rotation
        Matrix.setRotateM(snapTurnMatrix, 0, snapTurnYawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(dest, 0, baseRotationMatrix, 0, snapTurnMatrix, 0)
        _headRotation = Quaternion.fromRotationMatrix(dest)
    }

    @Synchronized
    override fun getViewMatrix(dest: FloatArray) {
        getCombinedRotationMatrix(combinedRotationMatrix)
        Matrix.setIdentityM(translationMatrix, 0)
        Matrix.translateM(
            translationMatrix,
            0,
            -_headPosition.x,
            -_headPosition.y,
            -_headPosition.z
        )
        Matrix.multiplyMM(dest, 0, combinedRotationMatrix, 0, translationMatrix, 0)
    }
}
