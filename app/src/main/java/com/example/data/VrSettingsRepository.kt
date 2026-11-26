package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "visionbox_settings")

enum class WindowKind {
    HOME,
    VIDEO,
    NOTES,
    SETTINGS,
    AI_VISION
}

/**
 * Persistent world-space spatial window (tab) state.
 * Coordinates ([posX], [posY], [posZ]) are in Virtual Room / World meters (Y-up, eye level ~1.60m).
 */
data class SpatialWindowState(
    val id: String,
    val kind: WindowKind,
    val title: String,
    val posX: Float,
    val posY: Float,
    val posZ: Float,
    val yawDeg: Float,
    val pitchDeg: Float = 0f,
    val scale: Float = 1.0f,
    val isOpen: Boolean = true,
    val isWallSnapped: Boolean = false,
    val noteText: String = ""
)

data class VrSettings(
    val ipdOffset: Float = 0.0f,            // Normalized stereo horizontal shift (-0.12f .. +0.12f)
    val distortionK1: Float = 0.22f,        // Barrel distortion coefficient (0.00f .. 0.55f)
    val lensScale: Float = 0.92f,           // Viewport scale inside lens (0.65f .. 1.15f)
    val panelDistanceMeters: Float = 2.0f,  // Default spawn distance (0.8f .. 4.0f)
    val gazeDurationSec: Float = 1.5f,      // Dwell click time in seconds (0.8f .. 3.0f)
    val cameraRotationOffsetDeg: Int = 0,   // User-adjustable 90° step offset (0, 90, 180, 270)
    val handTrackingEnabled: Boolean = true,// MediaPipe Hand Landmarker toggle
    val isVirtualRoomMode: Boolean = true,  // Virtual Room (true) vs Camera Passthrough (false)
    val floorTeleportEnabled: Boolean = true,
    val videoUrl: String = DEFAULT_SAMPLE_VIDEO_URL,
    val showDebugOverlay: Boolean = true,
    val windows: List<SpatialWindowState> = defaultWindows()
) {
    val ipdMillimeters: Int
        get() = (64 + (ipdOffset * 100f)).toInt()

    val halfIpdMeters: Float
        get() = (ipdMillimeters / 1000f) * 0.5f

    companion object {
        const val DEFAULT_SAMPLE_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
        const val SECOND_SAMPLE_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4"

        const val DEFAULT_NOTE_SAMPLE =
            "VisionBox Spatial Notes\n• Left Stick: Walk around room\n• Right Stick / L1-R1: Snap turn\n• Floor Gaze (1.5s): Teleport\n• Pinch Window: Move & Wall-Snap\n• 2-Hand Pinch Spread: Resize"

        fun defaultWindows(): List<SpatialWindowState> = listOf(
            SpatialWindowState(
                id = "home",
                kind = WindowKind.HOME,
                title = "VISIONBOX HOME",
                posX = 0.0f,
                posY = 1.56f,
                posZ = -2.00f,
                yawDeg = 0f,
                pitchDeg = -2f,
                scale = 1.0f,
                isOpen = true,
                isWallSnapped = false
            ),
            SpatialWindowState(
                id = "video",
                kind = WindowKind.VIDEO,
                title = "SPATIAL CINEMA",
                posX = -1.62f,
                posY = 1.62f,
                posZ = -1.48f,
                yawDeg = -46f,
                pitchDeg = 0f,
                scale = 1.0f,
                isOpen = true,
                isWallSnapped = false
            ),
            SpatialWindowState(
                id = "settings",
                kind = WindowKind.SETTINGS,
                title = "OPTICS & ROOM",
                posX = 1.62f,
                posY = 1.62f,
                posZ = -1.48f,
                yawDeg = 46f,
                pitchDeg = 0f,
                scale = 1.0f,
                isOpen = true,
                isWallSnapped = false
            ),
            SpatialWindowState(
                id = "notes",
                kind = WindowKind.NOTES,
                title = "SPATIAL NOTES",
                posX = -3.02f,
                posY = 1.68f,
                posZ = -0.35f,
                yawDeg = -90f,
                pitchDeg = 0f,
                scale = 1.05f,
                isOpen = true,
                isWallSnapped = true,
                noteText = DEFAULT_NOTE_SAMPLE
            ),
            SpatialWindowState(
                id = "ai_vision",
                kind = WindowKind.AI_VISION,
                title = "WHAT AM I SEEING?",
                posX = 0.0f,
                posY = 2.38f,
                posZ = -2.00f,
                yawDeg = 0f,
                pitchDeg = 12f,
                scale = 1.0f,
                isOpen = false,
                isWallSnapped = false
            )
        )
    }
}

