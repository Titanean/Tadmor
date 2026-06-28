package com.tadmor.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tadmor.domain.classification.visual.PlanetIconProfile
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TERRAIN_SIZE = 96
private const val CLOUD_SIZE = 96
// Rings sit at a fixed distance from the disk for legibility — engine ratios
// can extend rings far past the planet (Saturn-style 2.5R systems) which
// overflows the icon bounds. The 2D icon ignores those and draws a single
// uniform-thickness ring close to the disk.
private const val RING_INNER_R = 1.18f
private const val RING_OUTER_R = 1.42f
private const val RING_STROKE_R = 0.24f

/**
 * Stylized 2D rendition of a planet from a [PlanetIconProfile].
 *
 * Fully lit, totally flat. The only gradient in the entire icon is the
 * atmosphere halo (a soft radial tint outside the disk). Terrain is a
 * 48×48 binary noise mask, upscaled with nearest-neighbor for the chunky
 * look. Gas giants are horizontal hard-edged strips. Rings are stroked
 * arcs with width clamped for legibility.
 */
@Composable
fun PlanetIcon(
    profile: PlanetIconProfile,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Cache the terrain bitmap per (seed, bulkClass) combo. LazyColumn auto-
    // scopes this to visible rows: scrolling off discards both the row's
    // composition and this remember slot.
    val terrain: ImageBitmap? = remember(profile.seed, profile.bulkClass) {
        if (profile.bulkClass != null && profile.bandColors.isEmpty()) {
            buildTerrainBitmap(profile)
        } else null
    }
    // Swirl bitmap for unbanded gas/ice giants (Class IV/V hot Jupiters,
    // select ice giants). Separate from terrain bitmap because it samples
    // the gas-giant palette (`bandColors`) rather than body / accent.
    val swirl: ImageBitmap? = remember(profile.seed, profile.unbanded, profile.bandColors.size) {
        if (profile.unbanded && profile.bandColors.isNotEmpty()) {
            buildSwirlBitmap(profile)
        } else null
    }
    val clouds: ImageBitmap? = remember(profile.seed, profile.cloudCoverage, profile.cloudDensity) {
        if (profile.cloudCoverage > 0.02f && profile.cloudDensity > 0.02f && profile.bandColors.isEmpty()) {
            buildCloudBitmap(profile)
        } else null
    }

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            drawPlanetIcon(profile, terrain, swirl, clouds)
        }
    }
}

