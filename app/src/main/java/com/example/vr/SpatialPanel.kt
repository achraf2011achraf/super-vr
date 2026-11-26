package com.example.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.SpatialWindowState
import com.example.data.VrSettings
import com.example.data.WindowKind
import com.example.vr.ai.VisionAiState
import com.example.vr.pose.Vec3
import java.util.Locale
import kotlin.math.abs

data class PanelButton(
    val id: String,
    val label: String,
    val rectPx: RectF,
    val accentColor: Int = Color.parseColor("#00E5FF")
) {
    fun containsUv(u: Float, v: Float, canvasWidth: Int, canvasHeight: Int): Boolean {
        val px = u * canvasWidth
        val py = v * canvasHeight
        return rectPx.contains(px, py)
    }
}

class SpatialPanel(
    val id: String,
    val kind: WindowKind,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val baseWidthMeters: Float,
    val baseHeightMeters: Float,
    var posX: Float = 0f,
    var posY: Float = 1.60f,
    var posZ: Float = -2.0f,
    var yawAngleDeg: Float = 0f,
    var pitchAngleDeg: Float = 0f,
    var panelScale: Float = 1.0f,
    var isVisible: Boolean = true,
    var isWallSnapped: Boolean = false,
    var title: String = kind.name,
    var noteText: String = ""
) {
    val bitmap: Bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    val canvas: Canvas = Canvas(bitmap)
    val modelMatrix: FloatArray = FloatArray(16)
    val inverseModelMatrix: FloatArray = FloatArray(16)
    val buttons: MutableList<PanelButton> = mutableListOf()

    var glTextureId: Int = 0
    @Volatile
    var isTextureDirty: Boolean = true

    fun applyState(state: SpatialWindowState) {
        posX = state.posX
        posY = state.posY
        posZ = state.posZ
        yawAngleDeg = state.yawDeg
        pitchAngleDeg = state.pitchDeg
        panelScale = state.scale.coerceIn(0.55f, 2.20f)
        isVisible = state.isOpen
        isWallSnapped = state.isWallSnapped
        title = state.title
        noteText = state.noteText
    }

    /**
     * Positions the panel in 3D WORLD coordinates ([posX], [posY], [posZ]), oriented upright
     * with gravity (0° roll) at ([yawAngleDeg], [pitchAngleDeg]) and scaled by [panelScale].
     */
    fun updateTransform() {
        val s = panelScale.coerceIn(0.55f, 2.20f)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ)
        Matrix.rotateM(modelMatrix, 0, -yawAngleDeg, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, pitchAngleDeg, 1f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, baseWidthMeters * s, baseHeightMeters * s, 1f)
        Matrix.invertM(inverseModelMatrix, 0, modelMatrix, 0)
    }

    fun uploadTextureIfNeeded() {
        if (glTextureId == 0) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            glTextureId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            isTextureDirty = false
        } else if (isTextureDirty) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            isTextureDirty = false
        }
    }

    fun releaseGlTexture() {
        if (glTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(glTextureId), 0)
            glTextureId = 0
        }
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

