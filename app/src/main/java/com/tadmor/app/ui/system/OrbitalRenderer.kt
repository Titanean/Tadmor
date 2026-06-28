package com.tadmor.app.ui.system

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoRenderer
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.gl.Mesh
import com.tadmor.app.gl.ShaderProgram
import com.tadmor.app.gl.ShaderSource
import com.tadmor.domain.classification.OrbitalMechanics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import java.lang.Math.toRadians

/**
 * Renders an animated orbital diagram: orbit ellipses, planet dots, host star, HZ annulus.
 * All geometry is in the XZ plane (Y=0), with the star at origin.
 */
class OrbitalRenderer(
    private val appContext: Context,
    cameraController: CameraController,
    private val bridge: GLBridge<OrbitalParams>,
) : ExoRenderer(cameraController) {

    private lateinit var shader: ShaderProgram
    private lateinit var starfieldShader: ShaderProgram

    // Background starfield VAO
    private var sfVaoId = 0
    private var sfVboId = 0
    private var sfCount = 0
    private var sfSolVaoId = 0
    private var sfSolVboId = 0
    private var currentSfVersion = -1

    // Orbit trail VAOs — one per planet, vertex colors updated each frame
    private var orbitVaoIds = IntArray(0)
    private var orbitVboIds = IntArray(0)
    private var orbitTrailBuffer: java.nio.FloatBuffer? = null

    // Binary star orbit trail VAOs (2 for binary systems, 0 otherwise)
    private var binaryOrbitVaoIds = IntArray(0)
    private var binaryOrbitVboIds = IntArray(0)

    // Current planet XYZ positions in GL space (shared between planet + trail passes)
    private var currentPositions = Array(0) { floatArrayOf(0f, 0f, 0f) }

    // HZ annulus mesh (triangle strip)
    private var hzMesh: Mesh? = null

    // Star point VAO (1 vertex for single star, 2 for binary)
    private var starVaoId = 0
    private var starVboId = 0
    private var starVertexCount = 1

    // Binary star animation state (set in buildStarPoint, used per frame)
    private var binaryStarData = FloatArray(14) // 2 vertices × 7 floats
    private var starFloatBuffer: java.nio.FloatBuffer? = null
    private var binaryGLSma = 0f        // binary SMA in GL units
    private var binaryEcc = 0f          // binary eccentricity
    private var binaryOmegaCos = 1f     // cos(ω)
    private var binaryOmegaSin = 0f     // sin(ω)

    // Planet points VAO — rebuilt each frame (animated positions).
    // Vertices are ordered by camera depth: [0, planetsBehindCount) draw BEHIND
    // the star, [planetsBehindCount, planetCount) draw IN FRONT. This lets the
    // star glow blend over distant planets (physically correct halo occlusion)
    // without needing depth writes on the translucent glow pass — which would
    // produce a hard circular cutoff where planets touch the halo edge.
    private var planetVaoId = 0
    private var planetVboId = 0
    private var planetCount = 0
    private var planetsBehindCount = 0

    // Per-planet fade alpha (smoothed toward target each frame).
    // Target = 1.0 for visible planets, 0.0 for planets culled by star glow overlap.
    private var planetCurrentAlpha: FloatArray = FloatArray(0)

    private val mvpMatrix = FloatArray(16)
    private var lastFrameTime = 0L
    private var accumulatedDays = 0.0
    @Volatile var referenceJD = OrbitalMechanics.currentJulianDate()
        private set
    @Volatile var currentElapsedDays = 0.0
        private set
    @Volatile var visiblePlanets: BooleanArray = BooleanArray(0)
        private set
    @Volatile var planetScreenRadii: FloatArray = FloatArray(0)
        private set
    @Volatile var starScreenRadius: Float = 0f
        private set
    /** Animated GL positions for binary stars: [0-2]=primary, [3-5]=companion. Zeroed for single stars. */
    @Volatile var binaryStarPositions: FloatArray = FloatArray(6)
        private set
    private var currentVersion = -1

    // Star glow point size (stored for planet proximity culling)
    private var starPointSize = 0f
    // Cached planet base point sizes (set in rebuildPlanetPositions, used for screen radii)
    private var cachedPlanetSizes: FloatArray = FloatArray(0)

    // Actual viewport height in physical pixels (for culling GL↔pixel conversion)
    private var viewportHeight = REFERENCE_SCREEN_HEIGHT

    companion object {
        private const val REFERENCE_SCREEN_HEIGHT = 800f
        private const val ORBIT_SEGMENTS = 128
        private const val HZ_SEGMENTS = 128
        /** Duration of the [smoothToRealTime] interpolation in ms. Long enough
         *  to read as a deliberate ease, short enough that it doesn't feel
         *  laggy after the user releases the slider on RT. */
        private const val INTERP_TO_RT_MS = 400L
    }

    /**
     * Instant reset of the simulation clock to real time. Used on initial
     * load where nothing is on screen yet and there's nothing to interpolate
     * from. For slider-driven RT transitions, [smoothToRealTime] handles the
     * interpolation so planets don't visibly jump from the fast-forward
     * position to their real-time position.
     */
    fun resetToRealTime() {
        referenceJD = OrbitalMechanics.currentJulianDate()
        accumulatedDays = 0.0
    }

    /**
     * Smoothly interpolates the simulated clock back to real time over
     * [INTERP_TO_RT_MS]. Animates two values in parallel:
     *
     *  - **`referenceJD + accumulatedDays`** (= simulated JD) eases from where
     *    it was when RT was selected toward the current real-time JD. Drives
     *    the epoch-anchored planet phases.
     *  - **`accumulatedDays`** eases from its current value down to 0.
     *    Binary stars use `meanAnomaly(accumulatedDays, period)` with no
     *    catalog epoch, so their "RT position" is by convention the M=0
     *    state at `accumulatedDays = 0` (matching what the original
     *    instant-snap `resetToRealTime` did). Letting it stay put would
     *    desynchronise binary phase from RT after every fast-forward.
     *
     * Because both animations share the same eased progress, `referenceJD`
     * is reconstructed each frame as `interpolatedSimJD - interpolatedAccumulated`
     * so the planet target stays consistent while `accumulatedDays` is
     * being driven down to 0. By t = 1 the state matches a fresh
     * `resetToRealTime`: accumulatedDays = 0, referenceJD = currentJD.
     */
    fun smoothToRealTime() {
        interpStartNanos = System.nanoTime()
        interpStartSimJD = referenceJD + accumulatedDays
        interpStartAccumulated = accumulatedDays
        isInterpolatingToRT = true
    }

    // RT-smoothing state. Volatile because `smoothToRealTime` is called from
    // the UI thread but read/cleared from the GL thread inside `onFrame`.
    @Volatile private var isInterpolatingToRT = false
    @Volatile private var interpStartNanos = 0L
    @Volatile private var interpStartSimJD = 0.0
    @Volatile private var interpStartAccumulated = 0.0

    override fun onCreated() {
        val vertSrc = ShaderSource.load(appContext, "orbital.vert")
        val fragSrc = ShaderSource.load(appContext, "orbital.frag")
        shader = ShaderProgram(vertSrc, fragSrc)

        val sfVertSrc = ShaderSource.load(appContext, "star_points.vert")
        val sfFragSrc = ShaderSource.load(appContext, "star_points.frag")
        starfieldShader = ShaderProgram(sfVertSrc, sfFragSrc)

        GLES30.glEnable(0x8642) // GL_PROGRAM_POINT_SIZE

        lastFrameTime = System.nanoTime()
    }

    override fun onResized(width: Int, height: Int) {
        viewportHeight = height.toFloat()
    }

    override fun onFrame() {
        val params = bridge.consume()

        if (!params.isVisible || params.planets.isEmpty()) return

        // Rebuild static geometry when system data changes
        if (params.version != currentVersion) {
            rebuildStaticGeometry(params)
            currentVersion = params.version
        }

        // Rebuild starfield when it arrives or changes
        if (params.starfieldVersion != currentSfVersion) {
            rebuildStarfieldBuffer(params)
            currentSfVersion = params.starfieldVersion
        }

        // --- Time advancement ---
        val now = System.nanoTime()
        val dt = (now - lastFrameTime) / 1_000_000_000.0
        lastFrameTime = now
        if (isInterpolatingToRT) {
            // RT smoothing — eases both the simulated JD (planet phase) and
            // accumulatedDays (binary phase) to their real-time targets in
            // parallel. Natural accumulation is skipped during the
            // transition; at the new RT rate the per-frame contribution
            // would be ~16 µs of simulated time anyway, well below the
            // interpolation step.
            val elapsedMs = (now - interpStartNanos) / 1_000_000.0
            val raw = (elapsedMs / INTERP_TO_RT_MS).coerceIn(0.0, 1.0)
            // Smooth Hermite ease, matches Compose's FastOutSlowInEasing in feel
            val eased = raw * raw * (3.0 - 2.0 * raw)
            val targetSimJD = OrbitalMechanics.currentJulianDate()
            val interpolatedSimJD = interpStartSimJD + (targetSimJD - interpStartSimJD) * eased
            val interpolatedAccumulated = interpStartAccumulated * (1.0 - eased)
            accumulatedDays = interpolatedAccumulated
            referenceJD = interpolatedSimJD - accumulatedDays
            if (raw >= 1.0) isInterpolatingToRT = false
        } else if (params.isPlaying) {
            accumulatedDays += dt * params.daysPerSecond
        }

        currentElapsedDays = accumulatedDays

        // Snapshot the camera once so every read this frame (MVP build,
        // rotation-only starfield matrix, planet sort-by-view-Z) sees the
        // same matrix swap. Reading view/projection piecemeal during
        // active dragging can pair a fresh view with a stale projection
        // and produce one-frame "flash in the drag direction" artifacts.
        val cam = cameraController.snapshot()

        // MVP
        Matrix.multiplyMM(mvpMatrix, 0, cam.projection, 0, cam.view, 0)

        shader.use()
        shader.setUniformMat4("uMVP", mvpMatrix)
        shader.setUniform("uScreenHeight", REFERENCE_SCREEN_HEIGHT)

        // Compute planet positions + orbit trails before rendering
        val elapsedDays = accumulatedDays
        val frameDt = dt.toFloat().coerceIn(0f, 0.1f)
        rebuildPlanetPositions(params, elapsedDays, frameDt, cam.view)
        publishScreenRadii(params)
        updateOrbitTrails(params)
        updateBinaryStarPositions(params, elapsedDays)
        updateBinaryOrbitTrails(params, elapsedDays)

        // --- Layer 0: Background starfield (no depth, rotation-only MVP) ---
        if (sfCount > 0 && sfVaoId != 0 && params.starfieldAlpha > 0.001f) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(false)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)

            // Build rotation-only view matrix (strip translation → infinite distance)
            val rotView = cam.view.copyOf()
            rotView[12] = 0f; rotView[13] = 0f; rotView[14] = 0f
            val sfMVP = FloatArray(16)
            Matrix.multiplyMM(sfMVP, 0, cam.projection, 0, rotView, 0)

            starfieldShader.use()
            starfieldShader.setUniformMat4("uMVP", sfMVP)
            starfieldShader.setUniform("uScreenHeight", REFERENCE_SCREEN_HEIGHT)
            starfieldShader.setUniform("uTime", 0f)
            starfieldShader.setUniform("uIsLine", 0)
            starfieldShader.setUniform("uCameraPos", 0f, 0f, 0f)
            starfieldShader.setUniform("uVisibleRadius", 10000f)

            GLES30.glBindVertexArray(sfVaoId)
            // Shared shader has a per-vertex alpha (location 3) consumed by
            // StarMapRenderer fades; starfield VAOs don't bind it, so drive
            // fade via the constant generic attribute.
            GLES30.glDisableVertexAttribArray(3)
            GLES30.glVertexAttrib1f(3, params.starfieldAlpha)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, sfCount)
            GLES30.glBindVertexArray(0)

            // Sol marker
            if (sfSolVaoId != 0) {
                GLES30.glBindVertexArray(sfSolVaoId)
                GLES30.glDisableVertexAttribArray(3)
                GLES30.glVertexAttrib1f(3, params.starfieldAlpha)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 1)
                GLES30.glBindVertexArray(0)
            }

            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDepthMask(true)
        }

        // Switch back to orbital shader for the rest of the frame
        shader.use()
        shader.setUniformMat4("uMVP", mvpMatrix)
        shader.setUniform("uScreenHeight", REFERENCE_SCREEN_HEIGHT)

        // --- Layer 1: flat backdrop (HZ + orbits) — no depth test, pure painter's order ---
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // HZ annulus
        hzMesh?.let { mesh ->
            if (params.hzAlpha > 0.001f) {
                shader.setUniform("uRenderMode", 2)
                shader.setUniform("uLayerAlpha", params.hzAlpha)
                mesh.bind()
                mesh.draw()
                mesh.unbind()
            }
        }

        // Orbit ellipses with trail
        shader.setUniform("uRenderMode", 1)
        GLES30.glLineWidth(1f)
        for (i in orbitVaoIds.indices) {
            GLES30.glBindVertexArray(orbitVaoIds[i])
            GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, ORBIT_SEGMENTS)
            GLES30.glBindVertexArray(0)
        }

        // Binary star orbits with trail
        for (i in binaryOrbitVaoIds.indices) {
            GLES30.glBindVertexArray(binaryOrbitVaoIds[i])
            GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, ORBIT_SEGMENTS)
            GLES30.glBindVertexArray(0)
        }

        // --- Layer 2: bodies (star + planets) with depth test ---
        // Star and planets all sit at Y=0. Depth test lets nearer bodies occlude farther ones.
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)

        // Depth-sorted draw order:
        //   1. Planets behind the star (back-to-front) — blend over bg.
        //   2. Star core (opaque, writes depth) — occludes planets directly
        //      behind via depth test.
        //   3. Star glow (no depth writes) — blends over behind-planets
        //      through the translucent halo. No depth write = no hard cutoff.
        //   4. Planets in front of the star (back-to-front) — blend over glow.
        //
        // Planet passes use depth TEST (so the star core correctly occludes)
        // but NOT depth WRITE. With back-to-front sorting, alpha blending
        // handles planet-on-planet occlusion naturally. Writing depth would
        // cause a fading near-planet to mask a farther planet with a hard
        // depth boundary until the fade completes.
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        if (planetsBehindCount > 0 && planetVaoId != 0) {
            shader.setUniform("uRenderMode", 3)
            GLES30.glDepthMask(false)
            GLES30.glBindVertexArray(planetVaoId)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, planetsBehindCount)
            GLES30.glBindVertexArray(0)
            GLES30.glDepthMask(true)
        }

        if (starVaoId != 0) {
            shader.setUniform("uRenderMode", 4)
            GLES30.glBindVertexArray(starVaoId)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starVertexCount)
            GLES30.glBindVertexArray(0)

            shader.setUniform("uRenderMode", 0)
            GLES30.glDepthMask(false)
            GLES30.glBindVertexArray(starVaoId)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starVertexCount)
            GLES30.glBindVertexArray(0)
            GLES30.glDepthMask(true)
        }

        val frontCount = planetCount - planetsBehindCount
        if (frontCount > 0 && planetVaoId != 0) {
            shader.setUniform("uRenderMode", 3)
            GLES30.glDepthMask(false)
            GLES30.glBindVertexArray(planetVaoId)
            GLES30.glDrawArrays(GLES30.GL_POINTS, planetsBehindCount, frontCount)
            GLES30.glBindVertexArray(0)
            GLES30.glDepthMask(true)
        }

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    override fun release() {
        if (::shader.isInitialized) shader.release()
        if (::starfieldShader.isInitialized) starfieldShader.release()
        for (i in orbitVaoIds.indices) deleteVao(orbitVaoIds[i], orbitVboIds[i])
        for (i in binaryOrbitVaoIds.indices) deleteVao(binaryOrbitVaoIds[i], binaryOrbitVboIds[i])
        hzMesh?.release()
        deleteVao(starVaoId, starVboId)
        deleteVao(planetVaoId, planetVboId)
        deleteVao(sfVaoId, sfVboId)
        deleteVao(sfSolVaoId, sfSolVboId)
    }

    // --- Static geometry (rebuilt when system changes) ---

    private fun rebuildStarfieldBuffer(params: OrbitalParams) {
        deleteVao(sfVaoId, sfVboId); sfVaoId = 0; sfVboId = 0
        deleteVao(sfSolVaoId, sfSolVboId); sfSolVaoId = 0; sfSolVboId = 0
        sfCount = 0

        val count = params.starfieldCount
        if (count <= 0) return

        // Interleave position(3) + color(3) + size(1) = 7 floats per star
        val data = FloatArray(count * 7)
        val pos = params.starfieldPositions
        val col = params.starfieldColors
        val sz = params.starfieldSizes
        for (i in 0 until count) {
            val d = i * 7
            data[d] = pos[i * 3]
            data[d + 1] = pos[i * 3 + 1]
            data[d + 2] = pos[i * 3 + 2]
            data[d + 3] = col[i * 3]
            data[d + 4] = col[i * 3 + 1]
            data[d + 5] = col[i * 3 + 2]
            data[d + 6] = sz[i]
        }
        val result = buildPointVao(data)
        sfVaoId = result.first
        sfVboId = result.second
        sfCount = count

        // Sol marker — single point
        val solPos = params.solPosition
        val solCol = params.solColor
        val solData = floatArrayOf(
            solPos[0], solPos[1], solPos[2],
            solCol[0], solCol[1], solCol[2],
            params.solSize,
        )
        val solResult = buildPointVao(solData)
        sfSolVaoId = solResult.first
        sfSolVboId = solResult.second
    }

    private fun rebuildStaticGeometry(params: OrbitalParams) {
        for (i in orbitVaoIds.indices) deleteVao(orbitVaoIds[i], orbitVboIds[i])
        hzMesh?.release()
        hzMesh = null
        deleteVao(starVaoId, starVboId)
        starVaoId = 0; starVboId = 0

        // Reset per-planet fade state so new system's planets appear at their
        // correct target visibility immediately (no fade-in on system switch).
        planetCurrentAlpha = FloatArray(0)

        // Allocate orbit trail VAOs (one per planet, data updated each frame)
        val n = params.planets.size
        orbitVaoIds = IntArray(n)
        orbitVboIds = IntArray(n)
        val orbitBytes = ORBIT_SEGMENTS * 7 * 4
        for (i in 0 until n) {
            val vao = IntArray(1); val vbo = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glGenBuffers(1, vbo, 0)
            GLES30.glBindVertexArray(vao[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, orbitBytes, null, GLES30.GL_DYNAMIC_DRAW)
            val stride = 7 * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glBindVertexArray(0)
            orbitVaoIds[i] = vao[0]
            orbitVboIds[i] = vbo[0]
        }
        orbitTrailBuffer = ByteBuffer.allocateDirect(orbitBytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        currentPositions = Array(n) { floatArrayOf(0f, 0f, 0f) }

        // Allocate binary star orbit trail VAOs (same layout, 2 orbits)
        for (i in binaryOrbitVaoIds.indices) deleteVao(binaryOrbitVaoIds[i], binaryOrbitVboIds[i])
        if (params.isCircumbinary) {
            binaryOrbitVaoIds = IntArray(2)
            binaryOrbitVboIds = IntArray(2)
            for (i in 0 until 2) {
                val vao = IntArray(1); val vbo = IntArray(1)
                GLES30.glGenVertexArrays(1, vao, 0)
                GLES30.glGenBuffers(1, vbo, 0)
                GLES30.glBindVertexArray(vao[0])
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, orbitBytes, null, GLES30.GL_DYNAMIC_DRAW)
                val stride = 7 * 4
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)
                GLES30.glBindVertexArray(0)
                binaryOrbitVaoIds[i] = vao[0]
                binaryOrbitVboIds[i] = vbo[0]
            }
        } else {
            binaryOrbitVaoIds = IntArray(0)
            binaryOrbitVboIds = IntArray(0)
        }

        // Build HZ annulus
        if (params.hzInnerAU != null && params.hzOuterAU != null) {
            val maxAU = params.planets.maxOf { it.smaAU }
            hzMesh = buildHZAnnulus(params.hzInnerAU, params.hzOuterAU, maxAU)
        }

        // Build star point
        buildStarPoint(params)
    }

    private fun buildHZAnnulus(
        innerAU: Double,
        outerAU: Double,
        maxAU: Double,
    ): Mesh {
        val innerR = linearScale(innerAU, maxAU)
        val outerR = linearScale(outerAU, maxAU)

        // Build triangle strip ring in XZ plane
        val vertCount = (HZ_SEGMENTS + 1) * 2
        val floatsPerVert = 7 // pos(3) + color(3) + size(1)
        val data = FloatArray(vertCount * floatsPerVert)
        var vi = 0

        // HZ color: terra green, dimmed — alpha handled in fragment shader
        val r = 0.478f * 0.35f // compositionTerra.text ~= #7AB89E = (122, 184, 158)
        val g = 0.722f * 0.35f
        val b = 0.620f * 0.35f

        for (i in 0..HZ_SEGMENTS) {
            val angle = 2.0 * PI * i / HZ_SEGMENTS
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()

            // Outer vertex
            data[vi++] = outerR * cosA; data[vi++] = 0f; data[vi++] = outerR * sinA
            data[vi++] = r; data[vi++] = g; data[vi++] = b
            data[vi++] = 1f

            // Inner vertex
            data[vi++] = innerR * cosA; data[vi++] = 0f; data[vi++] = innerR * sinA
            data[vi++] = r; data[vi++] = g; data[vi++] = b
            data[vi++] = 1f
        }

        return buildCustomVao(data, vertCount, GLES30.GL_TRIANGLE_STRIP)
    }

    private fun buildStarPoint(params: OrbitalParams) {
        // Adapted from SystemStripView sizing logic:
        // Star size relative to REFERENCE_SCREEN_HEIGHT, clamped, and forced >= largest planet.
        // The star shader (mode 0) renders the solid core at 60% of point radius
        // (dist < 0.36 in squared coords, sqrt(0.36) ≈ 0.6), with the outer 40% as
        // a transparent glow. Planets (mode 3) are solid to the edge. To make the
        // star's visible core match the strip's solid circle, scale up by 1/0.6.
        val CORE_FRACTION = 0.6f

        val h = REFERENCE_SCREEN_HEIGHT
        val maxStarSize = h * 0.35f
        val minStarSize = h * 0.06f
        val rawStarSize = params.starRadiusSolar?.let { (it * h * 0.08f).toFloat() } ?: (h * 0.10f)
        var starSize = rawStarSize.coerceIn(minStarSize, maxStarSize)

        // Ensure star appears at least as large as the largest planet.
        // When they're comparable, scale proportionally so the relative sizes are correct.
        val radii = params.planets.map { it.radiusEarth ?: 1.0 }
        if (radii.isNotEmpty()) {
            val maxRad = radii.max()
            val planetSizes = computePlanetSizes(params)
            val largestPlanetSize = planetSizes.max()
            val starRadiusEarth = (params.starRadiusSolar ?: 1.0) * 109.076
            val largestPlanetFraction = maxRad / starRadiusEarth
            if (largestPlanetFraction < 0.3) {
                starSize = max(starSize, largestPlanetSize)
            } else {
                val proportionalSize = (starRadiusEarth / maxRad).toFloat() * largestPlanetSize
                starSize = max(starSize, proportionalSize)
            }
        }

        // Compensate for glow: inflate so the opaque core matches the intended size
        starSize /= CORE_FRACTION

        starPointSize = starSize

        if (params.isCircumbinary) {
            // Binary star: two points on elliptical orbits around barycenter (origin)
            val maxAU = params.planets.maxOfOrNull { it.smaAU } ?: 1.0
            val glSma = linearScale(params.binaryStarSeparationAU, maxAU)
            val ecc = params.binaryEccentricity
            val companionSize = (params.companionRadiusSolar?.let { (it * h * 0.08f).toFloat() }
                ?.coerceIn(minStarSize, maxStarSize) ?: (starSize * 0.7f)) / CORE_FRACTION

            // Store animation state for per-frame updates
            binaryGLSma = glSma
            binaryEcc = ecc
            val wRad = toRadians(params.binaryArgPeriapsisDeg.toDouble()).toFloat()
            binaryOmegaCos = cos(wRad)
            binaryOmegaSin = sin(wRad)

            // Initial positions at E=0 (periapsis)
            val massFrac = params.primaryMassFraction
            val (xRel, zRel) = OrbitalMechanics.orbitalPosition(glSma.toDouble(), ecc.toDouble(), 0.0)
            val xRot = (xRel * binaryOmegaCos - zRel * binaryOmegaSin).toFloat()
            val zRot = (xRel * binaryOmegaSin + zRel * binaryOmegaCos).toFloat()

            binaryStarData = floatArrayOf(
                -xRot * (1f - massFrac), 0f, -zRot * (1f - massFrac),
                params.starColorR, params.starColorG, params.starColorB, starSize,
                xRot * massFrac, 0f, zRot * massFrac,
                params.companionColorR, params.companionColorG, params.companionColorB, companionSize,
            )
            starFloatBuffer = ByteBuffer.allocateDirect(14 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            // Publish initial positions for UI thread
            binaryStarPositions = floatArrayOf(
                binaryStarData[0], binaryStarData[1], binaryStarData[2],
                binaryStarData[7], binaryStarData[8], binaryStarData[9],
            )

            val result = buildPointVao(binaryStarData)
            starVaoId = result.first
            starVboId = result.second
            starVertexCount = 2
        } else {
            val data = floatArrayOf(
                0f, 0f, 0f, // position
                params.starColorR, params.starColorG, params.starColorB, // color
                starSize, // size
            )
            val result = buildPointVao(data)
            starVaoId = result.first
            starVboId = result.second
            starVertexCount = 1
        }
    }

    // --- Planet sizing (adapted from SystemStripView) ---

    private fun computePlanetSizes(params: OrbitalParams): FloatArray {
        val h = REFERENCE_SCREEN_HEIGHT
        val maxPlanetSize = h * 0.14f
        val minPlanetSize = h * 0.03f
        val radii = params.planets.map { it.radiusEarth ?: 1.0 }
        val maxRad = if (radii.isNotEmpty()) radii.max() else 1.0
        return FloatArray(params.planets.size) { i ->
            ((radii[i] / maxRad) * maxPlanetSize).toFloat().coerceAtLeast(minPlanetSize)
        }
    }

    /**
     * Computes actual screen-space radii (in physical pixels) for each planet and the star,
     * accounting for the depth-dependent point size scaling in the vertex shader.
     * Published via volatile fields for the UI thread (connector line stop distance).
     */
    private fun publishScreenRadii(params: OrbitalParams) {
        val mvp = mvpMatrix
        val sizes = cachedPlanetSizes

        // Star: glow extends to the full point radius. At origin for single stars.
        val starClipZ = mvp[14] // mvp * (0,0,0,1).z
        val starDs = (2f / (1f + starClipZ * 0.5f)).coerceIn(0.3f, 3.0f)
        starScreenRadius = starPointSize * starDs / 2f

        // Planets: use current animated positions
        if (sizes.size != params.planets.size) return
        val radii = FloatArray(params.planets.size)
        for (i in params.planets.indices) {
            val pos = currentPositions[i]
            val clipZ = mvp[2] * pos[0] + mvp[6] * pos[1] + mvp[10] * pos[2] + mvp[14]
            val ds = (2f / (1f + clipZ * 0.5f)).coerceIn(0.3f, 3.0f)
            radii[i] = sizes[i] * ds / 2f
        }
        planetScreenRadii = radii
    }

    // --- Animated planet positions (rebuilt each frame) ---

    /**
     * Computes how many GL units on the Y=0 plane one unit of point size covers,
     * as seen from a top-down camera at the current distance.
     *
     * This is used for proximity culling so the check is consistent regardless
     * of actual camera angle — always computed as if looking straight down.
     */
    private fun topDownPointSizeToGLRadius(): Float {
        val d = cameraController.currentDistance
        val fovRad = cameraController.fovDegrees * PI.toFloat() / 180f

        // Clip-space Z for Y=0 points from a top-down camera at distance D.
        // View transform: (0,0,0) → (0, 0, -D). Perspective matrix Z row gives:
        //   clipZ = D*(f+n)/(f-n) - 2fn/(f-n)
        val n = cameraController.nearPlane
        val f = cameraController.farPlane
        val clipZ = d * (f + n) / (f - n) - 2f * f * n / (f - n)

        // Vertex shader: gl_PointSize = aSize * depthScale * (uScreenHeight / 800)
        // With uScreenHeight = 800, rendered pixel diameter = aSize * depthScale
        val depthScale = (2.0f / (1.0f + clipZ * 0.5f)).coerceIn(0.3f, 3.0f)

        // The perspective projection maps 2*D*tan(fov/2) GL units to viewportHeight pixels.
        // Point sizes use REFERENCE_SCREEN_HEIGHT (800), so physical pixel size = aSize * depthScale.
        // GL radius = (physical pixel radius) * (GL units per physical pixel)
        //           = (aSize * depthScale / 2) * (2 * D * tan(fov/2) / viewportHeight)
        val glPerPhysicalPixel = 2f * d * tan(fovRad / 2f).toFloat() / viewportHeight

        return depthScale * glPerPhysicalPixel / 2f
    }

    private fun rebuildPlanetPositions(
        params: OrbitalParams,
        elapsedDays: Double,
        dtSeconds: Float,
        viewMatrix: FloatArray,
    ) {
        deleteVao(planetVaoId, planetVboId)
        planetVaoId = 0; planetVboId = 0

        val planets = params.planets
        if (planets.isEmpty()) { planetCount = 0; return }

        val maxAU = planets.maxOf { it.smaAU }

        val planetSizes = computePlanetSizes(params)
        cachedPlanetSizes = planetSizes

        // Top-down proximity culling: consistent regardless of camera angle
        val pxToGL = topDownPointSizeToGLRadius()
        val starGlowRadiusGL = starPointSize * pxToGL
        // Small gap in GL units (a few pixels worth)
        val gapGL = 2f * pxToGL // minimal gap

        // First pass: compute positions and determine target visibility
        val targetAlpha = FloatArray(planets.size)

        for (i in planets.indices) {
            val p = planets[i]

            val (xAU, zAU) = if (p.periodDays != null && p.periodDays > 0) {
                val simulatedJD = referenceJD + elapsedDays
                val epoch = p.timeOfPeriapsisBJD ?: p.transitMidpointBJD
                val M = if (epoch != null) {
                    OrbitalMechanics.meanAnomalyAtEpoch(
                        simulatedJD, epoch, p.periodDays,
                        eccentricity = p.eccentricity,
                        argPeriapsisRad = toRadians(p.argPeriapsisDeg),
                        isTransitEpoch = p.timeOfPeriapsisBJD == null,
                    )
                } else {
                    OrbitalMechanics.meanAnomaly(elapsedDays, p.periodDays)
                }
                val E = OrbitalMechanics.solveKeplerEquation(M, p.eccentricity)
                OrbitalMechanics.orbitalPosition(p.smaAU, p.eccentricity, E)
            } else {
                OrbitalMechanics.orbitalPosition(p.smaAU, p.eccentricity, 0.0)
            }

            val aScaled = linearScale(p.smaAU, maxAU)
            val ratio = if (p.smaAU > 0) aScaled / p.smaAU.toFloat() else 1f
            val xFlat = (xAU * ratio).toFloat()
            val zFlat = (zAU * ratio).toFloat()

            // Full 3D orientation: ω (arg periapsis), i (inclination), Ω (long asc node)
            val pos = rotateOrbitalPosition(
                xFlat, zFlat,
                p.argPeriapsisDeg, p.relativeInclinationDeg, p.longAscNodeDeg,
            )
            currentPositions[i] = pos

            // XZ distance from star for proximity culling (top-down projection)
            val xzDist = sqrt(pos[0] * pos[0] + pos[2] * pos[2])
            val planetRadiusGL = planetSizes[i] * pxToGL

            // Target 0.0 when planet edge touches star glow edge (top-down check), 1.0 otherwise.
            targetAlpha[i] = if (xzDist - planetRadiusGL < starGlowRadiusGL + gapGL) 0f else 1f
        }

        // Initialize or smooth per-planet alpha toward target. On system change
        // (size mismatch) we snap to target so planets don't fade in on load.
        if (planetCurrentAlpha.size != planets.size) {
            planetCurrentAlpha = targetAlpha.copyOf()
        } else {
            val rate = 2.5f // ~400ms full fade
            for (i in planets.indices) {
                val cur = planetCurrentAlpha[i]
                val tgt = targetAlpha[i]
                if (cur < tgt) {
                    planetCurrentAlpha[i] = (cur + rate * dtSeconds).coerceAtMost(tgt)
                } else if (cur > tgt) {
                    planetCurrentAlpha[i] = (cur - rate * dtSeconds).coerceAtLeast(tgt)
                }
            }
        }

        // Publish visibility for UI thread (tap detection): a planet is tappable
        // while it's at least half-faded-in. Fading-out planets stop accepting
        // taps past the halfway point; fading-in planets accept taps early.
        visiblePlanets = BooleanArray(planets.size) { planetCurrentAlpha[it] > 0.5f }

        // Sort planets back-to-front by camera view-space Z, then split the
        // sorted list at the star's view-Z so the VBO lays out behind-star
        // vertices first and in-front-of-star vertices second. The draw loop
        // in onFrame() sandwiches the translucent star glow between the two
        // groups — behind-planets get tinted by the halo (blended over),
        // in-front-planets see real star color in their transparent pixels
        // (blended under). Within each group, back-to-front order lets the
        // planet pass skip depth writes: a fading near-planet no longer masks
        // the one behind it with a hard depth boundary.
        val vm = viewMatrix
        val starViewZ = vm[14]
        val vz = FloatArray(planets.size)
        for (i in planets.indices) {
            val pos = currentPositions[i]
            vz[i] = vm[2] * pos[0] + vm[6] * pos[1] + vm[10] * pos[2] + vm[14]
        }
        val sortedIndices = (0 until planets.size).sortedBy { vz[it] }
        var bCount = 0
        for (idx in sortedIndices) {
            if (vz[idx] < starViewZ) bCount++ else break
        }

        // Build buffer with ALL planets (including hidden ones at alpha 0) so
        // the cross-fade is driven by per-vertex alpha rather than VBO churn.
        val floatsPerPlanet = 8
        val data = FloatArray(planets.size * floatsPerPlanet)
        var vi = 0

        for (i in sortedIndices) {
            val pos = currentPositions[i]
            data[vi++] = pos[0]
            data[vi++] = pos[1]
            data[vi++] = pos[2]
            data[vi++] = planets[i].colorR
            data[vi++] = planets[i].colorG
            data[vi++] = planets[i].colorB
            data[vi++] = planetSizes[i]
            data[vi++] = planetCurrentAlpha[i]
        }

        planetCount = planets.size
        planetsBehindCount = bCount
        val result = buildPlanetVao(data)
        planetVaoId = result.first
        planetVboId = result.second
    }

    // --- Orbit trails (updated each frame based on planet positions) ---

    private fun updateOrbitTrails(params: OrbitalParams) {
        val planets = params.planets
        if (planets.isEmpty()) return
        val maxAU = planets.maxOf { it.smaAU }
        val buf = orbitTrailBuffer ?: return

        for (i in planets.indices) {
            val planet = planets[i]
            val pos = currentPositions[i]
            val planetAngle = atan2(pos[2].toDouble(), pos[0].toDouble())

            val aScaled = linearScale(planet.smaAU, maxAU)
            val bScaled = aScaled * sqrt(1.0 - planet.eccentricity * planet.eccentricity).toFloat()
            val focusOffset = aScaled * planet.eccentricity.toFloat()

            // Base orbit line color (dim blue-gray)
            val baseAlpha = if (planet.isEstimated) 0.06f else 0.10f
            val baseR = 0.33f * baseAlpha / 0.2f
            val baseG = 0.38f * baseAlpha / 0.2f
            val baseB = 0.55f * baseAlpha / 0.2f

            // Trail color: lighter gray
            val trailR = 0.45f
            val trailG = 0.48f
            val trailB = 0.55f

            buf.clear()
            for (j in 0 until ORBIT_SEGMENTS) {
                val angle = 2.0 * PI * j / ORBIT_SEGMENTS
                val xFlat = (aScaled * cos(angle)).toFloat() - focusOffset
                val zFlat = -(bScaled * sin(angle)).toFloat()

                // Full 3D orientation (same as planet positions)
                val vPos = rotateOrbitalPosition(
                    xFlat, zFlat,
                    planet.argPeriapsisDeg, planet.relativeInclinationDeg, planet.longAscNodeDeg,
                )

                // Angular distance "behind" the planet (where it's been)
                val vertAngle = atan2(vPos[2].toDouble(), vPos[0].toDouble())
                var behindDist = (vertAngle - planetAngle) % (2.0 * PI)
                if (behindDist < 0) behindDist += 2.0 * PI

                // Trail fades over the half-orbit behind the planet (0→π),
                // the ahead half stays at base color
                val trailFactor = if (behindDist <= PI) {
                    ((1.0 + cos(behindDist)) / 2.0).toFloat()
                } else {
                    0f
                }

                buf.put(vPos[0])
                buf.put(vPos[1])
                buf.put(vPos[2])
                buf.put(baseR + trailFactor * (trailR - baseR))
                buf.put(baseG + trailFactor * (trailG - baseG))
                buf.put(baseB + trailFactor * (trailB - baseB))
                buf.put(1f) // size (unused for lines)
            }
            buf.flip()

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, orbitVboIds[i])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, ORBIT_SEGMENTS * 7 * 4, buf)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // --- Binary star animation (per frame) ---

    /**
     * Computes the relative orbit position, applies ω rotation, and returns
     * the rotated (x, z) in GL space. Used by both star positions and orbit trails.
     */
    private fun binaryRelativePosition(eccentricAnomaly: Double): Pair<Float, Float> {
        val (xRel, zRel) = OrbitalMechanics.orbitalPosition(
            binaryGLSma.toDouble(), binaryEcc.toDouble(), eccentricAnomaly,
        )
        val xRot = (xRel * binaryOmegaCos - zRel * binaryOmegaSin).toFloat()
        val zRot = (xRel * binaryOmegaSin + zRel * binaryOmegaCos).toFloat()
        return xRot to zRot
    }

    private fun updateBinaryStarPositions(params: OrbitalParams, elapsedDays: Double) {
        if (!params.isCircumbinary || starVboId == 0) return

        val period = params.binaryOrbitalPeriodDays
        if (period <= 0) return

        val M = OrbitalMechanics.meanAnomaly(elapsedDays, period)
        val E = OrbitalMechanics.solveKeplerEquation(M, binaryEcc.toDouble())
        val (xRot, zRot) = binaryRelativePosition(E)

        val massFrac = params.primaryMassFraction

        // Primary on opposite side of relative vector from barycenter
        binaryStarData[0] = -xRot * (1f - massFrac)
        binaryStarData[1] = 0f
        binaryStarData[2] = -zRot * (1f - massFrac)

        // Companion along relative vector from barycenter
        binaryStarData[7] = xRot * massFrac
        binaryStarData[8] = 0f
        binaryStarData[9] = zRot * massFrac

        val buf = starFloatBuffer ?: return
        buf.clear()
        buf.put(binaryStarData)
        buf.flip()

        // Publish animated positions for UI thread (tap detection)
        binaryStarPositions = floatArrayOf(
            binaryStarData[0], binaryStarData[1], binaryStarData[2],
            binaryStarData[7], binaryStarData[8], binaryStarData[9],
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 14 * 4, buf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // --- Binary star orbit trails (per frame) ---

    private fun updateBinaryOrbitTrails(params: OrbitalParams, elapsedDays: Double) {
        if (!params.isCircumbinary || binaryOrbitVaoIds.isEmpty()) return

        val period = params.binaryOrbitalPeriodDays
        if (period <= 0) return

        val massFrac = params.primaryMassFraction
        // Scale factors: primary orbit = (1-f) of relative, companion = f of relative
        val scales = floatArrayOf(1f - massFrac, massFrac)
        val flips = floatArrayOf(-1f, 1f) // primary on opposite side of relative vector

        // Current star positions for trail angle calculation
        val M = OrbitalMechanics.meanAnomaly(elapsedDays, period)
        val E = OrbitalMechanics.solveKeplerEquation(M, binaryEcc.toDouble())
        val (xCur, zCur) = binaryRelativePosition(E)
        val starAngles = doubleArrayOf(
            atan2((-zCur * scales[0]).toDouble(), (-xCur * scales[0]).toDouble()),
            atan2((zCur * scales[1]).toDouble(), (xCur * scales[1]).toDouble()),
        )

        val buf = orbitTrailBuffer ?: return

        // Same colors as planet orbit trails (non-estimated)
        val baseR = 0.33f * 0.10f / 0.2f
        val baseG = 0.38f * 0.10f / 0.2f
        val baseB = 0.55f * 0.10f / 0.2f
        val trailR = 0.45f
        val trailG = 0.48f
        val trailB = 0.55f

        for (i in 0 until 2) {
            val scale = scales[i]
            val flip = flips[i]
            val currentAngle = starAngles[i]

            buf.clear()
            for (j in 0 until ORBIT_SEGMENTS) {
                // Parametric eccentric anomaly sweep for ellipse shape
                val Ej = 2.0 * PI * j / ORBIT_SEGMENTS
                val (xRel, zRel) = binaryRelativePosition(Ej)
                val x = flip * xRel * scale
                val z = flip * zRel * scale

                buf.put(x)
                buf.put(0f)
                buf.put(z)

                // Trail color based on angular distance behind the star
                val vertAngle = atan2(z.toDouble(), x.toDouble())
                var behindDist = (vertAngle - currentAngle) % (2.0 * PI)
                if (behindDist < 0) behindDist += 2.0 * PI

                val trailFactor = if (behindDist <= PI) {
                    ((1.0 + cos(behindDist)) / 2.0).toFloat()
                } else {
                    0f
                }

                buf.put(baseR + trailFactor * (trailR - baseR))
                buf.put(baseG + trailFactor * (trailG - baseG))
                buf.put(baseB + trailFactor * (trailB - baseB))
                buf.put(1f) // size (unused for lines)
            }
            buf.flip()

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, binaryOrbitVboIds[i])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, ORBIT_SEGMENTS * 7 * 4, buf)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // --- Scale function ---

    private fun linearScale(au: Double, maxAU: Double): Float {
        if (maxAU <= 0) return 0f
        return ((au / maxAU) * SCALE_RADIUS).toFloat()
    }

    /**
     * Applies full 3D orbital orientation to a flat ellipse position.
     * Rotation order: ω (arg periapsis in-plane), i (inclination tilt), Ω (ascending node).
     */
    private fun rotateOrbitalPosition(
        xFlat: Float,
        zFlat: Float,
        argPeriapsisDeg: Double,
        inclinationDeg: Double,
        longAscNodeDeg: Double,
    ): FloatArray {
        val wRad = toRadians(argPeriapsisDeg).toFloat()
        val iRad = toRadians(inclinationDeg).toFloat()
        val oRad = toRadians(longAscNodeDeg).toFloat()

        // 1. Rotate by ω in XZ plane (argument of periapsis)
        val cosW = cos(wRad); val sinW = sin(wRad)
        val x1 = xFlat * cosW - zFlat * sinW
        val z1 = xFlat * sinW + zFlat * cosW

        // 2. Tilt by i around X axis (inclination)
        val cosI = cos(iRad); val sinI = sin(iRad)
        val x2 = x1
        val y2 = z1 * sinI
        val z2 = z1 * cosI

        // 3. Rotate by Ω around Y axis (longitude of ascending node)
        val cosO = cos(oRad); val sinO = sin(oRad)
        val x3 = x2 * cosO + z2 * sinO
        val z3 = -x2 * sinO + z2 * cosO

        return floatArrayOf(x3, y2, z3)
    }

    // --- VAO helpers ---

    private fun buildPointVao(data: FloatArray): Pair<Int, Int> {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .flip()

        val vaoIds = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        val vboIds = IntArray(1)
        GLES30.glGenBuffers(1, vboIds, 0)

        GLES30.glBindVertexArray(vaoIds[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_DYNAMIC_DRAW)

        val stride = 7 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)

        GLES30.glBindVertexArray(0)
        return vaoIds[0] to vboIds[0]
    }

    /**
     * Planet VAO with an extra per-vertex alpha attribute at location 3.
     * Layout: position(3) + color(3) + size(1) + alpha(1) = 32 bytes/vertex.
     * Other passes (stars, orbits, HZ) use [buildPointVao] / [buildCustomVao]
     * which leave attribute 3 disabled; the fragment shader only reads vAlpha
     * in planet mode (3), so those passes are unaffected.
     */
    private fun buildPlanetVao(data: FloatArray): Pair<Int, Int> {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .flip()

        val vaoIds = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        val vboIds = IntArray(1)
        GLES30.glGenBuffers(1, vboIds, 0)

        GLES30.glBindVertexArray(vaoIds[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_DYNAMIC_DRAW)

        val stride = 8 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 28)

        GLES30.glBindVertexArray(0)
        return vaoIds[0] to vboIds[0]
    }

    private fun buildCustomVao(data: FloatArray, vertexCount: Int, drawMode: Int): Mesh {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .flip()

        val vaoIds = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        val vboIds = IntArray(1)
        GLES30.glGenBuffers(1, vboIds, 0)

        GLES30.glBindVertexArray(vaoIds[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        val stride = 7 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)

        GLES30.glBindVertexArray(0)

        return Mesh(
            vaoId = vaoIds[0],
            vboId = vboIds[0],
            iboId = 0,
            indexCount = vertexCount,
            drawMode = drawMode,
            hasIndices = false,
        )
    }

    private fun deleteVao(vao: Int, vbo: Int) {
        if (vao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        }
    }
}

private const val SCALE_RADIUS = 5.0 // GL units for the outermost orbit

/**
 * Immutable parameters sent from OrbitalScreen to the renderer via GLBridge.
 */
data class OrbitalParamsEntry(
    val smaAU: Double,
    val eccentricity: Double,
    val periodDays: Double?,
    val radiusEarth: Double?,
    val colorR: Float,
    val colorG: Float,
    val colorB: Float,
    val isEstimated: Boolean,
    val relativeInclinationDeg: Double,
    val argPeriapsisDeg: Double,
    val longAscNodeDeg: Double,
    val transitMidpointBJD: Double? = null,
    val timeOfPeriapsisBJD: Double? = null,
)

data class OrbitalParams(
    val planets: List<OrbitalParamsEntry> = emptyList(),
    val starRadiusSolar: Double? = null,
    val starColorR: Float = 1f,
    val starColorG: Float = 0.95f,
    val starColorB: Float = 0.8f,
    val hzInnerAU: Double? = null,
    val hzOuterAU: Double? = null,
    val isPlaying: Boolean = true,
    val daysPerSecond: Double = 1.0,
    val isVisible: Boolean = true,
    val version: Int = 0,
    val isCircumbinary: Boolean = false,
    val companionRadiusSolar: Double? = null,
    val companionColorR: Float = 1f,
    val companionColorG: Float = 0.95f,
    val companionColorB: Float = 0.8f,
    val binaryStarSeparationAU: Double = 0.0,
    val binaryEccentricity: Float = 0f,
    val binaryArgPeriapsisDeg: Float = 0f,
    val binaryOrbitalPeriodDays: Double = 0.0,
    val primaryMassFraction: Float = 0.5f,
    // Background starfield
    val starfieldPositions: FloatArray = FloatArray(0),
    val starfieldColors: FloatArray = FloatArray(0),
    val starfieldSizes: FloatArray = FloatArray(0),
    val starfieldCount: Int = 0,
    val solPosition: FloatArray = floatArrayOf(0f, 0f, 0f),
    val solColor: FloatArray = floatArrayOf(0f, 0f, 0f),
    val solSize: Float = 0f,
    val starfieldVersion: Int = 0,
    // Fade multipliers for HZ annulus + starfield. 0 = hidden, 1 = full.
    val hzAlpha: Float = 1f,
    val starfieldAlpha: Float = 1f,
)