class VrSettingsRepository(private val context: Context) {

    private object Keys {
        val IPD_OFFSET = floatPreferencesKey("ipd_offset")
        val DISTORTION_K1 = floatPreferencesKey("distortion_k1")
        val LENS_SCALE = floatPreferencesKey("lens_scale")
        val PANEL_DISTANCE = floatPreferencesKey("panel_distance_m")
        val GAZE_DURATION = floatPreferencesKey("gaze_duration_s")
        val CAMERA_ROT_OFFSET = intPreferencesKey("camera_rot_offset_deg")
        val HAND_TRACKING_ENABLED = booleanPreferencesKey("hand_tracking_enabled")
        val VIRTUAL_ROOM_MODE = booleanPreferencesKey("virtual_room_mode")
        val FLOOR_TELEPORT_ENABLED = booleanPreferencesKey("floor_teleport_enabled")
        val VIDEO_URL = stringPreferencesKey("video_url")
        val SHOW_DEBUG = booleanPreferencesKey("show_debug_overlay")
        val SAVED_WINDOWS_JSON = stringPreferencesKey("saved_spatial_windows_v1")
    }

    val settingsFlow: Flow<VrSettings> = context.dataStore.data.map { prefs ->
        val defaultDist = (prefs[Keys.PANEL_DISTANCE] ?: 2.0f).coerceIn(0.8f, 4.0f)
        val parsedWindows = deserializeWindows(prefs[Keys.SAVED_WINDOWS_JSON])
            ?: VrSettings.defaultWindows()

        VrSettings(
            ipdOffset = (prefs[Keys.IPD_OFFSET] ?: 0.0f).coerceIn(-0.12f, 0.12f),
            distortionK1 = (prefs[Keys.DISTORTION_K1] ?: 0.22f).coerceIn(0.0f, 0.55f),
            lensScale = (prefs[Keys.LENS_SCALE] ?: 0.92f).coerceIn(0.65f, 1.15f),
            panelDistanceMeters = defaultDist,
            gazeDurationSec = (prefs[Keys.GAZE_DURATION] ?: 1.5f).coerceIn(0.8f, 3.0f),
            cameraRotationOffsetDeg = ((prefs[Keys.CAMERA_ROT_OFFSET] ?: 0) % 360 + 360) % 360,
            handTrackingEnabled = prefs[Keys.HAND_TRACKING_ENABLED] ?: true,
            isVirtualRoomMode = prefs[Keys.VIRTUAL_ROOM_MODE] ?: true,
            floorTeleportEnabled = prefs[Keys.FLOOR_TELEPORT_ENABLED] ?: true,
            videoUrl = prefs[Keys.VIDEO_URL]?.takeIf { it.isNotBlank() }
                ?: VrSettings.DEFAULT_SAMPLE_VIDEO_URL,
            showDebugOverlay = prefs[Keys.SHOW_DEBUG] ?: true,
            windows = parsedWindows
        )
    }

    suspend fun updateIpdOffset(value: Float) {
        context.dataStore.edit { it[Keys.IPD_OFFSET] = value.coerceIn(-0.12f, 0.12f) }
    }

    suspend fun updateDistortion(value: Float) {
        context.dataStore.edit { it[Keys.DISTORTION_K1] = value.coerceIn(0.0f, 0.55f) }
    }

    suspend fun updateLensScale(value: Float) {
        context.dataStore.edit { it[Keys.LENS_SCALE] = value.coerceIn(0.65f, 1.15f) }
    }

    suspend fun updatePanelDistance(value: Float) {
        context.dataStore.edit { it[Keys.PANEL_DISTANCE] = value.coerceIn(0.8f, 4.0f) }
    }