private fun DrawScope.drawPlanetIcon(
    profile: PlanetIconProfile,
    terrain: ImageBitmap?,
    swirl: ImageBitmap?,
    clouds: ImageBitmap?,
) {
    val w = this.size.width
    val h = this.size.height
    val cx = w / 2f
    val cy = h / 2f
    val diskRadius = min(w, h) * 0.42f
    val center = Offset(cx, cy)

    // 1. Rings — back half (clipped to upper half of the ring ellipse)
    val hasAtmosphereTint = profile.atmosphereIntensity > 0f && (profile.atmosphereColor ushr 24) != 0L
    val ringMidR = (RING_INNER_R + RING_OUTER_R) * 0.5f * diskRadius
    val ringRy = diskRadius * 0.22f
    val ringStroke = RING_STROKE_R * diskRadius
    if (profile.hasRings) {
        drawRingHalf(
            center = center,
            ringMidR = ringMidR,
            ry = ringRy,
            stroke = ringStroke,
            color = colorFromArgb(profile.ringColor).copy(alpha = profile.ringOpacity),
            backHalf = true,
        )
    }

    // Disk clip path used by terrain, craters, clouds, polar caps
    val diskPath = Path().apply {
        addOval(Rect(center = center, radius = diskRadius))
    }

    // 3. Disk base
    drawCircle(color = colorFromArgb(profile.bodyColor), radius = diskRadius, center = center)

    // 4. Terrain / bands / swirl
    if (profile.bandColors.isNotEmpty() && profile.unbanded && swirl != null) {
        // Unbanded gas / ice giant (Class IV/V hot Jupiter, select ice
        // giants): domain-warped swirl bitmap sampled from the band
        // palette, matching the globe's pure-fluid look. No horizontal
        // strips, no spot overlay — pure vortex appearance.
        clipPath(diskPath) {
            drawImage(
                image = swirl,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(TERRAIN_SIZE, TERRAIN_SIZE),
                dstOffset = IntOffset((cx - diskRadius).toInt(), (cy - diskRadius).toInt()),
                dstSize = IntSize((diskRadius * 2f).toInt(), (diskRadius * 2f).toInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    } else if (profile.bandColors.isNotEmpty()) {
        // Gas giant: vertical-gradient brush with paired stops per
        // band — each palette colour gets TWO stops, one at
        // `(i + 0.5 − δ) / n` and one at `(i + 0.5 + δ) / n`, with
        // `δ = 0.40`. That keeps 80 % of each strip at the solid
        // palette colour and confines the linear interpolation
        // between adjacent palette entries to a narrow 20 %-of-strip
        // boundary zone. Reads as distinct Jupiter / Saturn cloud
        // bands with a soft edge, not the heavy cross-band blur the
        // single-stop-per-band implementation produced (which
        // interpolated across the entire strip width per transition).
        clipPath(diskPath) {
            val top = cy - diskRadius
            val span = diskRadius * 2f
            val n = profile.bandColors.size
            val delta = 0.40f
            val stops = Array(n * 2) { idx ->
                val i = idx / 2
                val side = if (idx % 2 == 0) -delta else delta
                val pos = (i + 0.5f + side) / n
                pos to colorFromArgb(profile.bandColors[i])
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = stops,
                    startY = top,
                    endY = top + span,
                ),
                topLeft = Offset(cx - diskRadius, top),
                size = Size(span, span),
            )
        }
    } else if (terrain != null) {
        // Rocky / ice: bilinear upscale of the 96×96 terrain bitmap
        clipPath(diskPath) {
            drawImage(
                image = terrain,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(TERRAIN_SIZE, TERRAIN_SIZE),
                dstOffset = IntOffset((cx - diskRadius).toInt(), (cy - diskRadius).toInt()),
                dstSize = IntSize((diskRadius * 2f).toInt(), (diskRadius * 2f).toInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }

    // 4b. Spot overlay (GRS, GDS, etc.)
    if (profile.spotColor != 0L && profile.spotWidthFrac > 0f) {
        clipPath(diskPath) {
            val spotW = profile.spotWidthFrac * diskRadius * 2f
            val spotH = profile.spotHeightFrac * diskRadius * 2f
            val spotCx = cx + diskRadius * 0.15f  // slightly right of center
            val spotCy = (cy - diskRadius) + profile.spotY * diskRadius * 2f
            drawOval(
                color = colorFromArgb(profile.spotColor),
                topLeft = Offset(spotCx - spotW / 2f, spotCy - spotH / 2f),
                size = Size(spotW, spotH),
            )
        }
    }

    // 5. Polar caps
    val showTidalCap = profile.tidallyLocked && profile.polarCapExtent >= 0.40f
    val showRotatorCap = !profile.tidallyLocked && profile.polarCapExtent > 0f
    if ((showTidalCap || showRotatorCap) && profile.bandColors.isEmpty()) {
        val capColor = colorFromArgb(profile.polarCapColor)
        if (showTidalCap) {
            val onLeft = (profile.seed and 1L) == 0L
            val crescentWidth = (profile.polarCapExtent * 0.32f * diskRadius)
                .coerceIn(diskRadius * 0.06f, diskRadius * 0.28f)
            val dayCenter = Offset(
                if (onLeft) cx + crescentWidth else cx - crescentWidth,
                cy,
            )
            val dayPath = Path().apply {
                addOval(Rect(center = dayCenter, radius = diskRadius))
            }
            val crescent = Path().apply {
                op(diskPath, dayPath, PathOperation.Difference)
            }
            drawPath(path = crescent, color = capColor)
        } else {
            // Rotator: oval polar cap at the top, wider than tall so it reads
            // as an ice sheet rather than a dot. Rx scales with extent; Ry is
            // ~60% of Rx for the flattened look.
            clipPath(diskPath) {
                val capRx = (profile.polarCapExtent * 1.10f + 0.15f) * diskRadius
                val capRy = capRx * 0.60f
                val capCenter = Offset(cx, cy - diskRadius * 0.78f)
                drawOval(
                    color = capColor,
                    topLeft = Offset(capCenter.x - capRx, capCenter.y - capRy),
                    size = Size(capRx * 2f, capRy * 2f),
                )
            }
        }
    }

    // 6. Craters
    if (profile.craterCount > 0 && profile.bandColors.isEmpty()) {
        val craterColor = darken(colorFromArgb(profile.bodyColor), 0.35f)
        clipPath(diskPath) {
            // Local PRNG seeded from profile.seed, no allocation in tight loop.
            var s = profile.seed xor 0x4352415445524BL
            for (i in 0 until profile.craterCount) {
                s = nextLong(s)
                val ang = (s.toInt() and 0xFFFF) / 65535f * 6.2831853f
                s = nextLong(s)
                val rNorm = sqrt((s.toInt() and 0xFFFF) / 65535f) * 0.85f
                s = nextLong(s)
                val sizeFrac = 0.05f + ((s.toInt() and 0xFFFF) / 65535f) * 0.10f
                val px = cx + cos(ang) * rNorm * diskRadius
                val py = cy + sin(ang) * rNorm * diskRadius
                drawCircle(
                    color = craterColor,
                    radius = sizeFrac * diskRadius,
                    center = Offset(px, py),
                )
            }
        }
    }

    // 7. Atmosphere tint — translucent disc at 1.10× radius for all
    //    densities. Covers the planet body (tinting the surface beneath)
    //    AND extends a faint colored ring past the disc edge. Alpha tracks
    //    intensity so denser atmospheres push toward an opaque overlay:
    //    Earth (intensity ≈ 0.43) lands at ≈ 0.55 — the user-validated
    //    Earth-like value — Mars-thin (0.08) at ≈ 0.20, Venus (0.78) at
    //    the 0.92 upper cap.
    if (hasAtmosphereTint) {
        val tint = colorFromArgb(profile.atmosphereColor)
        val intensity = profile.atmosphereIntensity
        val tintAlpha = (0.10f + intensity * 1.05f).coerceIn(0.20f, 0.92f)
        drawCircle(
            color = tint.copy(alpha = tintAlpha),
            radius = diskRadius * 1.10f,
            center = center,
        )
    }

    // 8. Clouds — noise bitmap, density and coverage scale with cloudCoverage.
    if (clouds != null) {
        clipPath(diskPath) {
            drawImage(
                image = clouds,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(CLOUD_SIZE, CLOUD_SIZE),
                dstOffset = IntOffset((cx - diskRadius).toInt(), (cy - diskRadius).toInt()),
                dstSize = IntSize((diskRadius * 2f).toInt(), (diskRadius * 2f).toInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }

    // 9. Rings — front half (clipped to lower half)
    if (profile.hasRings) {
        drawRingHalf(
            center = center,
            ringMidR = ringMidR,
            ry = ringRy,
            stroke = ringStroke,
            color = colorFromArgb(profile.ringColor).copy(alpha = profile.ringOpacity),
            backHalf = false,
        )
    }
}

private fun DrawScope.drawRingHalf(
    center: Offset,
    ringMidR: Float,
    ry: Float,
    stroke: Float,
    color: Color,
    backHalf: Boolean,
) {
    // The ring is an ellipse with x-radius ringMidR and y-radius ry, stroked.
    // Back half = top semicircle (y < center.y), front half = bottom.
    val rectTopLeft = Offset(center.x - ringMidR, center.y - ry)
    val rectSize = Size(ringMidR * 2f, ry * 2f)
    val clipTop = if (backHalf) center.y - ry - stroke else center.y
    val clipBot = if (backHalf) center.y else center.y + ry + stroke
    clipRect(
        left = center.x - ringMidR - stroke,
        top = clipTop,
        right = center.x + ringMidR + stroke,
        bottom = clipBot,
    ) {
        // Back half is drawn before the disk, so the disk naturally hides
        // any portion behind it — no extra subtraction needed.
        drawArc(
            color = color,
            startAngle = if (backHalf) 180f else 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
    }
}

// ─────────────────────────── Terrain bitmap ───────────────────────────

private fun buildTerrainBitmap(profile: PlanetIconProfile): ImageBitmap {
    val pixels = IntArray(TERRAIN_SIZE * TERRAIN_SIZE)
    val seed = profile.seed
    val bodyArgb = profile.bodyColor.toInt()
    val accentArgb = profile.accentColor.toInt()
    val threshold = profile.landThreshold.coerceIn(0.05f, 0.95f)

    // 2-octave value noise from a hash, folded to one scalar in [0,1].
    val cx = (TERRAIN_SIZE - 1) / 2f
    val cy = (TERRAIN_SIZE - 1) / 2f
    val maxR = TERRAIN_SIZE * 0.5f

    for (y in 0 until TERRAIN_SIZE) {
        for (x in 0 until TERRAIN_SIZE) {
            val dx = x - cx
            val dy = y - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxR + 0.5f) {
                pixels[y * TERRAIN_SIZE + x] = 0  // outside disk — transparent
                continue
            }
            // Two octaves of value noise — lower frequencies = larger continents
            val n1 = valueNoise2(x * 0.09f, y * 0.09f, seed)
            val n2 = valueNoise2(x * 0.22f, y * 0.22f, seed xor 0x1234L)
            val n = (n1 * 0.65f + n2 * 0.35f)
            val argb = if (n > threshold) accentArgb else bodyArgb
            pixels[y * TERRAIN_SIZE + x] = argb
        }
    }

    val bmp = Bitmap.createBitmap(TERRAIN_SIZE, TERRAIN_SIZE, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, TERRAIN_SIZE, 0, 0, TERRAIN_SIZE, TERRAIN_SIZE)
    return bmp.asImageBitmap()
}

/**
 * Swirl bitmap for un-banded gas / ice giants. Two-octave value noise
 * sampled at a domain-warped position, mapped to the bake's band
 * palette via piecewise-linear interpolation between adjacent palette
 * entries. The domain warp gives the colour field a vortex-like flow
 * structure at icon scale rather than the regular blob shapes that
 * naked value noise produces; matches the globe's pure-fluid look
 * (Class IV/V hot Jupiters and select ice giants) at the 60dp icon
 * tier without trying to be a literal fluid sim at this scale.
 */
private fun buildSwirlBitmap(profile: PlanetIconProfile): ImageBitmap {
    val pixels = IntArray(TERRAIN_SIZE * TERRAIN_SIZE)
    val seed = profile.seed xor 0x53574952L  // "SWIR"
    val palette = profile.bandColors
    val n = palette.size

    val cx = (TERRAIN_SIZE - 1) / 2f
    val cy = (TERRAIN_SIZE - 1) / 2f
    val maxR = TERRAIN_SIZE * 0.5f

    for (y in 0 until TERRAIN_SIZE) {
        for (x in 0 until TERRAIN_SIZE) {
            val dx = x - cx
            val dy = y - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxR + 0.5f) {
                pixels[y * TERRAIN_SIZE + x] = 0  // outside disk — transparent
                continue
            }
            // Domain warp via two independent noise samples for the X / Y
            // offsets. The 24-pixel warp magnitude (≈ ¼ of the bitmap)
            // is enough to bend straight noise contours into curling
            // vortex shapes without smearing them into uniform mush.
            val warpX = (valueNoise2(x * 0.08f, y * 0.08f, seed) - 0.5f) * 24f
            val warpY = (valueNoise2(x * 0.08f + 17f, y * 0.08f + 23f, seed xor 0x7FL) - 0.5f) * 24f
            val wx = x + warpX
            val wy = y + warpY
            // Two octaves of value noise at the warped position
            val n1 = valueNoise2(wx * 0.06f, wy * 0.06f, seed xor 0x1234L)
            val n2 = valueNoise2(wx * 0.14f, wy * 0.14f, seed xor 0x5678L)
            val noise = (n1 * 0.7f + n2 * 0.3f).coerceIn(0f, 1f)
            // Map noise [0, 1] across the palette with piecewise-linear
            // interpolation between adjacent colours so transitions read
            // as smooth gradients rather than discrete colour blocks.
            val t = noise * (n - 1)
            val idx0 = t.toInt().coerceIn(0, n - 1)
            val idx1 = (idx0 + 1).coerceAtMost(n - 1)
            val frac = t - idx0
            pixels[y * TERRAIN_SIZE + x] = lerpArgb(
                palette[idx0].toInt(),
                palette[idx1].toInt(),
                frac,
            )
        }
    }

    val bmp = Bitmap.createBitmap(TERRAIN_SIZE, TERRAIN_SIZE, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, TERRAIN_SIZE, 0, 0, TERRAIN_SIZE, TERRAIN_SIZE)
    return bmp.asImageBitmap()
}

/** Linear interpolation between two ARGB ints (channel-wise). */
private fun lerpArgb(a: Int, b: Int, t: Float): Int {
    val tF = t.coerceIn(0f, 1f)
    val aA = (a ushr 24) and 0xFF
    val aR = (a ushr 16) and 0xFF
    val aG = (a ushr 8) and 0xFF
    val aB = a and 0xFF
    val bA = (b ushr 24) and 0xFF
    val bR = (b ushr 16) and 0xFF
    val bG = (b ushr 8) and 0xFF
    val bB = b and 0xFF
    val oA = (aA + (bA - aA) * tF).toInt() and 0xFF
    val oR = (aR + (bR - aR) * tF).toInt() and 0xFF
    val oG = (aG + (bG - aG) * tF).toInt() and 0xFF
    val oB = (aB + (bB - aB) * tF).toInt() and 0xFF
    return (oA shl 24) or (oR shl 16) or (oG shl 8) or oB
}

/**
 * Cloud bitmap: 2-octave value noise softly thresholded by cloudCoverage.
 * Higher coverage lowers the threshold so more pixels are above it; the
 * smoothstep around the threshold gives soft cloud edges. Pixels outside
 * the disk are transparent.
 */
private fun buildCloudBitmap(profile: PlanetIconProfile): ImageBitmap {
    val pixels = IntArray(CLOUD_SIZE * CLOUD_SIZE)
    val seed = profile.seed xor 0x434C4F554453L
    val cloudArgb = profile.cloudColor.toInt()
    val baseR = (cloudArgb shr 16) and 0xFF
    val baseG = (cloudArgb shr 8) and 0xFF
    val baseB = cloudArgb and 0xFF

    val coverage = profile.cloudCoverage.coerceIn(0f, 1f)
    val density = profile.cloudDensity.coerceIn(0f, 1f)
    // threshold = 0 → everything cloudy, 1 → nothing cloudy
    val threshold = 1f - coverage
    val edgeSoft = 0.12f
    // Density modulates per-pixel opacity. Wispy clouds (TRAPPIST-1 f) cap
    // around 35% alpha; thick decks (Venus, Earth tropics) reach near full.
    val maxAlpha = (0.20f + density * 0.75f).coerceIn(0.20f, 0.95f)

    val cx = (CLOUD_SIZE - 1) / 2f
    val cy = (CLOUD_SIZE - 1) / 2f
    val maxR = CLOUD_SIZE * 0.5f

    for (y in 0 until CLOUD_SIZE) {
        for (x in 0 until CLOUD_SIZE) {
            val dx = x - cx
            val dy = y - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxR + 0.5f) {
                pixels[y * CLOUD_SIZE + x] = 0
                continue
            }
            // Lower-frequency noise sampling than the terrain pass — clouds
            // should read as a handful of broad bands/blotches rather than
            // dense speckle. Halving the per-axis scale from 0.14/0.32 to
            // 0.07/0.16 roughly doubles the visible feature size.
            val n1 = valueNoise2(x * 0.07f, y * 0.07f, seed)
            val n2 = valueNoise2(x * 0.16f, y * 0.16f, seed xor 0x5A5AL)
            val n = n1 * 0.65f + n2 * 0.35f
            // Smoothstep around threshold for soft cloud-edge transitions only.
            // No limb fade — clouds render flat across the whole disk.
            val t = ((n - (threshold - edgeSoft)) / (2f * edgeSoft)).coerceIn(0f, 1f)
            val s = t * t * (3f - 2f * t)
            val alpha = (s * maxAlpha * 255f).toInt().coerceIn(0, 255)
            pixels[y * CLOUD_SIZE + x] = (alpha shl 24) or (baseR shl 16) or (baseG shl 8) or baseB
        }
    }

    val bmp = Bitmap.createBitmap(CLOUD_SIZE, CLOUD_SIZE, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, CLOUD_SIZE, 0, 0, CLOUD_SIZE, CLOUD_SIZE)
    return bmp.asImageBitmap()
}

/** Bilinear value noise from a hash grid, returns [0,1]. */
private fun valueNoise2(x: Float, y: Float, seed: Long): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val xf = x - xi
    val yf = y - yi
    val v00 = hash01(xi, yi, seed)
    val v10 = hash01(xi + 1, yi, seed)
    val v01 = hash01(xi, yi + 1, seed)
    val v11 = hash01(xi + 1, yi + 1, seed)
    // Smoothstep interpolation
    val u = xf * xf * (3f - 2f * xf)
    val v = yf * yf * (3f - 2f * yf)
    val a = v00 + (v10 - v00) * u
    val b = v01 + (v11 - v01) * u
    return a + (b - a) * v
}

private fun hash01(x: Int, y: Int, seed: Long): Float {
    var h = (x * 374761393L) xor (y * 668265263L) xor seed
    h = (h xor (h ushr 13)) * 1274126177L
    h = h xor (h ushr 16)
    return ((h and 0xFFFFFFL).toFloat()) / 16777215f
}

// ─────────────────────────── helpers ───────────────────────────

private fun colorFromArgb(argb: Long): Color = Color(argb.toInt())

private fun darken(c: Color, amount: Float): Color {
    val k = (1f - amount).coerceIn(0f, 1f)
    return Color(
        red = c.red * k,
        green = c.green * k,
        blue = c.blue * k,
        alpha = c.alpha,
    )
}

/** Cheap xorshift step for in-loop deterministic positions. */
private fun nextLong(state: Long): Long {
    var x = state
    x = x xor (x shl 13)
    x = x xor (x ushr 7)
    x = x xor (x shl 17)
    return x
}
