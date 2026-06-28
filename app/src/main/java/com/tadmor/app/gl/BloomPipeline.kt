package com.tadmor.app.gl

import android.content.Context
import android.opengl.GLES30
import timber.log.Timber

/**
 * Post-process bloom pipeline for full-screen quad shaders.
 *
 * Pipeline:
 *   1. [beginScene] — bind HDR FBO, caller draws scene with uPostprocess=1 (linear HDR output).
 *   2. [endSceneAndApply] — extract bright pixels → separable Gaussian blur (half-res) →
 *      composite (HDR + bloom, then Reinhard tonemap + sRGB) to the default framebuffer.
 *
 * Uses RGBA16F color attachments; requires GL_EXT_color_buffer_half_float. Falls back
 * silently to RGBA8 if half-float rendering is unavailable (bloom still works, just clips
 * over 1.0 — HDR highlights get clamped before extract).
 *
 * Must only be used on the GL thread.
 */
class BloomPipeline(private val appContext: Context) {

    private lateinit var extractShader: ShaderProgram
    private lateinit var blurShader: ShaderProgram
    private lateinit var compositeShader: ShaderProgram

    private var hdrFBO = 0
    private var hdrTex = 0
    private var brightFBO = 0
    private var brightTex = 0
    private var blurFBO = 0
    private var blurTex = 0

    private var width = 0
    private var height = 0
    private var halfW = 0
    private var halfH = 0
    private var ready = false
    private var useHalfFloat = true

    fun create() {
        val vertSrc = ShaderSource.load(appContext, "planet.vert")
        extractShader = ShaderProgram(vertSrc, ShaderSource.load(appContext, "bloom_extract.frag"))
        blurShader = ShaderProgram(vertSrc, ShaderSource.load(appContext, "bloom_blur.frag"))
        compositeShader = ShaderProgram(vertSrc, ShaderSource.load(appContext, "bloom_composite.frag"))

        // Probe half-float color buffer support once. ES 3.0 requires the
        // EXT_color_buffer_half_float extension to render to RGBA16F.
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        useHalfFloat = extensions.contains("GL_EXT_color_buffer_half_float") ||
            extensions.contains("GL_EXT_color_buffer_float")
        Timber.i("BloomPipeline: half-float render = $useHalfFloat")
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height && ready) return
        releaseTargets()
        width = w
        height = h
        halfW = maxOf(1, w / 2)
        halfH = maxOf(1, h / 2)

        val hdr = createColorFBO(width, height)
        hdrFBO = hdr.first; hdrTex = hdr.second

        val bright = createColorFBO(halfW, halfH)
        brightFBO = bright.first; brightTex = bright.second

        val blur = createColorFBO(halfW, halfH)
        blurFBO = blur.first; blurTex = blur.second

        ready = true
    }

    /**
     * Binds the HDR framebuffer, sets viewport, and clears. The caller should draw the
     * scene with `uPostprocess = 1` so the fragment shader writes linear HDR instead of
     * tonemapped sRGB.
     */
    fun beginScene() {
        if (!ready) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hdrFBO)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Runs the bright extract, separable blur, and final composite passes.
     * Leaves the default framebuffer bound. [quad] is a fullscreen quad mesh.
     */
    fun endSceneAndApply(
        quad: Mesh,
        intensity: Float,
        threshold: Float = 0.75f,
        softKnee: Float = 0.5f,
        blurRadius: Float = 3.0f,
    ) {
        if (!ready) return

        // Bloom passes don't use depth or culling
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // ── Bright extract → brightFBO (half res) ──
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, brightFBO)
        GLES30.glViewport(0, 0, halfW, halfH)
        extractShader.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hdrTex)
        extractShader.setUniform("uHdr", 0)
        extractShader.setUniform("uThreshold", threshold)
        extractShader.setUniform("uSoftKnee", softKnee)
        quad.bind(); quad.draw(); quad.unbind()

        // Two blur iterations (H/V/H/V) for a wider, softer glow radius.
        // Each 9-tap Gaussian has σ ≈ 2 px; stacking doubles the effective radius.
        val stepX = blurRadius / halfW.toFloat()
        val stepY = blurRadius / halfH.toFloat()
        for (pass in 0 until 2) {
            // Horizontal: brightTex → blurTex
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFBO)
            GLES30.glViewport(0, 0, halfW, halfH)
            blurShader.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, brightTex)
            blurShader.setUniform("uTex", 0)
            blurShader.setUniform("uDirection", stepX, 0f)
            quad.bind(); quad.draw(); quad.unbind()

            // Vertical: blurTex → brightTex
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, brightFBO)
            GLES30.glViewport(0, 0, halfW, halfH)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTex)
            blurShader.setUniform("uDirection", 0f, stepY)
            quad.bind(); quad.draw(); quad.unbind()
        }

        // ── Composite → default framebuffer ──
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        compositeShader.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hdrTex)
        compositeShader.setUniform("uHdr", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, brightTex)
        compositeShader.setUniform("uBloom", 1)
        compositeShader.setUniform("uIntensity", intensity)
        quad.bind(); quad.draw(); quad.unbind()

        // Leave texture unit 0 active for the next frame
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        if (::extractShader.isInitialized) extractShader.release()
        if (::blurShader.isInitialized) blurShader.release()
        if (::compositeShader.isInitialized) compositeShader.release()
        releaseTargets()
    }

    // ── Internals ──

    private fun createColorFBO(w: Int, h: Int): Pair<Int, Int> {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        if (useHalfFloat) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
                w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
            )
        } else {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex[0], 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Timber.e("BloomPipeline FBO incomplete: 0x${status.toString(16)} (halfFloat=$useHalfFloat)")
            if (useHalfFloat) {
                // Retry with RGBA8
                useHalfFloat = false
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                    w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
                )
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return fbo[0] to tex[0]
    }

    private fun releaseTargets() {
        val fbos = mutableListOf<Int>()
        val texs = mutableListOf<Int>()
        if (hdrFBO != 0) { fbos += hdrFBO; texs += hdrTex }
        if (brightFBO != 0) { fbos += brightFBO; texs += brightTex }
        if (blurFBO != 0) { fbos += blurFBO; texs += blurTex }
        if (fbos.isNotEmpty()) {
            GLES30.glDeleteFramebuffers(fbos.size, fbos.toIntArray(), 0)
            GLES30.glDeleteTextures(texs.size, texs.toIntArray(), 0)
        }
        hdrFBO = 0; hdrTex = 0
        brightFBO = 0; brightTex = 0
        blurFBO = 0; blurTex = 0
        ready = false
    }
}
