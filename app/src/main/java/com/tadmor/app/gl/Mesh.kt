package com.tadmor.app.gl

import android.opengl.GLES30

/**
 * Holds GPU buffer handles for a renderable mesh.
 * Manages VAO/VBO/IBO lifecycle. Must only be used on the GL thread.
 */
class Mesh(
    val vaoId: Int,
    val vboId: Int,
    val iboId: Int,
    val indexCount: Int,
    val drawMode: Int,
    val hasIndices: Boolean,
) {

    fun bind() {
        GLES30.glBindVertexArray(vaoId)
    }

    fun draw() {
        if (hasIndices) {
            GLES30.glDrawElements(drawMode, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        } else {
            GLES30.glDrawArrays(drawMode, 0, indexCount)
        }
    }

    fun unbind() {
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
        if (iboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(iboId), 0)
        }
    }
}
