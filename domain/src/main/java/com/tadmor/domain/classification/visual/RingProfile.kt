package com.tadmor.domain.classification.visual

/**
 * Ring system parameters. Nullable on [VisualProfile] — not all planets have rings.
 */
data class RingProfile(
    val innerRadius: Float,
    val outerRadius: Float,
    val colors: List<Long>,
    val opacity: Float,
    val gapCount: Int,
    val dustiness: Float,
    val tiltDeg: Float,
)
