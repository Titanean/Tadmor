package com.tadmor.app.gl

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset

/**
 * Screen-to-world ray casting for tap-to-select on 3D point clouds.
 */
object RayCaster {

    /**
     * Finds the closest star to a screen tap within a pixel threshold.
     * Returns the star index, or -1 if none is close enough.
     *
     * Projects each star to screen space and picks the nearest one
     * within [thresholdPx] of the tap point.
     */
    fun pickStar(
        screenX: Float,
        screenY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        starPositions: FloatArray,
        starCount: Int,
        thresholdPx: Float = 40f,
    ): Int {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)

        var bestIndex = -1
        var bestDist = thresholdPx * thresholdPx

        val clip = FloatArray(4)
        for (i in 0 until starCount) {
            val ox = starPositions[i * 3]
            val oy = starPositions[i * 3 + 1]
            val oz = starPositions[i * 3 + 2]

            // Multiply by VP matrix
            clip[0] = vp[0] * ox + vp[4] * oy + vp[8] * oz + vp[12]
            clip[1] = vp[1] * ox + vp[5] * oy + vp[9] * oz + vp[13]
            clip[2] = vp[2] * ox + vp[6] * oy + vp[10] * oz + vp[14]
            clip[3] = vp[3] * ox + vp[7] * oy + vp[11] * oz + vp[15]

            // Behind camera
            if (clip[3] <= 0f) continue

            // NDC
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]

            // Screen space
            val sx = (ndcX + 1f) * 0.5f * viewportWidth
            val sy = (1f - ndcY) * 0.5f * viewportHeight // flip Y

            val dx = sx - screenX
            val dy = sy - screenY
            val d2 = dx * dx + dy * dy

            if (d2 < bestDist) {
                bestDist = d2
                bestIndex = i
            }
        }

        return bestIndex
    }

    /**
     * Projects a 3D world position to screen coordinates, even when behind the camera.
     * Returns the screen position and whether the point is in front of the camera.
     * For behind-camera points, the direction is corrected so it points toward the star.
     */
    fun projectToScreenExtended(
        worldX: Float,
        worldY: Float,
        worldZ: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
    ): Pair<Offset, Boolean> {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)

        val clipX = vp[0] * worldX + vp[4] * worldY + vp[8] * worldZ + vp[12]
        val clipY = vp[1] * worldX + vp[5] * worldY + vp[9] * worldZ + vp[13]
        val clipW = vp[3] * worldX + vp[7] * worldY + vp[11] * worldZ + vp[15]

        val inFront = clipW > 0f
        // Use abs(w) so behind-camera points project to the correct direction
        val absW = if (kotlin.math.abs(clipW) < 0.001f) 0.001f else kotlin.math.abs(clipW)
        val ndcX = clipX / absW
        val ndcY = clipY / absW

        val sx = (ndcX + 1f) * 0.5f * viewportWidth
        val sy = (1f - ndcY) * 0.5f * viewportHeight

        return Offset(sx, sy) to inFront
    }

    /**
     * Projects a 3D world position to screen coordinates.
     * Returns null if the point is behind the camera.
     */
    fun projectToScreen(
        worldX: Float,
        worldY: Float,
        worldZ: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
    ): Offset? {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)

        val w = vp[3] * worldX + vp[7] * worldY + vp[11] * worldZ + vp[15]
        if (w <= 0f) return null

        val ndcX = (vp[0] * worldX + vp[4] * worldY + vp[8] * worldZ + vp[12]) / w
        val ndcY = (vp[1] * worldX + vp[5] * worldY + vp[9] * worldZ + vp[13]) / w

        val sx = (ndcX + 1f) * 0.5f * viewportWidth
        val sy = (1f - ndcY) * 0.5f * viewportHeight

        return Offset(sx, sy)
    }
}
