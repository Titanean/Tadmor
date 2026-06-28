package com.tadmor.app.ui.system

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.tadmor.app.gl.BloomPipeline
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoRenderer
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.gl.MeshBuilder
import com.tadmor.app.gl.Mesh
import com.tadmor.app.gl.ShaderProgram
import com.tadmor.app.gl.ShaderSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Renders a planet globe as a full-screen quad with atmospheric ray marching.
 * Shader port of atmosphere.html — single-pass ray-sphere intersection + scattering integration.
 *
 * Cloud noise is pre-baked to a texture once per planet (on seed change),
 * so cloud density, normals, and shadows are all cheap texture lookups.
 */
class PlanetRenderer(
    private val appContext: Context,
    cameraController: CameraController,
    private val bridge: GLBridge<PlanetGlobeParams>,
) : ExoRenderer(cameraController) {

    private lateinit var shader: ShaderProgram
    private lateinit var bakeShader: ShaderProgram
    private lateinit var gasGiantBakeShader: ShaderProgram
    private lateinit var terrestrialBakeShader: ShaderProgram
    private lateinit var quad: Mesh

    // Matrices for the ray marcher
    private val inverseProjection = FloatArray(16)
    private val inverseView = FloatArray(16)

    // Cloud noise bake texture + FBO
    private var cloudFBO = 0
    private var cloudTexture = 0
    private var lastBakedSeed = Long.MIN_VALUE

    // Shared surface bake FBO + texture (gas giant + terrestrial, same 1024×512 RGBA8)
    private var surfaceFBO = 0
    private var surfaceTexture = 0
    private var permTexture = 0
    private var lastSurfaceSeed = Long.MIN_VALUE
    private var surfaceTextureReady = false

    // Cloud overlay bake FBO + texture (Venus-class opaque-deck terrestrials).
    // Separate from `surfaceTexture` because these planets need BOTH a
    // terrestrial surface bake AND a cloud overlay bake live simultaneously.
    private var cloudOverlayFBO = 0
    private var cloudOverlayTexture = 0
    private var lastCloudOverlaySeed = Long.MIN_VALUE
    private var cloudOverlayReady = false

    // Elapsed time for cloud/fog drift animation
    private var startTimeNanos = 0L
    private var hasStartTime = false

    // Bake deferral. On a fresh seed, frame N skips the bake (renders flat
    // colour while the bake is pending); frame N+1 runs the bake. Combined
    // with the Compose-driven `revealProgress` (which holds at 0 during the
    // page slide-in), the bake stall lands while the planet is invisible.
    private var lastSeedRequested: Long = Long.MIN_VALUE
    private var bakePending = false

    // HDR + bloom post-process
    private val bloom = BloomPipeline(appContext)

    override fun onCreated() {
        Timber.i("PlanetRenderer.onCreated: loading shaders")
        val vertSrc = try {
            ShaderSource.load(appContext, "planet.vert")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to LOAD planet.vert")
            throw t
        }
        Timber.i("  planet.vert loaded (${vertSrc.length} chars)")

        val fragSrc = try {
            ShaderSource.load(appContext, "planet.frag")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to LOAD planet.frag")
            throw t
        }
        Timber.i("  planet.frag loaded (${fragSrc.length} chars)")

        shader = try {
            ShaderProgram(vertSrc, fragSrc)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to COMPILE planet.vert + planet.frag")
            throw t
        }
        Timber.i("  planet shader compiled OK")

        val bakeFrag = try {
            ShaderSource.load(appContext, "cloud_bake.frag")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to LOAD cloud_bake.frag")
            throw t
        }
        bakeShader = try {
            ShaderProgram(vertSrc, bakeFrag)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to COMPILE cloud_bake.frag")
            throw t
        }
        Timber.i("  cloud_bake shader compiled OK")

        quad = MeshBuilder.quad()

        // Create cloud bake FBO + texture (512×256 equirectangular, R16F)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        cloudFBO = fbo[0]

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        cloudTexture = tex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cloudTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F,
            CLOUD_TEX_W, CLOUD_TEX_H, 0,
            GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // ── Gas giant bake shader ──
        val gasGiantFrag = try {
            ShaderSource.load(appContext, "gas_giant_bake.frag")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to LOAD gas_giant_bake.frag")
            throw t
        }
        gasGiantBakeShader = try {
            ShaderProgram(vertSrc, gasGiantFrag)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to COMPILE gas_giant_bake.frag")
            throw t
        }
        Timber.i("  gas_giant_bake shader compiled OK")

        // ── Terrestrial bake shader ──
        val terrestrialFrag = try {
            ShaderSource.load(appContext, "terrestrial_bake.frag")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to LOAD terrestrial_bake.frag")
            throw t
        }
        terrestrialBakeShader = try {
            ShaderProgram(vertSrc, terrestrialFrag)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to COMPILE terrestrial_bake.frag")
            throw t
        }
        Timber.i("  terrestrial_bake shader compiled OK")

        // ── Shared surface FBO + texture (1024×512 RGBA8) ──
        val ggFbo = IntArray(1)
        GLES30.glGenFramebuffers(1, ggFbo, 0)
        surfaceFBO = ggFbo[0]

        val ggTex = IntArray(1)
        GLES30.glGenTextures(1, ggTex, 0)
        surfaceTexture = ggTex[0]

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

        // Cloud overlay bake FBO + texture (1024×512 RGBA8, matches surface bake).
        // Reuses the gas giant bake shader but writes to a separate target so
        // both surface bake and cloud overlay coexist on Venus-class worlds.
        val coFbo = IntArray(1)
        GLES30.glGenFramebuffers(1, coFbo, 0)
        cloudOverlayFBO = coFbo[0]

        val coTex = IntArray(1)
        GLES30.glGenTextures(1, coTex, 0)
        cloudOverlayTexture = coTex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cloudOverlayTexture)
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

        // ── Bloom pipeline ──
        bloom.create()

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Timber.e("GL error after onCreated texture setup: 0x${err.toString(16)}")
        }
        Timber.i("PlanetRenderer.onCreated: complete")
    }

    override fun onResized(width: Int, height: Int) {
        bloom.resize(width, height)
    }

    /**
     * Bakes cloud FBM noise to the equirectangular texture.
     * Runs once per planet (when seed changes). Full 5-octave FBM with warp/distortion
     * is computed in the bake shader — the main shader only does texture lookups.
     */
    private fun bakeCloudNoise(params: PlanetGlobeParams) {
        if (params.seed == lastBakedSeed) return
        lastBakedSeed = params.seed

        // Save current viewport
        val prevViewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        // Render to cloud texture
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cloudFBO)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, cloudTexture, 0,
        )
        GLES30.glViewport(0, 0, CLOUD_TEX_W, CLOUD_TEX_H)

        bakeShader.use()
        bakeShader.setUniform("uCloudSize", params.cloudSize)
        bakeShader.setUniform("uCloudDistortion", params.cloudDistortion)
        bakeShader.setUniform("uCloudBanding", params.cloudBanding)
        bakeShader.setUniform("uTime", params.time)

        quad.bind()
        quad.draw()
        quad.unbind()

        // Restore
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
    }

    /**
     * Bakes gas giant band texture to 1024×512 RGBA8 equirectangular map.
     * Runs once per planet (when seed changes). Uses Perlin noise via permutation
     * texture lookup, fluid advection, storm vortices, and band color mapping.
     */
    private fun bakeGasGiantTexture(params: PlanetGlobeParams) {
        val bake = params.gasGiantBake ?: return
        if (params.seed == lastSurfaceSeed) return
        lastSurfaceSeed = params.seed
        surfaceTextureReady = false

        bakeGasGiantInto(bake, surfaceFBO, surfaceTexture)

        surfaceTextureReady = true
    }

    /**
     * Bakes a cloud overlay texture for Venus-class opaque-deck terrestrial
     * worlds. Reuses the gas giant bake shader (always in swirl mode, palette
     * tinted around uCloudColor) but writes to a separate texture so it can
     * coexist with the terrestrial surface bake.
     */
    private fun bakeCloudOverlayTexture(params: PlanetGlobeParams) {
        val bake = params.cloudOverlayBake ?: return
        if (params.seed == lastCloudOverlaySeed) return
        lastCloudOverlaySeed = params.seed
        cloudOverlayReady = false

        bakeGasGiantInto(bake, cloudOverlayFBO, cloudOverlayTexture)

        cloudOverlayReady = true
    }

    /**
     * Shared bake step: uploads the permutation table from the bake's seed,
     * binds [targetFBO]/[targetTexture] as the colour target, and runs
     * `gasGiantBakeShader` once over a 1024×512 quad.
     */
    private fun bakeGasGiantInto(bake: GasGiantBakeData, targetFBO: Int, targetTexture: Int) {
        // ── Generate + upload permutation table from seed ──
        val perm = ByteArray(256)
        val rng = java.util.Random(bake.permSeed.toLong())
        for (i in 0 until 256) perm[i] = rng.nextInt(256).toByte()
        // Fisher-Yates shuffle for better distribution
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

        // ── Save viewport, bind FBO ──
        val prevViewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFBO)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, targetTexture, 0,
        )
        GLES30.glViewport(0, 0, SURFACE_TEX_W, SURFACE_TEX_H)

        gasGiantBakeShader.use()

        // ── Colors ──
        gasGiantBakeShader.setUniform("uColor1", bake.color1R, bake.color1G, bake.color1B)
        gasGiantBakeShader.setUniform("uColor2", bake.color2R, bake.color2G, bake.color2B)
        gasGiantBakeShader.setUniform("uColor3", bake.color3R, bake.color3G, bake.color3B)
        gasGiantBakeShader.setUniform("uColor4", bake.color4R, bake.color4G, bake.color4B)
        gasGiantBakeShader.setUniform("uColor5", bake.color5R, bake.color5G, bake.color5B)

        // ── Band structure ──
        gasGiantBakeShader.setUniform("uBands", bake.bands)
        gasGiantBakeShader.setUniform("uBandBreakup", bake.bandBreakup)
        gasGiantBakeShader.setUniform("uBandSoftness", bake.bandSoftness)
        gasGiantBakeShader.setUniform("uContrast", bake.contrast)
        gasGiantBakeShader.setUniform("uUnbanded", if (bake.unbanded) 1 else 0)
        gasGiantBakeShader.setUniform("uChevronJets", if (bake.chevronJets) 1 else 0)

        // ── Detail ──
        gasGiantBakeShader.setUniform("uMicroDetails", bake.microDetails)
        gasGiantBakeShader.setUniform("uStriations", bake.striations)
        gasGiantBakeShader.setUniform("uTurbulence", bake.turbulence)

        // ── Storms ──
        gasGiantBakeShader.setUniform("uStormIntensity", bake.stormIntensity)
        gasGiantBakeShader.setUniform("uPoleSize", bake.poleSize)
        gasGiantBakeShader.setUniform("uNoiseScale", bake.noiseScale)

        // ── Permutation texture on unit 2 ──
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, permTexture)
        gasGiantBakeShader.setUniform("uPermTex", 2)

        // ── Macro storms ──
        gasGiantBakeShader.setUniform("uNumMacroStorms", bake.macroStorms.size)
        for (i in bake.macroStorms.indices) {
            val s = bake.macroStorms[i]
            gasGiantBakeShader.setUniformVec4("uMacroStorm[$i]", s.x, s.y, s.z, s.radius)
            gasGiantBakeShader.setUniform("uMacroStormProp[$i]", s.strength, 0f)
        }

        // ── Micro storms ──
        gasGiantBakeShader.setUniform("uNumMicroStorms", bake.microStorms.size)
        for (i in bake.microStorms.indices) {
            val ms = bake.microStorms[i]
            gasGiantBakeShader.setUniformVec4("uMicroStorm[$i]", ms.x, ms.y, ms.z, ms.radius)
            gasGiantBakeShader.setUniform("uMicroStormProp[$i]", ms.strength, ms.type.toFloat())
        }

        // ── Draw ──
        quad.bind()
        quad.draw()
        quad.unbind()

        // ── Restore ──
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
    }

    /**
     * Bakes terrestrial surface texture to 1024×512 RGBA8 equirectangular map.
     * RGB = surface color (sRGB encoded). Alpha = terrain elevation [0,1] for bump mapping.
     * Runs once per planet (when seed changes).
     */
    private fun bakeTerrestrialTexture(params: PlanetGlobeParams) {
        val bake = params.terrestrialBake ?: return
        if (params.seed == lastSurfaceSeed) return
        Timber.i("bakeTerrestrialTexture: seed=${params.seed} craters=${bake.craters.size} " +
            "seaLevel=${bake.seaLevel} polar=${bake.polarCap} noiseScale=${bake.noiseScale}")
        lastSurfaceSeed = params.seed
        surfaceTextureReady = false

        // ── Upload permutation table ──
        val perm = ByteArray(256)
        val rng = java.util.Random(bake.permSeed.toLong())
        for (i in 0 until 256) perm[i] = rng.nextInt(256).toByte()
        for (i in 255 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp
        }
        val permBuf = java.nio.ByteBuffer.allocateDirect(256)
            .order(java.nio.ByteOrder.nativeOrder())
        permBuf.put(perm).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, permTexture)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 256, 1,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, permBuf)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // ── Bind FBO ──
        val prevViewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, surfaceFBO)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, surfaceTexture, 0)
        GLES30.glViewport(0, 0, SURFACE_TEX_W, SURFACE_TEX_H)

        terrestrialBakeShader.use()

        // ── 6-stop terrain palette ──
        terrestrialBakeShader.setUniform("uTerrain[0]", bake.t0R, bake.t0G, bake.t0B)
        terrestrialBakeShader.setUniform("uTerrain[1]", bake.t1R, bake.t1G, bake.t1B)
        terrestrialBakeShader.setUniform("uTerrain[2]", bake.t2R, bake.t2G, bake.t2B)
        terrestrialBakeShader.setUniform("uTerrain[3]", bake.t3R, bake.t3G, bake.t3B)
        terrestrialBakeShader.setUniform("uTerrain[4]", bake.t4R, bake.t4G, bake.t4B)
        terrestrialBakeShader.setUniform("uTerrain[5]", bake.t5R, bake.t5G, bake.t5B)
        terrestrialBakeShader.setUniform("uColorWater", bake.colorWaterR, bake.colorWaterG, bake.colorWaterB)
        terrestrialBakeShader.setUniform("uWaterIsIce", if (bake.waterIsIce) 1 else 0)
        terrestrialBakeShader.setUniform("uColorPolar", bake.colorPolarR, bake.colorPolarG, bake.colorPolarB)

        // ── Composition ──
        terrestrialBakeShader.setUniform("uWaterFraction",  bake.waterFraction)
        terrestrialBakeShader.setUniform("uCarbonFraction", bake.carbonFraction)
        terrestrialBakeShader.setUniform("uTholinFraction", bake.tholinFraction)

        // ── Context ──
        terrestrialBakeShader.setUniform("uSeaLevel",           bake.seaLevel)
        terrestrialBakeShader.setUniform("uPolarCap",           bake.polarCap)
        terrestrialBakeShader.setUniform("uRoughness",          bake.roughness)
        terrestrialBakeShader.setUniform("uNoiseScale",         bake.noiseScale)
        terrestrialBakeShader.setUniform("uCraterDensityField", bake.craterDensityField)
        terrestrialBakeShader.setUniform("uVolcanism",          bake.volcanism)
        terrestrialBakeShader.setUniform("uMoltenSurface",      if (bake.moltenSurface) 1 else 0)
        terrestrialBakeShader.setUniform("uTidallyLocked",      if (bake.tidallyLocked) 1 else 0)
        terrestrialBakeShader.setUniform("uSubSolarDir",        bake.subSolarX, bake.subSolarY, bake.subSolarZ)

        // ── Permutation texture on unit 2 ──
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, permTexture)
        terrestrialBakeShader.setUniform("uPermTex", 2)

        // ── Craters ──
        terrestrialBakeShader.setUniform("uNumCraters", bake.craters.size)
        for (i in bake.craters.indices) {
            val c = bake.craters[i]
            terrestrialBakeShader.setUniformVec4("uCrater[$i]", c.x, c.y, c.z, c.radius)
            terrestrialBakeShader.setUniform("uCraterProp[$i]", c.depth, c.degradation)
        }

        // ── Draw ──
        quad.bind()
        quad.draw()
        quad.unbind()

        // ── Restore ──
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Timber.e("GL error after terrestrial bake: 0x${err.toString(16)}")
        }

        surfaceTextureReady = true
        Timber.i("bakeTerrestrialTexture: done")
    }

    override fun onFrame() {
        val params = bridge.consume() ?: return
        if (!params.isVisible) return

        // Defer bakes by one frame so the GPU stall lands while the planet is
        // still hidden (uReveal=0). On the first frame after a seed change we
        // render an invisible black frame; on the next frame we run the bakes
        // and start the reveal animation. Re-entries with the same seed skip
        // the deferral entirely (no new bakes needed).
        val seedChanged = params.seed != lastSeedRequested
        if (seedChanged) {
            lastSeedRequested = params.seed
            bakePending = true
        } else if (bakePending) {
            if (params.cloudCoverage > 0f) {
                if (params.seed != lastBakedSeed) hasStartTime = false
                bakeCloudNoise(params)
            }
            when {
                params.gasGiantBake != null   -> bakeGasGiantTexture(params)
                params.terrestrialBake != null -> bakeTerrestrialTexture(params)
            }
            // Cloud overlay bakes independently of the surface bake — Venus-
            // class worlds run BOTH (terrestrial surface + cloud overlay).
            if (params.cloudOverlayBake != null) {
                bakeCloudOverlayTexture(params)
            }
            bakePending = false
        }

        // Snapshot the camera once so view / projection / eye come from the
        // same matrix swap. Reading them separately risks pairing a stale
        // view matrix with a fresh eye position during active dragging,
        // which renders as a one-frame "flash in the drag direction" — the
        // ray-march origin (uCameraPos) jumps ahead while the screen-space
        // mapping (uInverseView) is one tick behind.
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

        // ── Camera ──
        shader.setUniformMat4("uInverseProjection", inverseProjection)
        shader.setUniformMat4("uInverseView", inverseView)
        shader.setUniform("uCameraPos", cam.eye[0], cam.eye[1], cam.eye[2])

        // ── Sun ──
        shader.setUniform("uSunDirection", params.sunDirX, params.sunDirY, params.sunDirZ)
        shader.setUniform("uSunIntensity", params.sunIntensity)
        shader.setUniform("uSunColor", params.sunColorR, params.sunColorG, params.sunColorB)
        shader.setUniform("uSunSize", params.sunSize)
        shader.setUniform("uSunDistanceAU", params.sunDistanceAU)

        // ── Secondary sun (circumbinary planets) ──
        // hasSecondarySun gates the second-sun branches in planet.frag.
        // When 0 (single-star planet), the shader skips every secondary
        // contribution behind a uniform branch — no fragment-level cost
        // beyond the branch test.
        shader.setUniform("uHasSecondarySun", if (params.hasSecondarySun) 1 else 0)
        shader.setUniform("uSun2Direction", params.sun2DirX, params.sun2DirY, params.sun2DirZ)
        shader.setUniform("uSun2Intensity", params.sun2Intensity)
        shader.setUniform("uSun2Color", params.sun2ColorR, params.sun2ColorG, params.sun2ColorB)
        shader.setUniform("uSun2Size", params.sun2Size)
        shader.setUniform("uSun2DistanceAU", params.sun2DistanceAU)

        // ── Planet ──
        shader.setUniform("uPlanetRadius", params.planetRadiusKm)
        shader.setUniform("uOblateness", params.oblateness)
        shader.setUniform("uPlanetColor", params.planetColorR, params.planetColorG, params.planetColorB)

        // ── Surface texture or flat color ──
        val hasSurfaceTexture = surfaceTextureReady &&
            (params.gasGiantBake != null || params.terrestrialBake != null)
        if (hasSurfaceTexture) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, surfaceTexture)
            shader.setUniform("uPlanetTexture", 0)
            shader.setUniform("uUseTexture", 1)
            shader.setUniform("uBumpStrength", params.bumpStrength)
        } else {
            shader.setUniform("uUseTexture", 0)
            shader.setUniform("uBumpStrength", 0f)
        }

        // ── Blackbody thermal emission (hot rocky surfaces >700 K) ──
        shader.setUniform("uThermalEmission",
            params.thermalEmissionR, params.thermalEmissionG, params.thermalEmissionB)

        // ── Water ocean specular glint (non-zero only for liquid water worlds) ──
        shader.setUniform("uWaterSpecular", params.waterSpecularStrength)

        // ── Lava emission gate (non-zero only when planet actually has lava) ──
        shader.setUniform("uLavaEmission", params.lavaEmission)

        // ── Hapke / Lommel-Seeliger blend for airless regolith bodies ──
        // 0 for atmospheric, wet, molten, or low-crater-density worlds; the
        // CPU-side gate in buildPlanetGlobeParams handles the qualification.
        shader.setUniform("uHapkeStrength", params.hapkeStrength)

        // ── Ring system ──
        shader.setUniform("uHasRings", if (params.hasRings) 1 else 0)
        if (params.hasRings) {
            shader.setUniform("uRingInner", params.ringInner)
            shader.setUniform("uRingOuter", params.ringOuter)
            shader.setUniform("uRingOpacity", params.ringOpacity)
            shader.setUniform("uRingGapCount", params.ringGapCount)
            shader.setUniform("uRingDustiness", params.ringDustiness)
            shader.setUniform("uRingSeed", params.ringSeed)
            val colors = params.ringColors
            shader.setUniform("uRingColorCount", colors.size)
            for (i in colors.indices) {
                val (r, g, b) = colors[i]
                shader.setUniform("uRingColors[$i]", r, g, b)
            }
        }

        // ── Atmosphere extent ──
        shader.setUniform("uAtmosphereThickness", params.atmosphereThicknessKm)

        // ── Ray march step counts (adaptive). Atmosphere thickness drives both
        // axes: thin layers under-sample the smooth optical-depth profile with
        // less visible banding, thick sub-Neptune envelopes need the full 16/4
        // count to avoid stripe artifacts on long sun rays. The hot bottleneck
        // (full-screen sub-Neptune at any framing) keeps full quality but now
        // benefits from the early-termination break — uniform-bound loops are
        // not unrolled by the GLSL compiler, so the `break` becomes a real
        // per-wavefront exit on dense atmospheres. ──
        val iSteps = when {
            params.atmosphereThicknessKm > 300f -> 16
            params.atmosphereThicknessKm > 80f  -> 14
            params.atmosphereThicknessKm > 0f   -> 14  // bumped from 12; banding showed on Mars-likes
            else                                -> 16
        }
        val jSteps = if (params.atmosphereThicknessKm > 80f) 4 else 3
        shader.setUniform("uISteps", iSteps)
        shader.setUniform("uJSteps", jSteps)

        // Reveal multiplier driven from Compose so the animation start can
        // be delayed past the page slide-in. Held at 0 during the slide and
        // ramped 0→1 once the page settles — see PlanetDetailContent for the
        // Animatable that drives this. Held at 0 here as well while the bake
        // is pending so the bake stall always lands invisibly.
        val reveal = if (bakePending) 0f else params.revealProgress
        shader.setUniform("uReveal", reveal)

        // FULL-view inspection toggles. Multiplied into the scattering /
        // absorption / cloud / fog uniforms here so the entire pipeline
        // (sun attenuation, view ray march, ambient injection) sees a
        // single scaled value — animating these on the Compose side
        // produces a smooth fade across all atmosphere/cloud effects.
        val atmVis = params.atmosphereVisibility
        val cloudVis = params.cloudsVisibility

        // ── Rayleigh ──
        shader.setUniform("uRayleighScattering",
            params.rayleighR * atmVis, params.rayleighG * atmVis, params.rayleighB * atmVis)
        shader.setUniform("uRayleighScaleHeight", params.rayleighScaleHeightKm)

        // ── Mie ──
        shader.setUniform("uMieScattering",
            params.mieR * atmVis, params.mieG * atmVis, params.mieB * atmVis)
        shader.setUniform("uMieAbsorption",
            params.mieAbsorptionR * atmVis, params.mieAbsorptionG * atmVis, params.mieAbsorptionB * atmVis)
        shader.setUniform("uMieScaleHeight", params.mieScaleHeightKm)
        shader.setUniform("uMiePhaseG", params.miePhaseG)
        shader.setUniform("uMiePhaseG2", params.miePhaseG2)
        shader.setUniform("uMiePhaseBlend", params.miePhaseBlend)
        shader.setUniform("uMieDirtiness", params.mieDirtiness)

        // ── Absorption band ──
        shader.setUniform("uOzoneAbsorption",
            params.ozoneR * atmVis, params.ozoneG * atmVis, params.ozoneB * atmVis)
        shader.setUniform("uOzoneCenter", params.ozoneCenterKm)
        shader.setUniform("uOzoneWidth", params.ozoneWidthKm)

        // ── Clouds ──
        shader.setUniform("uCloudColor", params.cloudColorR, params.cloudColorG, params.cloudColorB)
        shader.setUniform("uCloudCoverage", params.cloudCoverage * cloudVis)
        shader.setUniform("uCloudDensity", params.cloudDensity * cloudVis)
        shader.setUniform("uCloudAltitude", params.cloudAltitudeKm)
        shader.setUniform("uCloudBumpiness", params.cloudBumpiness)

        // ── Baked cloud noise texture ──
        val hasClouds = params.cloudCoverage * cloudVis > 0f
        shader.setUniform("uHasCloudTexture", if (hasClouds) 1 else 0)
        if (hasClouds) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cloudTexture)
            shader.setUniform("uCloudNoiseMap", 1)
        }

        // ── Cloud overlay texture (Venus-class opaque deck) ──
        // When present, planet.frag samples this as a per-pixel cloud-colour
        // tint replacing the flat uCloudColor in the cloud lighting block.
        // Density and bumpiness still come from the procedural cloud field.
        val hasCloudOverlay = hasClouds &&
            cloudOverlayReady &&
            params.cloudOverlayBake != null
        shader.setUniform("uHasCloudOverlay", if (hasCloudOverlay) 1 else 0)
        if (hasCloudOverlay) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cloudOverlayTexture)
            shader.setUniform("uCloudOverlayTexture", 3)
        }

        // ── Fog ──
        shader.setUniform("uFogColor", params.fogColorR, params.fogColorG, params.fogColorB)
        shader.setUniform("uFogDensity", params.fogDensity * cloudVis)
        shader.setUniform("uFogScaleHeight", params.fogScaleHeightKm)
        shader.setUniform("uFogPatchiness", params.fogPatchiness)

        // ── Rendering ──
        shader.setUniform("uCameraExposure", params.cameraExposure)

        // Elapsed time drives cloud drift and fog patchiness animation.
        // Cloud bake uses the deterministic seed-time (frozen); the runtime
        // shader gets a smoothly incrementing value so clouds rotate and fog
        // shifts without re-baking.
        if (!hasStartTime) { startTimeNanos = System.nanoTime(); hasStartTime = true }
        val elapsedSec = (System.nanoTime() - startTimeNanos) / 1e9f
        shader.setUniform("uTime", elapsedSec)
        shader.setUniform("uCloudDriftSpeed", params.cloudDriftSpeed)
        shader.setUniform("uTidallyLocked", if (params.tidallyLocked) 1 else 0)
        shader.setUniform("uAmbientLight", params.ambientLight)

        // Draw full-screen quad into HDR target
        quad.bind()
        quad.draw()
        quad.unbind()

        // Bright extract → blur → composite (tonemap + sRGB) to default framebuffer.
        // Threshold works in LDR (post-tonemap) space now — hot sun-side lit regions
        // and lava emissives sit near 0.85–1.0 post-tonemap and cleanly exceed this.
        bloom.endSceneAndApply(
            quad,
            intensity = 0.8f,
            threshold = 0.55f,
            softKnee = 0.5f,
            blurRadius = 3.5f,
        )

        // Restore state for any other renderers
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    override fun release() {
        bloom.release()
        if (::shader.isInitialized) shader.release()
        if (::bakeShader.isInitialized) bakeShader.release()
        if (::gasGiantBakeShader.isInitialized) gasGiantBakeShader.release()
        if (::terrestrialBakeShader.isInitialized) terrestrialBakeShader.release()
        if (::quad.isInitialized) quad.release()
        if (cloudFBO != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(cloudFBO), 0)
            GLES30.glDeleteTextures(1, intArrayOf(cloudTexture), 0)
        }
        if (surfaceFBO != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(surfaceFBO), 0)
            GLES30.glDeleteTextures(1, intArrayOf(surfaceTexture), 0)
        }
        if (cloudOverlayFBO != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(cloudOverlayFBO), 0)
            GLES30.glDeleteTextures(1, intArrayOf(cloudOverlayTexture), 0)
        }
        if (permTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(permTexture), 0)
        }
    }

    companion object {
        private const val CLOUD_TEX_W = 1024
        private const val CLOUD_TEX_H = 512
        private const val SURFACE_TEX_W = 1024
        private const val SURFACE_TEX_H = 512
    }
}
