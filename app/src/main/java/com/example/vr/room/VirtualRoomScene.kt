package com.example.vr.room

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.example.vr.pose.RoomBounds
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ultra-lightweight low-poly OpenGL ES 2.0 virtual room designed for 30-60 FPS on Kirin 710:
 * - 6.4m x 6.4m floor with procedural glowing grid texture
 * - 4 walls + baseboard trim strips + ceiling + glowing skylight frame
 * - Simple low-poly props (desk + monitor accent, pedestal + rotating orb, lounge bench, shelf pillar)
 * - Soft directional + hemisphere vertex lighting
 * - Floor teleport target ring indicator
 */
class VirtualRoomScene(
    private val bounds: RoomBounds = RoomBounds()
) {
    private var floorProgram = 0
    private var litBoxProgram = 0
    private var ringProgram = 0
    private var floorGridTexId = 0

    private lateinit var floorVertexBuffer: FloatBuffer
    private lateinit var floorUvBuffer: FloatBuffer
    private lateinit var cubePosBuffer: FloatBuffer
    private lateinit var cubeNormalBuffer: FloatBuffer
    private lateinit var ringBuffer: FloatBuffer

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var orbRotationDeg = 0f

    fun initGl() {
        floorGridTexId = createProceduralGridTexture()

        val hw = bounds.halfWidth
        val hd = bounds.halfDepth
        val floorVerts = floatArrayOf(
            -hw, 0f,  hd,
             hw, 0f,  hd,
            -hw, 0f, -hd,
             hw, 0f, -hd
        )
        floorVertexBuffer = createFloatBuffer(floorVerts)

        val tileRepeat = 8.0f
        val floorUvs = floatArrayOf(
            0f, tileRepeat,
            tileRepeat, tileRepeat,
            0f, 0f,
            tileRepeat, 0f
        )
        floorUvBuffer = createFloatBuffer(floorUvs)

        initUnitCubeBuffers()
        initRingBuffer()
        initShaders()
    }

    fun draw(vpMatrix: FloatArray, dtSec: Float) {
        orbRotationDeg = (orbRotationDeg + dtSec * 36f) % 360f

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // 1. Draw Floor with tiled grid texture
        drawGridFloor(vpMatrix)

        // 2. Draw 4 Walls, Ceiling, Trim & Low-Poly Props with soft lighting
        GLES20.glUseProgram(litBoxProgram)
        val posLoc = GLES20.glGetAttribLocation(litBoxProgram, "aPosition")
        val normLoc = GLES20.glGetAttribLocation(litBoxProgram, "aNormal")
        val mvpLoc = GLES20.glGetUniformLocation(litBoxProgram, "uMVPMatrix")
        val modelLoc = GLES20.glGetUniformLocation(litBoxProgram, "uModelMatrix")
        val colorLoc = GLES20.glGetUniformLocation(litBoxProgram, "uColor")

        cubePosBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, cubePosBuffer)

        cubeNormalBuffer.position(0)
        GLES20.glEnableVertexAttribArray(normLoc)
        GLES20.glVertexAttribPointer(normLoc, 3, GLES20.GL_FLOAT, false, 0, cubeNormalBuffer)

        val hw = bounds.halfWidth
        val hd = bounds.halfDepth
        val ch = bounds.ceilingHeight
        val wallThick = 0.14f

        // Back Wall (-Z)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 0f, ch * 0.5f, -hd - wallThick * 0.5f, hw * 2f + wallThick * 2f, ch, wallThick, 0.12f, 0.16f, 0.24f, 1f)
        // Front Wall (+Z)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 0f, ch * 0.5f, hd + wallThick * 0.5f, hw * 2f + wallThick * 2f, ch, wallThick, 0.11f, 0.15f, 0.22f, 1f)
        // Left Wall (-X)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -hw - wallThick * 0.5f, ch * 0.5f, 0f, wallThick, ch, hd * 2f, 0.10f, 0.14f, 0.21f, 1f)
        // Right Wall (+X)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, hw + wallThick * 0.5f, ch * 0.5f, 0f, wallThick, ch, hd * 2f, 0.10f, 0.14f, 0.21f, 1f)

        // Ceiling & Soft Skylight Frame
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 0f, ch + 0.06f, 0f, hw * 2f + wallThick * 2f, 0.12f, hd * 2f + wallThick * 2f, 0.08f, 0.11f, 0.18f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 0f, ch - 0.01f, 0f, 2.6f, 0.03f, 2.6f, 0.28f, 0.55f, 0.72f, 1f)

        // Cyan Baseboard Accent Trim along all 4 walls (enhances motion parallax & spatial edges)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 0f, 0.06f, -hd + 0.02f, hw * 2f, 0.12f, 0.04f, 0.0f, 0.75f, 0.90f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 0f, 0.06f, hd - 0.02f, hw * 2f, 0.12f, 0.04f, 0.0f, 0.55f, 0.75f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -hw + 0.02f, 0.06f, 0f, 0.04f, 0.12f, hd * 2f, 0.0f, 0.65f, 0.85f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, hw - 0.02f, 0.06f, 0f, 0.04f, 0.12f, hd * 2f, 0.0f, 0.65f, 0.85f, 1f)

        // Prop 1: Low-Poly Desk in back-left corner (-2.25, 0, -2.35)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -2.25f, 0.76f, -2.35f, 1.35f, 0.07f, 0.72f, 0.22f, 0.27f, 0.38f, 1f) // tabletop
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -2.84f, 0.38f, -2.62f, 0.09f, 0.76f, 0.09f, 0.15f, 0.18f, 0.25f, 1f) // legs
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -1.66f, 0.38f, -2.62f, 0.09f, 0.76f, 0.09f, 0.15f, 0.18f, 0.25f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -2.84f, 0.38f, -2.08f, 0.09f, 0.76f, 0.09f, 0.15f, 0.18f, 0.25f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -1.66f, 0.38f, -2.08f, 0.09f, 0.76f, 0.09f, 0.15f, 0.18f, 0.25f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -2.25f, 1.02f, -2.52f, 0.68f, 0.38f, 0.04f, 0.08f, 0.78f, 0.92f, 1f) // glowing monitor

        // Prop 2: Pedestal + Rotating Spatial Cube/Crystal in back-right corner (2.25, 0, -2.25)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 2.25f, 0.45f, -2.25f, 0.56f, 0.90f, 0.56f, 0.16f, 0.20f, 0.30f, 1f)
        drawRotatingOrbProp(vpMatrix, mvpLoc, modelLoc, colorLoc, 2.25f, 1.18f, -2.25f)

        // Prop 3: Lounge Bench along left-rear area (-2.55, 0, 1.65)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -2.55f, 0.26f, 1.65f, 0.72f, 0.44f, 1.65f, 0.24f, 0.28f, 0.42f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, -2.85f, 0.62f, 1.65f, 0.16f, 0.55f, 1.65f, 0.20f, 0.24f, 0.36f, 1f)

        // Prop 4: Tall Shelf / Pillar Accent near right-rear (2.65, 0, 1.85)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 2.65f, 1.05f, 1.85f, 0.65f, 2.10f, 0.45f, 0.17f, 0.21f, 0.31f, 1f)
        drawLitBox(vpMatrix, mvpLoc, modelLoc, colorLoc, 2.65f, 1.55f, 1.61f, 0.52f, 0.04f, 0.04f, 0.32f, 0.44f, 0.98f, 1f)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(normLoc)
    }

    fun drawFloorTeleportMarker(vpMatrix: FloatArray, targetX: Float, targetZ: Float, dwellProgress: Float) {
        GLES20.glUseProgram(ringProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val posLoc = GLES20.glGetAttribLocation(ringProgram, "aPosition")
        val mvpLoc = GLES20.glGetUniformLocation(ringProgram, "uMVPMatrix")
        val colorLoc = GLES20.glGetUniformLocation(ringProgram, "uColor")

        // Outer ring
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, targetX, 0.015f, targetZ)
        Matrix.scaleM(modelMatrix, 0, 0.36f, 1f, 0.36f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorLoc, 0.0f, 0.90f, 1.0f, 0.95f)

        ringBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, ringBuffer)
        GLES20.glLineWidth(4.5f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, RING_SEGMENTS)

        // Inner progress ring
        val innerScale = (0.10f + 0.24f * dwellProgress.coerceIn(0f, 1f))
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, targetX, 0.018f, targetZ)
        Matrix.scaleM(modelMatrix, 0, innerScale, 1f, innerScale)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorLoc, 0.0f, 1.0f, 0.46f, 0.95f)
        GLES20.glLineWidth(5.5f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, RING_SEGMENTS)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawGridFloor(vpMatrix: FloatArray) {
        GLES20.glUseProgram(floorProgram)
        val posLoc = GLES20.glGetAttribLocation(floorProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(floorProgram, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(floorProgram, "uMVPMatrix")
        val texLoc = GLES20.glGetUniformLocation(floorProgram, "uTexture")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, vpMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floorGridTexId)
        GLES20.glUniform1i(texLoc, 0)

        floorVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, floorVertexBuffer)

        floorUvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, floorUvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun drawLitBox(
        vpMatrix: FloatArray,
        mvpLoc: Int,
        modelLoc: Int,
        colorLoc: Int,
        cx: Float,
        cy: Float,
        cz: Float,
        sx: Float,
        sy: Float,
        sz: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cx, cy, cz)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform4f(colorLoc, r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    private fun drawRotatingOrbProp(
        vpMatrix: FloatArray,
        mvpLoc: Int,
        modelLoc: Int,
        colorLoc: Int,
        cx: Float,
        cy: Float,
        cz: Float
    ) {
        val bobY = cy + 0.04f * sin(Math.toRadians((orbRotationDeg * 2f).toDouble())).toFloat()
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cx, bobY, cz)
        Matrix.rotateM(modelMatrix, 0, orbRotationDeg, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, 35f, 1f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, 0.24f, 0.24f, 0.24f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform4f(colorLoc, 0.0f, 0.90f, 1.0f, 1.0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    private fun createProceduralGridTexture(): Int {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0B111E"))

        val minorPaint = Paint().apply {
            color = Color.parseColor("#1D2C48")
            strokeWidth = 2f
        }
        val step = size / 4f
        for (i in 1..3) {
            val p = i * step
            canvas.drawLine(p, 0f, p, size.toFloat(), minorPaint)
            canvas.drawLine(0f, p, size.toFloat(), p, minorPaint)
        }

        val majorPaint = Paint().apply {
            color = Color.parseColor("#00B8D4")
            strokeWidth = 5f
        }
        canvas.drawLine(0f, 0f, size.toFloat(), 0f, majorPaint)
        canvas.drawLine(0f, 0f, 0f, size.toFloat(), majorPaint)
        canvas.drawLine(0f, size.toFloat() - 2f, size.toFloat(), size.toFloat() - 2f, majorPaint)
        canvas.drawLine(size.toFloat() - 2f, 0f, size.toFloat() - 2f, size.toFloat(), majorPaint)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bmp.recycle()
        return tex[0]
    }

    private fun initUnitCubeBuffers() {
        val p = floatArrayOf(
            // Front (+Z)
            -0.5f,-0.5f, 0.5f,  0.5f,-0.5f, 0.5f,  0.5f, 0.5f, 0.5f,
            -0.5f,-0.5f, 0.5f,  0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
            // Back (-Z)
             0.5f,-0.5f,-0.5f, -0.5f,-0.5f,-0.5f, -0.5f, 0.5f,-0.5f,
             0.5f,-0.5f,-0.5f, -0.5f, 0.5f,-0.5f,  0.5f, 0.5f,-0.5f,
            // Left (-X)
            -0.5f,-0.5f,-0.5f, -0.5f,-0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
            -0.5f,-0.5f,-0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f,-0.5f,
            // Right (+X)
             0.5f,-0.5f, 0.5f,  0.5f,-0.5f,-0.5f,  0.5f, 0.5f,-0.5f,
             0.5f,-0.5f, 0.5f,  0.5f, 0.5f,-0.5f,  0.5f, 0.5f, 0.5f,
            // Top (+Y)
            -0.5f, 0.5f, 0.5f,  0.5f, 0.5f, 0.5f,  0.5f, 0.5f,-0.5f,
            -0.5f, 0.5f, 0.5f,  0.5f, 0.5f,-0.5f, -0.5f, 0.5f,-0.5f,
            // Bottom (-Y)
            -0.5f,-0.5f,-0.5f,  0.5f,-0.5f,-0.5f,  0.5f,-0.5f, 0.5f,
            -0.5f,-0.5f,-0.5f,  0.5f,-0.5f, 0.5f, -0.5f,-0.5f, 0.5f
        )
        val n = floatArrayOf(
             0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,
             0f, 0f,-1f,  0f, 0f,-1f,  0f, 0f,-1f,  0f, 0f,-1f,  0f, 0f,-1f,  0f, 0f,-1f,
            -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f,
             1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,
             0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,
             0f,-1f, 0f,  0f,-1f, 0f,  0f,-1f, 0f,  0f,-1f, 0f,  0f,-1f, 0f,  0f,-1f, 0f
        )
        cubePosBuffer = createFloatBuffer(p)
        cubeNormalBuffer = createFloatBuffer(n)
    }

    private fun initRingBuffer() {
        val coords = FloatArray(RING_SEGMENTS * 3)
        for (i in 0 until RING_SEGMENTS) {
            val angle = 2.0 * Math.PI * i / RING_SEGMENTS
            coords[i * 3] = cos(angle).toFloat()
            coords[i * 3 + 1] = 0f
            coords[i * 3 + 2] = sin(angle).toFloat()
        }
        ringBuffer = createFloatBuffer(coords)
    }

    private fun initShaders() {
        val floorVs = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val floorFs = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
        floorProgram = buildProgram(floorVs, floorFs)

        val litVs = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            attribute vec4 aPosition;
            attribute vec3 aNormal;
            varying float vLight;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vec3 worldNorm = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);
                vec3 lightDir = normalize(vec3(0.32, 0.88, 0.35));
                float diff = max(dot(worldNorm, lightDir), 0.0);
                float hemi = 0.48 + 0.14 * worldNorm.y;
                vLight = clamp(hemi + 0.52 * diff, 0.32, 1.15);
            }
        """.trimIndent()
        val litFs = """
            precision mediump float;
            uniform vec4 uColor;
            varying float vLight;
            void main() {
                gl_FragColor = vec4(uColor.rgb * vLight, uColor.a);
            }
        """.trimIndent()
        litBoxProgram = buildProgram(litVs, litFs)

        val ringVs = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
            }
        """.trimIndent()
        val ringFs = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent()
        ringProgram = buildProgram(ringVs, ringFs)
    }

    private fun buildProgram(vsSource: String, fsSource: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vsSource)
            GLES20.glCompileShader(it)
        }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fsSource)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
        }
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

    companion object {
        private const val RING_SEGMENTS = 36
    }
}
