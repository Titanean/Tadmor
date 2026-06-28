package com.tadmor.app.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates standard mesh primitives with a consistent vertex layout:
 *   location 0: vec3 position
 *   location 1: vec3 normal
 *   location 2: vec2 texcoord
 * Stride = 32 bytes (8 floats).
 *
 * Must only be called on the GL thread.
 */
object MeshBuilder {

    private const val FLOATS_PER_VERTEX = 8 // pos(3) + normal(3) + uv(2)
    private const val STRIDE = FLOATS_PER_VERTEX * 4 // 32 bytes

    /**
     * Generates a unit UV sphere centered at the origin.
     */
    fun uvSphere(segments: Int = 64, rings: Int = 32): Mesh {
        val vertexCount = (rings + 1) * (segments + 1)
        val vertices = FloatArray(vertexCount * FLOATS_PER_VERTEX)
        var vi = 0

        for (r in 0..rings) {
            val phi = PI.toFloat() * r / rings // 0 to PI (top to bottom)
            val sinPhi = sin(phi)
            val cosPhi = cos(phi)

            for (s in 0..segments) {
                val theta = 2f * PI.toFloat() * s / segments // 0 to 2PI
                val sinTheta = sin(theta)
                val cosTheta = cos(theta)

                // Position on unit sphere
                val x = cosTheta * sinPhi
                val y = cosPhi
                val z = sinTheta * sinPhi

                // Normal = position for unit sphere
                vertices[vi++] = x   // position
                vertices[vi++] = y
                vertices[vi++] = z
                vertices[vi++] = x   // normal
                vertices[vi++] = y
                vertices[vi++] = z
                vertices[vi++] = s.toFloat() / segments // u
                vertices[vi++] = r.toFloat() / rings    // v
            }
        }

        // Index buffer: two triangles per quad
        val indexCount = rings * segments * 6
        val indices = ShortArray(indexCount)
        var ii = 0
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                val topLeft = (r * (segments + 1) + s).toShort()
                val bottomLeft = ((r + 1) * (segments + 1) + s).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                // First triangle (CCW)
                indices[ii++] = topLeft
                indices[ii++] = bottomLeft
                indices[ii++] = topRight

                // Second triangle (CCW)
                indices[ii++] = topRight
                indices[ii++] = bottomLeft
                indices[ii++] = bottomRight
            }
        }

        return createIndexedMesh(vertices, indices, GLES30.GL_TRIANGLES)
    }

    /**
     * Generates a line loop from a list of 3D points.
     * Each point is a FloatArray of [x, y, z].
     */
    fun lineLoop(points: List<FloatArray>): Mesh {
        val vertices = FloatArray(points.size * FLOATS_PER_VERTEX)
        var vi = 0
        for (p in points) {
            vertices[vi++] = p[0]  // position
            vertices[vi++] = p[1]
            vertices[vi++] = p[2]
            vertices[vi++] = 0f    // normal (unused for lines)
            vertices[vi++] = 1f
            vertices[vi++] = 0f
            vertices[vi++] = 0f    // uv (unused)
            vertices[vi++] = 0f
        }
        return createArrayMesh(vertices, points.size, GLES30.GL_LINE_LOOP)
    }

    /**
     * Generates a full-screen quad from (-1,-1) to (1,1).
     */
    fun quad(): Mesh {
        val vertices = floatArrayOf(
            // pos          normal       uv
            -1f, -1f, 0f,  0f, 0f, 1f,  0f, 0f,
             1f, -1f, 0f,  0f, 0f, 1f,  1f, 0f,
             1f,  1f, 0f,  0f, 0f, 1f,  1f, 1f,
            -1f,  1f, 0f,  0f, 0f, 1f,  0f, 1f,
        )
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
        return createIndexedMesh(vertices, indices, GLES30.GL_TRIANGLES)
    }

    /**
     * Generates a point array from a flat float array of [x,y,z, x,y,z, ...].
     */
    fun pointArray(positions: FloatArray): Mesh {
        val pointCount = positions.size / 3
        val vertices = FloatArray(pointCount * FLOATS_PER_VERTEX)
        var vi = 0
        for (i in 0 until pointCount) {
            vertices[vi++] = positions[i * 3]     // position
            vertices[vi++] = positions[i * 3 + 1]
            vertices[vi++] = positions[i * 3 + 2]
            vertices[vi++] = 0f                    // normal (unused)
            vertices[vi++] = 0f
            vertices[vi++] = 0f
            vertices[vi++] = 0f                    // uv (unused)
            vertices[vi++] = 0f
        }
        return createArrayMesh(vertices, pointCount, GLES30.GL_POINTS)
    }

    // --- Internal mesh creation ---

    private fun createIndexedMesh(
        vertices: FloatArray,
        indices: ShortArray,
        drawMode: Int,
    ): Mesh {
        val vao = genId { GLES30.glGenVertexArrays(1, it, 0) }
        val vbo = genId { GLES30.glGenBuffers(1, it, 0) }
        val ibo = genId { GLES30.glGenBuffers(1, it, 0) }

        GLES30.glBindVertexArray(vao)

        // Upload vertex data
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * 4,
            vertexBuffer,
            GLES30.GL_STATIC_DRAW,
        )

        // Upload index data
        val indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
            .flip()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * 2,
            indexBuffer,
            GLES30.GL_STATIC_DRAW,
        )

        setupVertexAttribs()

        GLES30.glBindVertexArray(0)

        return Mesh(
            vaoId = vao,
            vboId = vbo,
            iboId = ibo,
            indexCount = indices.size,
            drawMode = drawMode,
            hasIndices = true,
        )
    }

    private fun createArrayMesh(
        vertices: FloatArray,
        vertexCount: Int,
        drawMode: Int,
    ): Mesh {
        val vao = genId { GLES30.glGenVertexArrays(1, it, 0) }
        val vbo = genId { GLES30.glGenBuffers(1, it, 0) }

        GLES30.glBindVertexArray(vao)

        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * 4,
            vertexBuffer,
            GLES30.GL_STATIC_DRAW,
        )

        setupVertexAttribs()

        GLES30.glBindVertexArray(0)

        return Mesh(
            vaoId = vao,
            vboId = vbo,
            iboId = 0,
            indexCount = vertexCount,
            drawMode = drawMode,
            hasIndices = false,
        )
    }

    private fun setupVertexAttribs() {
        // location 0: vec3 position
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)

        // location 1: vec3 normal
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE, 12)

        // location 2: vec2 texcoord
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, STRIDE, 24)
    }

    private fun genId(generator: (IntArray) -> Unit): Int {
        val ids = IntArray(1)
        generator(ids)
        return ids[0]
    }
}
