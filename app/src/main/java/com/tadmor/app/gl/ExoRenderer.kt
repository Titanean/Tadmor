package com.tadmor.app.gl

import android.opengl.GLES30
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import timber.log.Timber

/**
 * Abstract base renderer with common GL setup shared by all 3D views.
 * Clears to the app background color (#06080F), enables depth test and back-face culling.
 *
 * Subclasses implement [onCreated], [onResized], and [onFrame] for their specific rendering.
 */
abstract class ExoRenderer(
    protected val cameraController: CameraController,
) : android.opengl.GLSurfaceView.Renderer {

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Timber.i("${javaClass.simpleName}.onSurfaceCreated")
        // Pure black for atmospheric scattering visibility
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        try {
            onCreated()
        } catch (t: Throwable) {
            Timber.e(t, "${javaClass.simpleName}.onCreated threw")
            throw t
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Timber.i("${javaClass.simpleName}.onSurfaceChanged ${width}x$height")
        GLES30.glViewport(0, 0, width, height)
        cameraController.setAspectRatio(width, height)
        try {
            onResized(width, height)
        } catch (t: Throwable) {
            Timber.e(t, "${javaClass.simpleName}.onResized threw")
            throw t
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        try {
            onFrame()
        } catch (t: Throwable) {
            Timber.e(t, "${javaClass.simpleName}.onFrame threw")
            throw t
        }
    }

    /** Called when the GL surface is created. Load shaders, build meshes here. */
    protected abstract fun onCreated()

    /** Called when the surface size changes. */
    protected abstract fun onResized(width: Int, height: Int)

    /** Called each frame after clearing. Render your scene here. */
    protected abstract fun onFrame()

    /** Override to clean up GL resources (shaders, meshes). */
    open fun release() {}
}
