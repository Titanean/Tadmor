package com.tadmor.app.ui.gltest

import android.content.Context
import android.opengl.Matrix
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoRenderer
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.gl.Mesh
import com.tadmor.app.gl.MeshBuilder
import com.tadmor.app.gl.ShaderProgram
import com.tadmor.app.gl.ShaderSource

/**
 * Renders a lit, colored UV sphere to validate the full GL pipeline.
 * Parameters are received via [GLBridge] from the Compose layer.
 */
class TestSphereRenderer(
    private val appContext: Context,
    cameraController: CameraController,
    private val bridge: GLBridge<TestSphereParams>,
) : ExoRenderer(cameraController) {

    private lateinit var shader: ShaderProgram
    private lateinit var sphere: Mesh

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(9)

    private var startTime = 0L

    override fun onCreated() {
        val vertSrc = ShaderSource.load(appContext, "test_sphere.vert")
        val fragSrc = ShaderSource.load(appContext, "test_sphere.frag")
        shader = ShaderProgram(vertSrc, fragSrc)
        sphere = MeshBuilder.uvSphere(segments = 64, rings = 32)
        startTime = System.nanoTime()
    }

    override fun onResized(width: Int, height: Int) {
        // Handled by base class (viewport + camera aspect)
    }

    override fun onFrame() {
        val params = bridge.consume()

        // Slow auto-rotation for visual interest
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        val rotationDeg = elapsed * 15f // 15 degrees per second

        // Model matrix: rotate the sphere
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationDeg, 0f, 1f, 0f)

        // MVP = projection * view * model
        Matrix.multiplyMM(tempMatrix, 0, cameraController.viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, cameraController.projectionMatrix, 0, tempMatrix, 0)

        // Normal matrix = upper-left 3x3 of model matrix (no non-uniform scale, so no inverse transpose needed)
        extractNormalMatrix(modelMatrix, normalMatrix)

        shader.use()
        shader.setUniformMat4("uMVP", mvpMatrix)
        shader.setUniformMat4("uModel", modelMatrix)
        shader.setUniformMat3("uNormalMatrix", normalMatrix)
        shader.setUniform("uColor", params.color[0], params.color[1], params.color[2])
        shader.setUniform("uLightDir", params.lightDir[0], params.lightDir[1], params.lightDir[2])
        shader.setUniform("uLightColor", params.lightColor[0], params.lightColor[1], params.lightColor[2])

        sphere.bind()
        sphere.draw()
        sphere.unbind()
    }

    override fun release() {
        if (::shader.isInitialized) shader.release()
        if (::sphere.isInitialized) sphere.release()
    }

    /**
     * Extracts the upper-left 3x3 from a 4x4 column-major matrix.
     * For uniform rotation/scale, this serves as the normal matrix.
     */
    private fun extractNormalMatrix(mat4: FloatArray, mat3: FloatArray) {
        mat3[0] = mat4[0]; mat3[1] = mat4[1]; mat3[2] = mat4[2]
        mat3[3] = mat4[4]; mat3[4] = mat4[5]; mat3[5] = mat4[6]
        mat3[6] = mat4[8]; mat3[7] = mat4[9]; mat3[8] = mat4[10]
    }
}

/**
 * Immutable parameters for the test sphere renderer.
 * Posted from the Compose layer via [GLBridge].
 */
data class TestSphereParams(
    val color: FloatArray = floatArrayOf(0.48f, 0.72f, 0.62f),   // Terra green
    val lightDir: FloatArray = floatArrayOf(1f, 1f, 1f),          // Upper-right-front
    val lightColor: FloatArray = floatArrayOf(1f, 0.96f, 0.92f),  // Warm white
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TestSphereParams) return false
        return color.contentEquals(other.color) &&
            lightDir.contentEquals(other.lightDir) &&
            lightColor.contentEquals(other.lightColor)
    }

    override fun hashCode(): Int {
        var result = color.contentHashCode()
        result = 31 * result + lightDir.contentHashCode()
        result = 31 * result + lightColor.contentHashCode()
        return result
    }
}