object SpatialPanelCanvasRenderer {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9EB0D2")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val buttonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val buttonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bodyTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 23f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private fun drawGlassFrame(
        canvas: Canvas,
        w: Float,
        h: Float,
        accentColor: Int,
        isPlacing: Boolean = false,
        isWallSnapped: Boolean = false
    ) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val rect = RectF(6f, 6f, w - 6f, h - 6f)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(232, 15, 22, 38),
            Color.argb(242, 8, 12, 22),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 34f, 34f, bgPaint)
        bgPaint.shader = null

        borderPaint.color = when {
            isPlacing -> Color.parseColor("#00E676")
            isWallSnapped -> Color.parseColor("#38BDF8")
            else -> accentColor
        }
        borderPaint.strokeWidth = if (isPlacing) 5.5f else 3.2f
        borderPaint.alpha = if (isPlacing) 255 else 195
        canvas.drawRoundRect(rect, 34f, 34f, borderPaint)
    }

    private fun drawButton(
        canvas: Canvas,
        btn: PanelButton,
        hoveredButtonId: String?,
        hoverProgress: Float
    ) {
        val isHovered = btn.id == hoveredButtonId
        buttonFillPaint.color = if (isHovered) {
            Color.argb(195, 0, 110, 150)
        } else {
            Color.argb(175, 24, 35, 58)
        }
        canvas.drawRoundRect(btn.rectPx, 16f, 16f, buttonFillPaint)

        if (isHovered && hoverProgress > 0f) {
            val fillRect = RectF(
                btn.rectPx.left,
                btn.rectPx.top,
                btn.rectPx.left + btn.rectPx.width() * hoverProgress.coerceIn(0f, 1f),
                btn.rectPx.bottom
            )
            buttonFillPaint.color = Color.argb(145, 0, 229, 255)
            canvas.drawRoundRect(fillRect, 16f, 16f, buttonFillPaint)
        }

        buttonStrokePaint.color = if (isHovered) Color.parseColor("#00E5FF") else btn.accentColor
        buttonStrokePaint.alpha = if (isHovered) 255 else 160
        canvas.drawRoundRect(btn.rectPx, 16f, 16f, buttonStrokePaint)

        buttonTextPaint.textSize = (btn.rectPx.height() * 0.34f).coerceIn(16f, 25f)
        buttonTextPaint.color = Color.WHITE
        val textY = btn.rectPx.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText(btn.label, btn.rectPx.centerX(), textY, buttonTextPaint)
    }

    /**
     * Draws a uniform window header toolbar with:
     * - [PLACE] (gaze placement fallback)
     * - [S-] / [S+] (resize fallback alongside 2-hand pinch spread)
     * - [X] close button (omitted for Home panel so user always keeps main menu)
     */
    private fun drawWindowHeaderBar(
        canvas: Canvas,
        panel: SpatialPanel,
        isPlacingThisPanel: Boolean,
        allowClose: Boolean,
        hoveredButtonId: String?,
        hoverProgress: Float
    ) {
        val w = panel.bitmapWidth.toFloat()
        val top = 18f
        val btnH = 44f
        var right = w - 26f

        if (allowClose) {
            val closeRect = RectF(right - 46f, top, right, top + btnH)
            val closeBtn = PanelButton(
                id = "win:${panel.id}:close",
                label = "X",
                rectPx = closeRect,
                accentColor = Color.parseColor("#FF5252")
            )
            panel.buttons.add(closeBtn)
            drawButton(canvas, closeBtn, hoveredButtonId, hoverProgress)
            right = closeRect.left - 10f
        }

        val scalePlusRect = RectF(right - 52f, top, right, top + btnH)
        val scaleTextX = scalePlusRect.left - 34f
        val scaleMinusRect = RectF(scalePlusRect.left - 68f - 52f, top, scalePlusRect.left - 68f, top + btnH)
        val placeW = if (isPlacingThisPanel) 170f else 112f
        val placeRect = RectF(scaleMinusRect.left - 10f - placeW, top, scaleMinusRect.left - 10f, top + btnH)

        val placeBtn = PanelButton(
            id = "win:${panel.id}:place",
            label = if (isPlacingThisPanel) "LOOK TO DROP" else "PLACE",
            rectPx = placeRect,
            accentColor = if (isPlacingThisPanel) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
        )
        val sMinusBtn = PanelButton(
            id = "win:${panel.id}:scale_minus",
            label = "S-",
            rectPx = scaleMinusRect,
            accentColor = Color.parseColor("#536DFE")
        )
        val sPlusBtn = PanelButton(
            id = "win:${panel.id}:scale_plus",
            label = "S+",
            rectPx = scalePlusRect,
            accentColor = Color.parseColor("#00E5FF")
        )

        panel.buttons.add(placeBtn)
        panel.buttons.add(sMinusBtn)
        panel.buttons.add(sPlusBtn)

        drawButton(canvas, placeBtn, hoveredButtonId, hoverProgress)
        drawButton(canvas, sMinusBtn, hoveredButtonId, hoverProgress)
        drawButton(canvas, sPlusBtn, hoveredButtonId, hoverProgress)

        monoPaint.textSize = 18f
        monoPaint.color = Color.parseColor("#00E5FF")
        val scaleStr = String.format(Locale.US, "%.1fx", panel.panelScale)
        val tw = monoPaint.measureText(scaleStr)
        canvas.drawText(scaleStr, scaleTextX - tw / 2f, top + 29f, monoPaint)
    }

    fun renderHomePanel(
        panel: SpatialPanel,
        hoveredButtonId: String?,
        hoverProgress: Float,
        isVirtualRoom: Boolean,
        videoOpen: Boolean,
        notesOpen: Boolean,
        settingsOpen: Boolean,
        aiState: VisionAiState,
        headPos: Vec3,
        placingWindowId: String?,
        battery: BatteryTelemetry
    ) {
        val isPlacing = placingWindowId == panel.id
        val c = panel.canvas
        val w = panel.bitmapWidth.toFloat()
        val h = panel.bitmapHeight.toFloat()
        drawGlassFrame(c, w, h, Color.parseColor("#00E5FF"), isPlacing, panel.isWallSnapped)

        panel.buttons.clear()

        titlePaint.textSize = 29f
        c.drawText("VISIONBOX HOME", 28f, 50f, titlePaint)

        drawWindowHeaderBar(
            canvas = c,
            panel = panel,
            isPlacingThisPanel = isPlacing,
            allowClose = false,
            hoveredButtonId = hoveredButtonId,
            hoverProgress = hoverProgress
        )

        subtitlePaint.textSize = 18f
        val sub = if (placingWindowId != null) {
            "PLACE MODE: Look anywhere (1.5s dwell) to lock window in 3D space"
        } else {
            String.format(
                Locale.US,
                "Head (%.1f, %.1f, %.1f)m • %s • Vol- AI Vision",
                headPos.x,
                headPos.y,
                headPos.z,
                if (isVirtualRoom) "VIRTUAL ROOM" else "PASSTHROUGH"
            )
        }
        c.drawText(sub, 28f, 86f, subtitlePaint)

        monoPaint.textSize = 17f
        monoPaint.color = if (battery.hasWarning) Color.parseColor("#FFAB00") else Color.parseColor("#00E676")
        c.drawText(
            String.format(Locale.US, "BAT %d%% • %.1f°C • PINCH=MOVE • 2-HAND SPREAD=RESIZE", battery.levelPercent, battery.temperatureCelsius),
            28f,
            114f,
            monoPaint
        )

        val pad = 26f
        val gapX = 12f
        val gapY = 12f
        val colW = (w - pad * 2 - gapX) / 2f
        val rowH = 68f
        val topY = 132f

        val roomLabel = if (isVirtualRoom) "MODE: VIRTUAL ROOM" else "MODE: PASSTHROUGH"
        val whatLabel = when {
            aiState.isListening -> "LISTENING..."
            aiState.isAiCameraActive -> "AI CAMERA ACTIVE"
            else -> "WHAT AM I SEEING?"
        }

        val btns = listOf(
            // Row 1: Virtual Room vs Passthrough + New Window
            PanelButton(
                id = "win:home:toggle_room",
                label = roomLabel,
                rectPx = RectF(pad, topY, pad + colW, topY + rowH),
                accentColor = if (isVirtualRoom) Color.parseColor("#00E676") else Color.parseColor("#38BDF8")
            ),
            PanelButton(
                id = "win:home:new_window",
                label = "+ NEW WINDOW (NOTE)",
                rectPx = RectF(pad + colW + gapX, topY, pad + colW * 2 + gapX, topY + rowH),
                accentColor = Color.parseColor("#00E5FF")
            ),
            // Row 2: Video + Notes
            PanelButton(
                id = "win:home:toggle_video",
                label = if (videoOpen) "VIDEO TAB: OPEN" else "OPEN VIDEO TAB",
                rectPx = RectF(pad, topY + rowH + gapY, pad + colW, topY + rowH * 2 + gapY),
                accentColor = if (videoOpen) Color.parseColor("#00E676") else Color.parseColor("#536DFE")
            ),
            PanelButton(
                id = "win:home:toggle_notes",
                label = if (notesOpen) "NOTES TAB: OPEN" else "OPEN NOTES TAB",
                rectPx = RectF(pad + colW + gapX, topY + rowH + gapY, pad + colW * 2 + gapX, topY + rowH * 2 + gapY),
                accentColor = if (notesOpen) Color.parseColor("#00E676") else Color.parseColor("#536DFE")
            ),
            // Row 3: Settings + What Am I Seeing?
            PanelButton(
                id = "win:home:toggle_settings",
                label = if (settingsOpen) "SETTINGS: OPEN" else "OPEN SETTINGS",
                rectPx = RectF(pad, topY + (rowH + gapY) * 2, pad + colW, topY + rowH * 3 + gapY * 2),
                accentColor = if (settingsOpen) Color.parseColor("#00E676") else Color.parseColor("#536DFE")
            ),
            PanelButton(
                id = "win:home:what_am_i_seeing",
                label = whatLabel,
                rectPx = RectF(pad + colW + gapX, topY + (rowH + gapY) * 2, pad + colW * 2 + gapX, topY + rowH * 3 + gapY * 2),
                accentColor = if (aiState.isAiCameraActive) Color.parseColor("#FF6D00") else Color.parseColor("#00E5FF")
            )
        )
        panel.buttons.addAll(btns)
        for (btn in btns) drawButton(c, btn, hoveredButtonId, hoverProgress)

        // Row 4: 4 compact movement & lifecycle buttons (Turn -30°, Turn +30°, Recenter, Exit VR)
        val r4Top = topY + (rowH + gapY) * 3
        val r4H = 62f
        val r4W = (w - pad * 2 - gapX * 3) / 4f
        val bottomBtns = listOf(
            PanelButton("win:home:snap_left", "TURN -30°", RectF(pad, r4Top, pad + r4W, r4Top + r4H), Color.parseColor("#536DFE")),
            PanelButton("win:home:snap_right", "TURN +30°", RectF(pad + (r4W + gapX), r4Top, pad + (r4W + gapX) + r4W, r4Top + r4H), Color.parseColor("#536DFE")),
            PanelButton("win:home:recenter", "RECENTER", RectF(pad + (r4W + gapX) * 2, r4Top, pad + (r4W + gapX) * 2 + r4W, r4Top + r4H), Color.parseColor("#00E5FF")),
            PanelButton("win:home:exit", "EXIT VR", RectF(pad + (r4W + gapX) * 3, r4Top, pad + (r4W + gapX) * 3 + r4W, r4Top + r4H), Color.parseColor("#FF5252"))
        )
        panel.buttons.addAll(bottomBtns)
        for (btn in bottomBtns) drawButton(c, btn, hoveredButtonId, hoverProgress)

        panel.isTextureDirty = true
    }

    fun renderNotesPanel(
        panel: SpatialPanel,
        placingWindowId: String?,
        hoveredButtonId: String?,
        hoverProgress: Float
    ) {
        val isPlacing = placingWindowId == panel.id
        val c = panel.canvas
        val w = panel.bitmapWidth.toFloat()
        val h = panel.bitmapHeight.toFloat()
        drawGlassFrame(c, w, h, Color.parseColor("#38BDF8"), isPlacing, panel.isWallSnapped)

        panel.buttons.clear()

        titlePaint.textSize = 26f
        c.drawText(panel.title.take(20), 28f, 48f, titlePaint)

        drawWindowHeaderBar(
            canvas = c,
            panel = panel,
            isPlacingThisPanel = isPlacing,
            allowClose = true,
            hoveredButtonId = hoveredButtonId,
            hoverProgress = hoverProgress
        )

        monoPaint.textSize = 17f
        monoPaint.color = if (panel.isWallSnapped) Color.parseColor("#38BDF8") else Color.parseColor("#00E676")
        val lockBadge = if (panel.isWallSnapped) "WALL-SNAPPED" else "WORLD-LOCKED"
        c.drawText(
            String.format(Locale.US, "%s • (%.1f, %.1f, %.1f)m", lockBadge, panel.posX, panel.posY, panel.posZ),
            28f,
            88f,
            monoPaint
        )

        val bodyRect = RectF(26f, 106f, w - 26f, h - 102f)
        pillFillPaint.color = Color.argb(150, 12, 18, 34)
        c.drawRoundRect(bodyRect, 18f, 18f, pillFillPaint)
        pillStrokePaint.color = Color.argb(120, 80, 160, 230)
        c.drawRoundRect(bodyRect, 18f, 18f, pillStrokePaint)

        val textContent = panel.noteText.ifBlank {
            "Empty Spatial Note.\nGaze at TEMPLATE or SNAP WALL below, pinch-drag to move, or spread 2 hands to resize."
        }
        bodyTextPaint.color = Color.WHITE
        bodyTextPaint.textSize = 23f
        drawWrappedText(c, textContent, 44f, 124f, (w - 88f).toInt(), maxLines = 8)

        val pad = 26f
        val gap = 12f
        val btnW = (w - pad * 2 - gap * 3) / 4f
        val btnTop = h - 86f
        val btnBottom = h - 18f

        val btns = listOf(
            PanelButton("win:${panel.id}:note_next", "NEXT NOTE", RectF(pad, btnTop, pad + btnW, btnBottom), Color.parseColor("#00E5FF")),
            PanelButton("win:${panel.id}:snap_wall", "SNAP WALL", RectF(pad + (btnW + gap), btnTop, pad + (btnW + gap) + btnW, btnBottom), Color.parseColor("#38BDF8")),
            PanelButton("win:${panel.id}:note_clear", "CLEAR", RectF(pad + (btnW + gap) * 2, btnTop, pad + (btnW + gap) * 2 + btnW, btnBottom), Color.parseColor("#FFAB00")),
            PanelButton("win:${panel.id}:close", "CLOSE", RectF(pad + (btnW + gap) * 3, btnTop, pad + (btnW + gap) * 3 + btnW, btnBottom), Color.parseColor("#FF5252"))
        )
        panel.buttons.addAll(btns)
        for (btn in btns) drawButton(c, btn, hoveredButtonId, hoverProgress)

        panel.isTextureDirty = true
    }

    fun renderSettingsPanel(
        panel: SpatialPanel,
        settings: VrSettings,
        placingWindowId: String?,
        hoveredButtonId: String?,
        hoverProgress: Float
    ) {
        val isPlacing = placingWindowId == panel.id
        val c = panel.canvas
        val w = panel.bitmapWidth.toFloat()
        val h = panel.bitmapHeight.toFloat()
        drawGlassFrame(c, w, h, Color.parseColor("#536DFE"), isPlacing, panel.isWallSnapped)

        panel.buttons.clear()

        titlePaint.textSize = 26f
        c.drawText("OPTICS & ROOM", 28f, 48f, titlePaint)

        drawWindowHeaderBar(
            canvas = c,
            panel = panel,
            isPlacingThisPanel = isPlacing,
            allowClose = true,
            hoveredButtonId = hoveredButtonId,
            hoverProgress = hoverProgress
        )

        data class SettingRow(
            val title: String,
            val formattedValue: String,
            val minusId: String,
            val plusId: String
        )

        val rows = listOf(
            SettingRow("3D IPD Separation", String.format(Locale.US, "%d mm", settings.ipdMillimeters), "set_ipd_minus", "set_ipd_plus"),
            SettingRow("Barrel Distortion", String.format(Locale.US, "k1 = %.2f", settings.distortionK1), "set_dist_minus", "set_dist_plus"),
            SettingRow("Viewport Scale", String.format(Locale.US, "%d%%", (settings.lensScale * 100).toInt()), "set_scale_minus", "set_scale_plus"),
            SettingRow("Gaze Click Time", String.format(Locale.US, "%.1f s", settings.gazeDurationSec), "set_gaze_minus", "set_gaze_plus")
        )

        var startY = 82f
        val rowHeight = 56f
        val rowGap = 10f
        val btnW = 90f

        for (row in rows) {
            titlePaint.textSize = 20f
            c.drawText(row.title, 28f, startY + 24f, titlePaint)

            monoPaint.textSize = 18f
            monoPaint.color = Color.parseColor("#00E5FF")
            c.drawText(row.formattedValue, 28f, startY + 46f, monoPaint)

            val minusRect = RectF(w - 28f - btnW * 2 - 12f, startY + 4f, w - 28f - btnW - 12f, startY + rowHeight)
            val plusRect = RectF(w - 28f - btnW, startY + 4f, w - 28f, startY + rowHeight)

            val minusBtn = PanelButton(row.minusId, " - ", minusRect, Color.parseColor("#536DFE"))
            val plusBtn = PanelButton(row.plusId, " + ", plusRect, Color.parseColor("#00E5FF"))
            panel.buttons.add(minusBtn)
            panel.buttons.add(plusBtn)

            drawButton(c, minusBtn, hoveredButtonId, hoverProgress)
            drawButton(c, plusBtn, hoveredButtonId, hoverProgress)

            startY += rowHeight + rowGap
        }

        // Bottom toggles: Hand Tracking + Floor Teleport + Reset Layout
        val pad = 28f
        val gap = 12f
        val bTop = h - 144f
        val bH = 56f
        val halfW = (w - pad * 2 - gap) / 2f

        val handBtn = PanelButton(
            id = "set_hand_toggle",
            label = if (settings.handTrackingEnabled) "HAND AI: ON" else "HAND AI: OFF",
            rectPx = RectF(pad, bTop, pad + halfW, bTop + bH),
            accentColor = if (settings.handTrackingEnabled) Color.parseColor("#00E676") else Color.parseColor("#FFAB00")
        )
        val tpBtn = PanelButton(
            id = "set_teleport_toggle",
            label = if (settings.floorTeleportEnabled) "TELEPORT: ON" else "TELEPORT: OFF",
            rectPx = RectF(pad + halfW + gap, bTop, pad + halfW * 2 + gap, bTop + bH),
            accentColor = if (settings.floorTeleportEnabled) Color.parseColor("#00E676") else Color.parseColor("#FFAB00")
        )
        val resetBtn = PanelButton(
            id = "win:home:reset_layout",
            label = "RESET ALL WINDOWS TO DEFAULT ROOM SPOTS",
            rectPx = RectF(pad, h - 76f, w - pad, h - 18f),
            accentColor = Color.parseColor("#536DFE")
        )

        panel.buttons.addAll(listOf(handBtn, tpBtn, resetBtn))
        drawButton(c, handBtn, hoveredButtonId, hoverProgress)
        drawButton(c, tpBtn, hoveredButtonId, hoverProgress)
        drawButton(c, resetBtn, hoveredButtonId, hoverProgress)

        panel.isTextureDirty = true
    }

    fun renderVideoControlOverlay(
        panel: SpatialPanel,
        videoState: VideoPlayerState,
        placingWindowId: String?,
        hoveredButtonId: String?,
        hoverProgress: Float
    ) {
        val isPlacing = placingWindowId == panel.id
        val c = panel.canvas
        val w = panel.bitmapWidth.toFloat()
        val h = panel.bitmapHeight.toFloat()
        drawGlassFrame(c, w, h, Color.parseColor("#00E5FF"), isPlacing, panel.isWallSnapped)

        panel.buttons.clear()

        titlePaint.textSize = 25f
        c.drawText("SPATIAL CINEMA", 28f, 48f, titlePaint)

        drawWindowHeaderBar(
            canvas = c,
            panel = panel,
            isPlacingThisPanel = isPlacing,
            allowClose = true,
            hoveredButtonId = hoveredButtonId,
            hoverProgress = hoverProgress
        )

        val videoFrameRect = RectF(28f, 76f, w - 28f, h - 108f)
        borderPaint.color = Color.parseColor("#283754")
        borderPaint.strokeWidth = 2.5f
        c.drawRoundRect(videoFrameRect, 16f, 16f, borderPaint)

        monoPaint.textSize = 18f
        monoPaint.color = Color.parseColor("#00E5FF")
        val posSec = (videoState.currentPositionMs / 1000L).coerceAtLeast(0L)
        val durSec = (videoState.durationMs / 1000L).coerceAtLeast(0L)
        val stateBadge = when {
            videoState.errorMessage != null -> "ERR"
            videoState.isBuffering -> "BUFFERING"
            videoState.isPlaying -> "PLAYING"
            else -> "PAUSED"
        }
        val timeText = String.format(
            Locale.US,
            "%s %02d:%02d/%02d:%02d",
            stateBadge,
            posSec / 60,
            posSec % 60,
            durSec / 60,
            durSec % 60
        )
        c.drawText(timeText, 28f, 72f, monoPaint)

        val pad = 28f
        val gap = 14f
        val btnCount = 4
        val btnW = (w - pad * 2 - gap * (btnCount - 1)) / btnCount
        val btnTop = h - 92f
        val btnBottom = h - 20f

        val playLabel = if (videoState.isPlaying) "PAUSE" else "PLAY"
        val btns = listOf(
            PanelButton("vid_play_pause", playLabel, RectF(pad, btnTop, pad + btnW, btnBottom), Color.parseColor("#00E676")),
            PanelButton("vid_back_10", "-10s", RectF(pad + (btnW + gap), btnTop, pad + (btnW + gap) + btnW, btnBottom), Color.parseColor("#00E5FF")),
            PanelButton("vid_fwd_10", "+10s", RectF(pad + (btnW + gap) * 2, btnTop, pad + (btnW + gap) * 2 + btnW, btnBottom), Color.parseColor("#00E5FF")),
            PanelButton("win:${panel.id}:close", "CLOSE", RectF(pad + (btnW + gap) * 3, btnTop, pad + (btnW + gap) * 3 + btnW, btnBottom), Color.parseColor("#FF5252"))
        )

        panel.buttons.addAll(btns)
        for (btn in btns) drawButton(c, btn, hoveredButtonId, hoverProgress)

        panel.isTextureDirty = true
    }

    fun renderWarningPanel(panel: SpatialPanel, battery: BatteryTelemetry) {
        val c = panel.canvas
        val w = panel.bitmapWidth.toFloat()
        val h = panel.bitmapHeight.toFloat()
        drawGlassFrame(c, w, h, Color.parseColor("#FFAB00"))

        titlePaint.textSize = 28f
        titlePaint.color = Color.parseColor("#FFAB00")
        val title = when {
            battery.isHighTempWarning && battery.isLowBatteryWarning -> "⚠ HIGH THERMAL & LOW BATTERY ALERT"
            battery.isHighTempWarning -> "⚠ HIGH DEVICE TEMPERATURE (> 42°C)"
            else -> "⚠ LOW BATTERY LEVEL (< 15%)"
        }
        c.drawText(title, 28f, 52f, titlePaint)
        titlePaint.color = Color.WHITE

        subtitlePaint.textSize = 20f
        c.drawText(
            String.format(Locale.US, "Temp: %.1f°C • Battery: %d%% — Remove phone from VR box to cool down.", battery.temperatureCelsius, battery.levelPercent),
            28f,
            92f,
            subtitlePaint
        )
        panel.isTextureDirty = true
    }

    fun renderAiVisionPanel(
        panel: SpatialPanel,
        aiState: VisionAiState,
        placingWindowId: String?,
        hoveredButtonId: String?,
        hoverProgress: Float
    ) {
        val isPlacing = placingWindowId == panel.id
        val c = panel.canvas
        val w = panel.bitmapWidth.toFloat()
        val h = panel.bitmapHeight.toFloat()

        val frameAccent = when {
            aiState.isAiCameraActive -> Color.parseColor("#FF6D00")
            aiState.isListening -> Color.parseColor("#00E676")
            aiState.errorMessage != null -> Color.parseColor("#FF5252")
            else -> Color.parseColor("#00E5FF")
        }
        drawGlassFrame(c, w, h, frameAccent, isPlacing, panel.isWallSnapped)
        panel.buttons.clear()

        titlePaint.textSize = 25f
        c.drawText("WHAT AM I SEEING?", 28f, 48f, titlePaint)

        drawWindowHeaderBar(
            canvas = c,
            panel = panel,
            isPlacingThisPanel = isPlacing,
            allowClose = true,
            hoveredButtonId = hoveredButtonId,
            hoverProgress = hoverProgress
        )

        val statusRect = RectF(28f, 80f, w - 28f, 118f)
        val (statusColor, statusText) = when {
            aiState.isAiCameraActive -> Pair(
                Color.parseColor("#FF6D00"),
                if (aiState.isFollowUpMode) "● AI CAMERA ACTIVE • SENDING FOLLOW-UP..."
                else "● AI CAMERA ACTIVE • SENDING 1024px JPEG..."
            )
            aiState.isListening -> Pair(Color.parseColor("#00E676"), "● LISTENING... SPEAK YOUR QUESTION")
            aiState.errorMessage != null -> Pair(Color.parseColor("#FF5252"), "⚠ ERROR • READ ALOUD VIA TTS")
            aiState.isSpeaking -> Pair(Color.parseColor("#00E676"), "● SPEAKING ANSWER • ${aiState.capturedFrameInfo}")
            aiState.hasCachedImage -> Pair(Color.parseColor("#00E5FF"), "● CACHED (${aiState.capturedFrameInfo}) • FOLLOW-UP READY")
            else -> Pair(Color.parseColor("#9EB0D2"), "● STANDBY • PRESS VOL- OR NEW PHOTO")
        }

        pillFillPaint.color = Color.argb(48, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
        c.drawRoundRect(statusRect, 14f, 14f, pillFillPaint)
        pillStrokePaint.color = statusColor
        c.drawRoundRect(statusRect, 14f, 14f, pillStrokePaint)

        monoPaint.textSize = 18f
        monoPaint.color = statusColor
        c.drawText(statusText, 44f, 105f, monoPaint)

        val questionLine = if (aiState.lastQuestion.isNotBlank()) "Q: ${aiState.lastQuestion}"
        else "Q: (Waiting for spoken question or quick prompt)"
        bodyTextPaint.color = Color.parseColor("#9EB0D2")
        bodyTextPaint.textSize = 20f
        drawWrappedText(c, questionLine, 28f, 132f, (w - 56f).toInt(), maxLines = 2)

        val bodyRect = RectF(28f, 190f, w - 28f, h - 108f)
        pillFillPaint.color = Color.argb(145, 10, 16, 30)
        c.drawRoundRect(bodyRect, 18f, 18f, pillFillPaint)
        pillStrokePaint.color = Color.argb(110, 100, 140, 210)
        c.drawRoundRect(bodyRect, 18f, 18f, pillStrokePaint)

        val displayBody = when {
            aiState.errorMessage != null -> aiState.errorMessage
            aiState.isListening -> "Listening to microphone… Ask anything about what is in front of you."
            aiState.isAiCameraActive -> "AI camera active. Sending 1024px JPEG frame and your question…"
            aiState.lastAnswer.isNotBlank() -> aiState.lastAnswer
            else -> "Gaze at 'NEW PHOTO' or press Volume-Down anytime to ask what you are seeing."
        }
        bodyTextPaint.color = if (aiState.errorMessage != null) Color.parseColor("#FFB4A9") else Color.WHITE
        bodyTextPaint.textSize = 23f
        drawWrappedText(c, displayBody, 46f, 208f, (w - 92f).toInt(), maxLines = 6)

        val pad = 28f
        val gap = 12f
        val btnTop = h - 90f
        val btnBottom = h - 20f
        val btnW = (w - pad * 2 - gap * 4) / 5f

        val btns = listOf(
            PanelButton("ai_follow_up", if (aiState.hasCachedImage) "FOLLOW-UP" else "ASK VOICE", RectF(pad, btnTop, pad + btnW, btnBottom), Color.parseColor("#00E676")),
            PanelButton("ai_new_capture", "NEW PHOTO", RectF(pad + (btnW + gap), btnTop, pad + (btnW + gap) + btnW, btnBottom), Color.parseColor("#00E5FF")),
            PanelButton("ai_describe_quick", "DESCRIBE", RectF(pad + (btnW + gap) * 2, btnTop, pad + (btnW + gap) * 2 + btnW, btnBottom), Color.parseColor("#536DFE")),
            PanelButton("ai_toggle_tts", if (aiState.isSpeaking) "STOP TTS" else "REPEAT", RectF(pad + (btnW + gap) * 3, btnTop, pad + (btnW + gap) * 3 + btnW, btnBottom), Color.parseColor("#FFAB00")),
            PanelButton("win:${panel.id}:close", "CLOSE", RectF(pad + (btnW + gap) * 4, btnTop, pad + (btnW + gap) * 4 + btnW, btnBottom), Color.parseColor("#FF5252"))
        )

        panel.buttons.addAll(btns)
        for (btn in btns) drawButton(c, btn, hoveredButtonId, hoverProgress)

        panel.isTextureDirty = true
    }

    fun renderAiHudBadge(panel: SpatialPanel, aiState: VisionAiState) {
        val c = panel.canvas
        val w = panel.bitmapWidth.toFloat()
        val h = panel.bitmapHeight.toFloat()
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val (accent, label) = when {
            aiState.isAiCameraActive -> Pair(Color.parseColor("#FF6D00"), "● AI CAMERA ACTIVE • SENDING FRAME")
            aiState.isListening -> Pair(Color.parseColor("#00E676"), "● LISTENING... SPEAK QUESTION")
            else -> return
        }

        val rect = RectF(4f, 4f, w - 4f, h - 4f)
        pillFillPaint.color = Color.argb(230, 14, 18, 30)
        c.drawRoundRect(rect, 28f, 28f, pillFillPaint)

        pillStrokePaint.color = accent
        pillStrokePaint.strokeWidth = 4f
        c.drawRoundRect(rect, 28f, 28f, pillStrokePaint)

        buttonTextPaint.textSize = 24f
        buttonTextPaint.color = accent
        val textY = rect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        c.drawText(label, rect.centerX(), textY, buttonTextPaint)

        panel.isTextureDirty = true
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        widthPx: Int,
        maxLines: Int
    ) {
        val safeW = widthPx.coerceAtLeast(100)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, bodyTextPaint, safeW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.0f)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}
