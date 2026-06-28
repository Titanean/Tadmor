package com.tadmor.app.ui.starmap

import android.content.Context
import android.opengl.GLES30
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoRenderer
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.gl.Mesh
import com.tadmor.app.gl.MeshBuilder
import com.tadmor.app.gl.ShaderProgram
import com.tadmor.app.gl.ShaderSource
import com.tadmor.app.ui.theme.TeffColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the 3D star map: color-coded star points + dynamic distance rings.
 * Star data is received via [GLBridge] from the ViewModel.
 */
class StarMapRenderer(
    private val appContext: Context,
    cameraController: CameraController,
    private val bridge: GLBridge<StarMapParams>,
) : ExoRenderer(cameraController) {

    private lateinit var starShader: ShaderProgram
    private var starVaoId = 0
    private var starVboId = 0
    private var starAlphaVboId = 0
    private var starCount = 0
    private var currentBufferVersion = -1
    private var currentFilterVersion = -1

    // Per-star fade state. targetAlpha receives filter-driven target values
    // from the ViewModel; currentAlpha is lerped toward target each frame and
    // uploaded to starAlphaVboId for the vertex shader to consume. Separating
    // alpha into its own VBO means the static pos/color/size buffer is never
    // rewritten — only filter fades touch the GPU per frame.
    private var currentAlpha: FloatArray = FloatArray(0)
    private var targetAlpha: FloatArray = FloatArray(0)
    private var alphaUploadBuffer: java.nio.FloatBuffer? = null

    private var solVaoId = 0
    private var solVboId = 0

    // Dynamic distance rings. Each ring is keyed by its world-space
    // distance (in pc) so rings that persist across interval changes
    // keep their full opacity while new/removed rings crossfade.
    private data class RingEntry(
        val distancePc: Float,
        val mesh: Mesh,
        var currentAlpha: Float,
        var targetAlpha: Float,
    )
    private var ringEntries = mutableListOf<RingEntry>()
    private var currentRingInterval = -1f
    private var lastFrameNanos = 0L

    private val mvpMatrix = FloatArray(16)
    private var startTime = 0L

    companion object {
        private const val REFERENCE_SCREEN_HEIGHT = 800f
        private const val PC_PER_GL_UNIT = 30f // must match ViewModel divisor
        private const val TARGET_RING_COUNT = 3

        // 1-2-5 sequence for nice round intervals
        private val NICE_INTERVALS = floatArrayOf(
            1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f,
        )
    }

    override fun onCreated() {
        val vertSrc = ShaderSource.load(appContext, "star_points.vert")
        val fragSrc = ShaderSource.load(appContext, "star_points.frag")
        starShader = ShaderProgram(vertSrc, fragSrc)

        GLES30.glEnable(0x8642) // GL_PROGRAM_POINT_SIZE

        buildSolMarker()

        startTime = System.nanoTime()
    }

    override fun onResized(width: Int, height: Int) {
        // Handled by base class
    }

    override fun onFrame() {
        val params = bridge.consume()

        if (!params.isVisible) return

        // Rebuild star VBO if the star set changed (rare — basically init).
        if (params.bufferVersion != currentBufferVersion) {
            if (params.count > 0) {
                rebuildStarBuffer(params)
            } else {
                starCount = 0
            }
            currentBufferVersion = params.bufferVersion
        }

        // Pick up new filter target alphas. currentAlpha animates toward these.
        if (params.filterVersion != currentFilterVersion &&
            params.targetAlpha.size == starCount
        ) {
            targetAlpha = params.targetAlpha.copyOf()
            if (currentAlpha.size != starCount) {
                // First-ever target after a star-set rebuild: snap so stars
                // don't fade in from 0 on initial load.
                currentAlpha = targetAlpha.copyOf()
                uploadAlphaBuffer()
            }
            currentFilterVersion = params.filterVersion
        }

        // Lerp currentAlpha toward target each frame and upload if anything moved.
        if (starCount > 0 && currentAlpha.size == starCount) {
            val dtSec = if (lastFrameNanos == 0L) 0f
                        else ((System.nanoTime() - lastFrameNanos) / 1_000_000_000f)
                            .coerceIn(0f, 0.1f)
            val rate = 2.5f // ~400ms full fade
            var moved = false
            for (i in 0 until starCount) {
                val cur = currentAlpha[i]
                val tgt = if (i < targetAlpha.size) targetAlpha[i] else 1f
                if (cur < tgt) {
                    currentAlpha[i] = (cur + rate * dtSec).coerceAtMost(tgt)
                    moved = true
                } else if (cur > tgt) {
                    currentAlpha[i] = (cur - rate * dtSec).coerceAtLeast(tgt)
                    moved = true
                }
            }
            if (moved) uploadAlphaBuffer()
        }

        // Snapshot camera once per frame so view / projection / eye come
        // from the same matrix swap. Reading them piecemeal across the
        // frame risks pairing a stale eye position with a fresh view
        // matrix during active dragging (one-frame "flash in the drag
        // direction" artifact).
        val cam = cameraController.snapshot()

        // MVP = projection * view (no model matrix — stars are in world space)
        android.opengl.Matrix.multiplyMM(
            mvpMatrix, 0,
            cam.projection, 0,
            cam.view, 0,
        )

        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f

        // --- Update dynamic distance rings based on camera zoom ---
        updateRings(cam.eye)

        // --- Draw distance rings ---
        val eye = cam.eye
        // Visible radius = zoom distance + distance from camera to origin,
        // so rings at Sol stay visible when camera is centered on a distant star
        val distToOrigin = kotlin.math.sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2])
        val visibleRadius = (cameraController.currentDistance + distToOrigin) * 1.5f

        starShader.use()
        starShader.setUniformMat4("uMVP", mvpMatrix)
        starShader.setUniform("uScreenHeight", REFERENCE_SCREEN_HEIGHT)
        starShader.setUniform("uTime", elapsed)
        starShader.setUniform("uCameraPos", eye[0], eye[1], eye[2])
        starShader.setUniform("uVisibleRadius", visibleRadius)
        starShader.setUniform("uIsLine", 1)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glLineWidth(1f)
        for (ring in ringEntries) {
            if (ring.currentAlpha <= 0f) continue
            starShader.setUniform("uLineAlpha", ring.currentAlpha)
            ring.mesh.bind()
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glVertexAttrib3f(1, 0.18f, 0.22f, 0.35f)
            GLES30.glDisableVertexAttribArray(2)
            GLES30.glVertexAttrib1f(2, 2f)
            ring.mesh.draw()
            ring.mesh.unbind()
        }
        starShader.setUniform("uLineAlpha", 1f)
        GLES30.glDisable(GLES30.GL_BLEND)

        // --- Draw stars ---
        starShader.setUniform("uIsLine", 0)
        if (starCount > 0 && starVaoId != 0) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            GLES30.glDepthMask(false)

            starShader.use()
            starShader.setUniformMat4("uMVP", mvpMatrix)
            starShader.setUniform("uScreenHeight", REFERENCE_SCREEN_HEIGHT)
            starShader.setUniform("uTime", elapsed)

            GLES30.glBindVertexArray(starVaoId)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starCount)
            GLES30.glBindVertexArray(0)

            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // --- Draw Sol marker ---
        if (solVaoId != 0 && params.showSol) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            GLES30.glDepthMask(false)

            starShader.use()
            starShader.setUniformMat4("uMVP", mvpMatrix)
            starShader.setUniform("uScreenHeight", REFERENCE_SCREEN_HEIGHT)
            starShader.setUniform("uTime", elapsed)

            GLES30.glBindVertexArray(solVaoId)
            // Sol VAO doesn't bind location 3; supply a constant alpha=1 so
            // the fragment shader's vAlpha discard doesn't drop Sol.
            GLES30.glDisableVertexAttribArray(3)
            GLES30.glVertexAttrib1f(3, 1f)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 1)
            GLES30.glBindVertexArray(0)

            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    /**
     * Picks a nice round interval so ~[TARGET_RING_COUNT] rings fit in the
     * visible radius, then updates the ring set by crossfading entries in
     * and out. Rings shared between the old and new set keep their current
     * alpha (they remain visible); rings only in the new set fade in from
     * 0; rings only in the old set fade out to 0 and are disposed.
     *
     * [eye] is passed in (rather than re-read from `cameraController`) so
     * the visible-radius computation uses the same eye position that the
     * rest of the frame is rendering with — see the camera-snapshot fix
     * in `onDrawFrame`.
     */
    private fun updateRings(eye: FloatArray) {
        // Smooth alpha toward target every frame.
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f
                 else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        val rate = 2.5f // ~400ms fade across full [0,1] range
        val iter = ringEntries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.currentAlpha < e.targetAlpha) {
                e.currentAlpha = (e.currentAlpha + rate * dt).coerceAtMost(e.targetAlpha)
            } else if (e.currentAlpha > e.targetAlpha) {
                e.currentAlpha = (e.currentAlpha - rate * dt).coerceAtLeast(e.targetAlpha)
                if (e.currentAlpha <= 0f && e.targetAlpha <= 0f) {
                    e.mesh.release()
                    iter.remove()
                }
            }
        }

        // Use distance from camera to Sol (origin), not just zoom distance,
        // so rings scale correctly when camera is centered on a distant star
        val distToSol = kotlin.math.sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2])
        val camDist = maxOf(cameraController.currentDistance, distToSol)
        val visiblePc = camDist * PC_PER_GL_UNIT

        // Pick the largest nice interval that gives at least TARGET_RING_COUNT rings
        var interval = NICE_INTERVALS.last()
        for (ni in NICE_INTERVALS) {
            if (visiblePc / ni >= TARGET_RING_COUNT) {
                interval = ni
            } else {
                break
            }
        }

        if (interval == currentRingInterval) return
        currentRingInterval = interval

        // Build the desired set of ring distances at the new interval.
        val desired = mutableListOf<Float>()
        var dist = interval
        while (dist / PC_PER_GL_UNIT < camDist * 3f) {
            desired.add(dist)
            dist += interval
            if (desired.size >= 20) break
        }

        // Mark all current rings for fade-out, then override to fade-in
        // for ones present in the new desired set.
        for (e in ringEntries) e.targetAlpha = 0f
        for (d in desired) {
            val existing = ringEntries.firstOrNull {
                kotlin.math.abs(it.distancePc - d) < 0.001f
            }
            if (existing != null) {
                existing.targetAlpha = 1f
            } else {
                ringEntries.add(
                    RingEntry(
                        distancePc = d,
                        mesh = buildRing(d / PC_PER_GL_UNIT),
                        currentAlpha = 0f,
                        targetAlpha = 1f,
                    ),
                )
            }
        }
    }

    override fun release() {
        if (::starShader.isInitialized) starShader.release()
        if (starVaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(starVaoId), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(starVboId), 0)
        }
        if (solVaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(solVaoId), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(solVboId), 0)
        }
        ringEntries.forEach { it.mesh.release() }
    }

    private fun rebuildStarBuffer(params: StarMapParams) {
        if (starVaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(starVaoId), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(starVboId), 0)
        }
        if (starAlphaVboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(starAlphaVboId), 0)
            starAlphaVboId = 0
        }

        starCount = params.count

        val floatsPerStar = 7
        val data = FloatArray(starCount * floatsPerStar)
        for (i in 0 until starCount) {
            data[i * 7 + 0] = params.positions[i * 3]
            data[i * 7 + 1] = params.positions[i * 3 + 1]
            data[i * 7 + 2] = params.positions[i * 3 + 2]
            data[i * 7 + 3] = params.colors[i * 3]
            data[i * 7 + 4] = params.colors[i * 3 + 1]
            data[i * 7 + 5] = params.colors[i * 3 + 2]
            data[i * 7 + 6] = params.sizes[i]
        }

        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .flip()

        val vaoIds = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        starVaoId = vaoIds[0]

        val vboIds = IntArray(2)
        GLES30.glGenBuffers(2, vboIds, 0)
        starVboId = vboIds[0]
        starAlphaVboId = vboIds[1]

        GLES30.glBindVertexArray(starVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        val stride = floatsPerStar * 4

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)

        // Per-star alpha in its own VBO so we can glBufferSubData it during
        // fades without touching the static pos/color/size buffer.
        val initialAlphas = FloatArray(starCount) { 1f }
        val alphaBuf = ByteBuffer.allocateDirect(starCount * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(initialAlphas)
            .flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starAlphaVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, starCount * 4, alphaBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, 4, 0)

        GLES30.glBindVertexArray(0)

        // Empty currentAlpha triggers a snap (not a fade) on the next filter
        // pickup so stars that start out filtered-away don't animate in from
        // full visibility on initial load.
        currentAlpha = FloatArray(0)
        targetAlpha = FloatArray(0)
        alphaUploadBuffer = ByteBuffer.allocateDirect(starCount * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        currentFilterVersion = -1 // force pickup of incoming targetAlpha
    }

    /** Streams currentAlpha to GPU via glBufferSubData on the alpha VBO. */
    private fun uploadAlphaBuffer() {
        val buf = alphaUploadBuffer ?: return
        if (starAlphaVboId == 0 || currentAlpha.isEmpty()) return
        buf.clear()
        buf.put(currentAlpha)
        buf.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starAlphaVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, currentAlpha.size * 4, buf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun buildSolMarker() {
        // Sol: G2V, 5778 K, 1.0 R☉ — same pipeline as every other star
        val solColor = TeffColor.fromTeff(5778.0)
        val solSize = (2.5f + kotlin.math.ln(1.0f + 1.0f) * 2.0f).coerceIn(2.0f, 14.0f)
        val data = floatArrayOf(0f, 0f, 0f, solColor.red, solColor.green, solColor.blue, solSize)
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .flip()

        val vaoIds = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        solVaoId = vaoIds[0]

        val vboIds = IntArray(1)
        GLES30.glGenBuffers(1, vboIds, 0)
        solVboId = vboIds[0]

        GLES30.glBindVertexArray(solVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, solVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        val stride = 7 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)

        GLES30.glBindVertexArray(0)
    }

    private fun buildRing(radius: Float): Mesh {
        val segments = 128
        val points = (0 until segments).map { i ->
            val angle = 2f * PI.toFloat() * i / segments
            floatArrayOf(radius * cos(angle), 0f, radius * sin(angle))
        }
        return MeshBuilder.lineLoop(points)
    }
}

/**
 * Immutable parameters sent from the ViewModel to the renderer via [GLBridge].
 */
data class StarMapParams(
    val positions: FloatArray = FloatArray(0),
    val colors: FloatArray = FloatArray(0),
    val sizes: FloatArray = FloatArray(0),
    // Per-star target alpha (1 = visible, 0 = filtered-out). The renderer
    // lerps toward this value each frame to produce the filter fade animation.
    val targetAlpha: FloatArray = FloatArray(0),
    val count: Int = 0,
    val isVisible: Boolean = true,
    val showSol: Boolean = true,
    // Bumps when the static star set changes (positions/colors/sizes/count).
    val bufferVersion: Int = 0,
    // Bumps when filters change the targetAlpha. Independent of bufferVersion
    // so filter toggles don't force a full VBO rebuild.
    val filterVersion: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StarMapParams) return false
        return bufferVersion == other.bufferVersion &&
            filterVersion == other.filterVersion &&
            isVisible == other.isVisible &&
            showSol == other.showSol
    }

    override fun hashCode(): Int =
        bufferVersion * 31 + filterVersion * 7 +
            (if (isVisible) 1 else 0) + (if (showSol) 2 else 0)
}
