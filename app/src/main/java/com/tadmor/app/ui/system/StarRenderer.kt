package com.tadmor.app.ui.system

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.tadmor.app.gl.BloomPipeline
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoRenderer
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.gl.Mesh
import com.tadmor.app.gl.MeshBuilder
import com.tadmor.app.gl.ShaderProgram
import com.tadmor.app.gl.ShaderSource
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a star globe as a full-screen quad with procedural convection noise,
 * limb darkening, and corona glow. For brown dwarfs, reuses the gas giant bake
 * pipeline to generate a banded surface texture.
 */
class StarRenderer(
    private val appContext: Context,
    cameraController: CameraController,
    private val bridge: GLBridge<StarGlobeParams>,
) : ExoRenderer(cameraController) {

    private lateinit var shader: ShaderProgram
    private lateinit var gasGiantBakeShader: ShaderProgram
    private lateinit var quad: Mesh

    private val inverseProjection = FloatArray(16)
    private val inverseView = FloatArray(16)

    // Elapsed time for animation
    private var startTimeNanos = 0L
    private var hasStartTime = false

    // Brown dwarf surface bake (reuses gas giant pipeline)
    private var surfaceFBO = 0
    private var surfaceTexture = 0
    private var permTexture = 0
    private var lastBakedSeed = Long.MIN_VALUE
    private var surfaceTextureReady = false

    // HDR + bloom post-process
    private val bloom = BloomPipeline(appContext)

    override fun onCreated() {
        Timber.i("StarRenderer.onCreated: loading shaders")
        val vertSrc = ShaderSource.load(appContext, "planet.vert")
        val fragSrc = ShaderSource.load(appContext, "star_globe.frag")
        shader = ShaderProgram(vertSrc, fragSrc)
        Timber.i("  star_globe shader compiled OK")

        // Gas giant bake shader for brown dwarfs
        val gasGiantFrag = ShaderSource.load(appContext, "gas_giant_bake.frag")
        gasGiantBakeShader = ShaderProgram(vertSrc, gasGiantFrag)
        Timber.i("  gas_giant_bake shader compiled OK")

        quad = MeshBuilder.quad()

        // Surface bake FBO + texture (1024×512 RGBA8)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        surfaceFBO = fbo[0]

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        surfaceTexture = tex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, surfaceTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            SURFACE_TEX_W, SURFACE_TEX_H, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // Permutation texture (256×1 R8, NEAREST)
        val permTex = IntArray(1)
        GLES30.glGenTextures(1, permTex, 0)
        permTexture = permTex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, permTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
            256, 1, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        bloom.create()

        Timber.i("StarRenderer.onCreated: complete")
    }

    override fun onResized(width: Int, height: Int) {
        bloom.resize(width, height)
    }

    /**
     * Bakes gas giant band texture for a brown dwarf surface.
     * Same pipeline as PlanetRenderer's gas giant bake.
     */
    private fun bakeBrownDwarfTexture(params: StarGlobeParams) {
        val bake = params.gasGiantBake ?: return
        if (params.seed == lastBakedSeed) return
        lastBakedSeed = params.seed
        surfaceTextureReady = false

        // Generate + upload permutation table
        val perm = ByteArray(256)
        val rng = java.util.Random(bake.permSeed.toLong())
        for (i in 0 until 256) perm[i] = rng.nextInt(256).toByte()
        for (i in 255 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp
        }

        val permBuf = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder())
        permBuf.put(perm)
        permBuf.position(0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, permTexture)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, 256, 1,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, permBuf,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // Save viewport, bind FBO
        val prevViewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, surfaceFBO)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, surfaceTexture, 0,
        )
        GLES30.glViewport(0, 0, SURFACE_TEX_W, SURFACE_TEX_H)

        gasGiantBakeShader.use()

        // Colors
        gasGiantBakeShader.setUniform("uColor1", bake.color1R, bake.color1G, bake.color1B)
        gasGiantBakeShader.setUniform("uColor2", bake.color2R, bake.color2G, bake.color2B)
        gasGiantBakeShader.setUniform("uColor3", bake.color3R, bake.color3G, bake.color3B)
        gasGiantBakeShader.setUniform("uColor4", bake.color4R, bake.color4G, bake.color4B)
        gasGiantBakeShader.setUniform("uColor5", bake.color5R, bake.color5G, bake.color5B)

        // Band structure
        gasGiantBakeShader.setUniform("uBands", bake.bands)
        gasGiantBakeShader.setUniform("uBandBreakup", bake.bandBreakup)
        gasGiantBakeShader.setUniform("uBandSoftness", bake.bandSoftness)
        gasGiantBakeShader.setUniform("uContrast", bake.contrast)
        gasGiantBakeShader.setUniform("uUnbanded", if (bake.unbanded) 1 else 0)

        // Detail
        gasGiantBakeShader.setUniform("uMicroDetails", bake.microDetails)
        gasGiantBakeShader.setUniform("uStriations", bake.striations)
        gasGiantBakeShader.setUniform("uTurbulence", bake.turbulence)

        // Storms
        gasGiantBakeShader.setUniform("uStormIntensity", bake.stormIntensity)
        gasGiantBakeShader.setUniform("uPoleSize", bake.poleSize)
        gasGiantBakeShader.setUniform("uNoiseScale", bake.noiseScale)

        // Permutation texture on unit 2
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, permTexture)
        gasGiantBakeShader.setUniform("uPermTex", 2)

        // Macro storms
        gasGiantBakeShader.setUniform("uNumMacroStorms", bake.macroStorms.size)
        for (i in bake.macroStorms.indices) {
            val s = bake.macroStorms[i]
            gasGiantBakeShader.setUniformVec4("uMacroStorm[$i]", s.x, s.y, s.z, s.radius)
            gasGiantBakeShader.setUniform("uMacroStormProp[$i]", s.strength, 0f)
        }

        // Micro storms (empty for brown dwarfs)
        gasGiantBakeShader.setUniform("uNumMicroStorms", bake.microStorms.size)

        // Draw
        quad.bind()
        quad.draw()
        quad.unbind()

        // Restore
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])

        surfaceTextureReady = true
        Timber.i("bakeBrownDwarfTexture: done, seed=${params.seed}")
    }

    override fun onFrame() {
        val params = bridge.consume() ?: return
        if (!params.isVisible) return

        // Bake brown dwarf surface if needed
        if (params.gasGiantBake != null) {
            if (params.seed != lastBakedSeed) hasStartTime = false
            bakeBrownDwarfTexture(params)
        }

        // Snapshot camera once — see PlanetRenderer for why reading
        // viewMatrix/projectionMatrix/eyePosition separately produces a
        // one-frame torn-camera flash during active drag.
        val cam = cameraController.snapshot()
        Matrix.invertM(inverseProjection, 0, cam.projection, 0)
        Matrix.invertM(inverseView, 0, cam.view, 0)

        // Full-screen quad: no depth test, no backface culling
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Bind HDR FBO; scene shader writes linear HDR (bloom composite tonemaps)
        bloom.beginScene()

        shader.use()
        shader.setUniform("uPostprocess", 1)

        // Camera
        shader.setUniformMat4("uInverseProjection", inverseProjection)
        shader.setUniformMat4("uInverseView", inverseView)
        shader.setUniform("uCameraPos", cam.eye[0], cam.eye[1], cam.eye[2])

        // Star geometry
        shader.setUniform("uStarRadius", params.starRadiusKm)
        shader.setUniform("uOblateness", params.oblateness)

        // Star appearance
        shader.setUniform("uStarColor", params.colorR, params.colorG, params.colorB)
        shader.setUniform("uStarIntensity", params.intensity)
        shader.setUniform("uLimbDarkeningCoeff", params.limbDarkeningCoeff)
        shader.setUniform("uExposure", params.exposure)
        shader.setUniform("uReveal", params.revealProgress)

        // Noise / convection. uTime drives the slow per-axis drift of the
        // noise lattice (the "boil" of granulation/ridges/corona). The
        // SPIN of the surface is a separate term: uSpinAngle rotates the
        // sample point around the Y axis inside the shader so the pattern
        // physically rotates rather than streaming toward an X pole.
        // Pulsars set params.spinRate ≈ 100 rad/s for the absurd-fast look;
        // normal stars leave it 0 and rely on the slow drift.
        if (!hasStartTime) { startTimeNanos = System.nanoTime(); hasStartTime = true }
        val elapsedSec = (System.nanoTime() - startTimeNanos) / 1e9f
        shader.setUniform("uTime", elapsedSec)
        // Wrap to keep the angle small for fp precision over long sessions.
        val spinAngle = (elapsedSec * params.spinRate) % (2f * Math.PI.toFloat())
        shader.setUniform("uSpinAngle", spinAngle)
        shader.setUniform("uNoiseScale1", params.noiseScale1)
        shader.setUniform("uNoiseScale2", params.noiseScale2)
        shader.setUniform("uConvectionStrength", params.convectionStrength)

        // Per-star 3D noise offset. Seeds are 32-bit hostname hashes, so we
        // spread them across three axes using three independent byte slices
        // and map them into a wide coordinate range (−500..+500) so nearby
        // hashes don't produce visually similar stars.
        val s = params.seed
        val ox = (((s and 0xFFFF) - 32768) / 65.536f)
        val oy = ((((s shr 16) and 0xFFFF) - 32768) / 65.536f)
        val oz = ((((s shr 32) and 0xFFFF) - 32768) / 65.536f)
        shader.setUniform("uNoiseSeedOffset", ox, oy, oz)

        // Corona
        shader.setUniform("uCoronaIntensity", params.coronaIntensity)
        shader.setUniform("uCoronaExtent", params.coronaExtent)

        // Atmospheric limb (brown dwarfs only). Extent = 0 disables it
        // in the shader; the corona is gated off in tandem for the same
        // stars in `buildStarGlobeParams`.
        shader.setUniform("uAtmosphereLimbExtent", params.atmosphereLimbExtent)
        shader.setUniform("uAtmosphereLimbColor",
            params.atmosphereLimbColorR,
            params.atmosphereLimbColorG,
            params.atmosphereLimbColorB)

        // L dwarf detection — gates the Voronoi cellular surface
        // pattern in star_globe.frag so L dwarfs read as distinct
        // bodies vs M dwarfs (which share the same red colour
        // temperature but have blobby granulation, not cells).
        shader.setUniform("uIsLDwarf", if (params.isLDwarf) 1 else 0)

        // Brown dwarf texture path
        val hasBDTexture = params.gasGiantBake != null && surfaceTextureReady
        if (hasBDTexture) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, surfaceTexture)
            shader.setUniform("uPlanetTexture", 0)
            shader.setUniform("uUseTexture", 1)
        } else {
            shader.setUniform("uUseTexture", 0)
        }

        // Draw full-screen quad into HDR target
        quad.bind()
        quad.draw()
        quad.unbind()

        // Bright extract → blur → composite (tonemap + sRGB) to default framebuffer.
        // Stars glow over their full photosphere — lower threshold and higher
        // intensity so the whole disk contributes and the corona genuinely
        // blooms into the surrounding black.
        bloom.endSceneAndApply(
            quad,
            intensity = 1.6f,
            threshold = 0.55f,
            softKnee = 0.75f,
            blurRadius = 5.0f,
        )

        // Restore state
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    override fun release() {
        bloom.release()
        if (::shader.isInitialized) shader.release()
        if (::gasGiantBakeShader.isInitialized) gasGiantBakeShader.release()
        if (::quad.isInitialized) quad.release()
        if (surfaceFBO != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(surfaceFBO), 0)
            GLES30.glDeleteTextures(1, intArrayOf(surfaceTexture), 0)
        }
        if (permTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(permTexture), 0)
        }
    }

    companion object {
        private const val SURFACE_TEX_W = 1024
        private const val SURFACE_TEX_H = 512
    }
}
