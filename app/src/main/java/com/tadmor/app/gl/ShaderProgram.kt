package com.tadmor.app.gl

import android.opengl.GLES30
import timber.log.Timber

/**
 * Compiles, links, and manages a GL ES 3.0 shader program.
 * Caches uniform locations for efficient per-frame updates.
 *
 * Must only be used on the GL thread.
 */
class ShaderProgram(vertexSource: String, fragmentSource: String) {

    val programId: Int

    private val uniformCache = HashMap<String, Int>()

    init {
        val vertexShader = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        programId = link(vertexShader, fragmentShader)
        // Shaders can be detached and deleted after linking
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
    }

    fun use() {
        GLES30.glUseProgram(programId)
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    // --- Uniform setters ---

    fun setUniform(name: String, value: Float) {
        GLES30.glUniform1f(location(name), value)
    }

    fun setUniform(name: String, value: Int) {
        GLES30.glUniform1i(location(name), value)
    }

    fun setUniform(name: String, x: Float, y: Float) {
        GLES30.glUniform2f(location(name), x, y)
    }

    fun setUniform(name: String, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(location(name), x, y, z)
    }

    fun setUniformVec4(name: String, x: Float, y: Float, z: Float, w: Float) {
        GLES30.glUniform4f(location(name), x, y, z, w)
    }

    fun setUniformMat4(name: String, matrix: FloatArray) {
        GLES30.glUniformMatrix4fv(location(name), 1, false, matrix, 0)
    }

    fun setUniformMat3(name: String, matrix: FloatArray) {
        GLES30.glUniformMatrix3fv(location(name), 1, false, matrix, 0)
    }

    fun getAttribLocation(name: String): Int =
        GLES30.glGetAttribLocation(programId, name)

    // --- Internal ---

    private fun location(name: String): Int =
        uniformCache.getOrPut(name) {
            val loc = GLES30.glGetUniformLocation(programId, name)
            if (loc == -1) {
                Timber.w("Uniform '$name' not found (may be optimized out)")
            }
            loc
        }

    companion object {

        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                val typeName = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
                error("Failed to compile $typeName shader:\n$log")
            }
            return shader
        }

        fun link(vertexShader: Int, fragmentShader: Int): Int {
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                error("Failed to link shader program:\n$log")
            }
            return program
        }
    }
}