    suspend fun updateGazeDuration(value: Float) {
        context.dataStore.edit { it[Keys.GAZE_DURATION] = value.coerceIn(0.8f, 3.0f) }
    }

    suspend fun updateHandTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAND_TRACKING_ENABLED] = enabled }
    }

    suspend fun updateVirtualRoomMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIRTUAL_ROOM_MODE] = enabled }
    }

    suspend fun updateFloorTeleportEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FLOOR_TELEPORT_ENABLED] = enabled }
    }

    suspend fun rotateCamera90() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CAMERA_ROT_OFFSET] ?: 0
            prefs[Keys.CAMERA_ROT_OFFSET] = (current + 90) % 360
        }
    }

    suspend fun updateVideoUrl(url: String) {
        context.dataStore.edit {
            it[Keys.VIDEO_URL] = url.trim().ifEmpty { VrSettings.DEFAULT_SAMPLE_VIDEO_URL }
        }
    }

    suspend fun updateShowDebug(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_DEBUG] = show }
    }

    suspend fun saveWindows(windows: List<SpatialWindowState>) {
        val jsonStr = serializeWindows(windows)
        context.dataStore.edit { prefs ->
            prefs[Keys.SAVED_WINDOWS_JSON] = jsonStr
        }
    }

    suspend fun resetAllPanelPlacements() {
        saveWindows(VrSettings.defaultWindows())
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { prefs ->
            prefs[Keys.IPD_OFFSET] = 0.0f
            prefs[Keys.DISTORTION_K1] = 0.22f
            prefs[Keys.LENS_SCALE] = 0.92f
            prefs[Keys.PANEL_DISTANCE] = 2.0f
            prefs[Keys.GAZE_DURATION] = 1.5f
            prefs[Keys.VIRTUAL_ROOM_MODE] = true
            prefs[Keys.FLOOR_TELEPORT_ENABLED] = true
            prefs[Keys.SAVED_WINDOWS_JSON] = serializeWindows(VrSettings.defaultWindows())
        }
    }

    private fun serializeWindows(windows: List<SpatialWindowState>): String {
        val arr = JSONArray()
        for (w in windows) {
            arr.put(
                JSONObject().apply {
                    put("id", w.id)
                    put("kind", w.kind.name)
                    put("title", w.title)
                    put("posX", w.posX.toDouble())
                    put("posY", w.posY.toDouble())
                    put("posZ", w.posZ.toDouble())
                    put("yawDeg", w.yawDeg.toDouble())
                    put("pitchDeg", w.pitchDeg.toDouble())
                    put("scale", w.scale.toDouble())
                    put("isOpen", w.isOpen)
                    put("isWallSnapped", w.isWallSnapped)
                    put("noteText", w.noteText)
                }
            )
        }
        return arr.toString()
    }

    private fun deserializeWindows(rawJson: String?): List<SpatialWindowState>? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(rawJson)
            val result = mutableListOf<SpatialWindowState>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val kindStr = obj.optString("kind")
                val kind = runCatching { WindowKind.valueOf(kindStr) }.getOrNull() ?: continue
                result.add(
                    SpatialWindowState(
                        id = id,
                        kind = kind,
                        title = obj.optString("title", kind.name),
                        posX = obj.optDouble("posX", 0.0).toFloat().coerceIn(-3.12f, 3.12f),
                        posY = obj.optDouble("posY", 1.60).toFloat().coerceIn(0.45f, 2.75f),
                        posZ = obj.optDouble("posZ", -2.0).toFloat().coerceIn(-3.12f, 3.12f),
                        yawDeg = obj.optDouble("yawDeg", 0.0).toFloat(),
                        pitchDeg = obj.optDouble("pitchDeg", 0.0).toFloat().coerceIn(-55f, 55f),
                        scale = obj.optDouble("scale", 1.0).toFloat().coerceIn(0.55f, 2.20f),
                        isOpen = obj.optBoolean("isOpen", true),
                        isWallSnapped = obj.optBoolean("isWallSnapped", false),
                        noteText = obj.optString("noteText", "")
                    )
                )
            }
            if (result.any { it.kind == WindowKind.HOME }) result else null
        } catch (_: Exception) {
            null
        }
    }
}
