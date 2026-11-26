package com.example.vr.pose

import android.opengl.Matrix
import android.util.Log

/**
 * Clean stub for future on-device ARCore 6DoF inside-out tracking without changing
 * the Virtual Room, spatial window system, or input layer.
 */
@Suppress("unused")
class ArCorePoseProvider : PoseProvider {

    override val providerName: String = "ArCorePoseProvider (Stub)"

    override var headPosition: Vec3 = Vec3(0f, 1.60f, 0f)
        private set

    override var headRotation: Quaternion = Quaternion.IDENTITY
        private set

    override fun start() {
        Log.d(TAG, "ArCorePoseProvider stub started (no ARCore dependency active).")
    }

    override fun stop() {
        Log.d(TAG, "ArCorePoseProvider stub stopped.")
    }

    override fun recenter() {
        headPosition = Vec3(0f, 1.60f, 0f)
        headRotation = Quaternion.IDENTITY
    }

    override fun getViewMatrix(dest: FloatArray) {
        Matrix.setIdentityM(dest, 0)
        Matrix.translateM(dest, 0, -headPosition.x, -headPosition.y, -headPosition.z)
    }

    companion object {
        private const val TAG = "ArCorePoseProvider"
    }
}

/**
 * Clean stub for future PC-companion 6DoF SLAM streaming over WebSocket (e.g., ORB-SLAM3 / OpenVR
 * pose packets streamed from desktop). Implements [PoseProvider] so room rendering and windows
 * require zero changes once connected.
 */
@Suppress("unused")
class RemoteSlamPoseProvider(
    val webSocketEndpoint: String = "ws://192.168.1.100:8765/pose"
) : PoseProvider {

    override val providerName: String = "RemoteSlamPoseProvider (WebSocket Stub)"

    @Volatile
    override var headPosition: Vec3 = Vec3(0f, 1.60f, 0f)
        private set

    @Volatile
    override var headRotation: Quaternion = Quaternion.IDENTITY
        private set

    override fun start() {
        Log.d(TAG, "RemoteSlamPoseProvider stub ready for WebSocket stream: $webSocketEndpoint")
    }

    override fun stop() {
        Log.d(TAG, "RemoteSlamPoseProvider stub disconnected.")
    }

    override fun recenter() {
        headPosition = Vec3(0f, 1.60f, 0f)
        headRotation = Quaternion.IDENTITY
    }

    /**
     * Hook for future WebSocket packet handler: parses incoming 6DoF pose (meters + quaternion).
     */
    fun onRemotePosePacket(x: Float, y: Float, z: Float, qw: Float, qx: Float, qy: Float, qz: Float) {
        headPosition = Vec3(x, y, z)
        headRotation = Quaternion(qw, qx, qy, qz).normalized()
    }

    override fun getViewMatrix(dest: FloatArray) {
        Matrix.setIdentityM(dest, 0)
        Matrix.translateM(dest, 0, -headPosition.x, -headPosition.y, -headPosition.z)
    }

    companion object {
        private const val TAG = "RemoteSlamPoseProvider"
    }
}
