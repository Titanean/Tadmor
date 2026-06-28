package com.tadmor.app.ui.settings

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoGLSurfaceView
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.ui.system.CraterData
import com.tadmor.app.ui.system.GasGiantBakeData
import com.tadmor.app.ui.system.MicroStormData
import com.tadmor.app.ui.system.PlanetGlobeDebug
import com.tadmor.app.ui.system.PlanetGlobeParams
import com.tadmor.app.ui.system.PlanetRenderer
import com.tadmor.app.ui.system.StormData
import com.tadmor.app.ui.system.TerrestrialBakeData
import com.tadmor.app.ui.system.buildTerrainColors
import com.tadmor.app.ui.system.pickColor
import com.tadmor.app.ui.system.toGlRgb
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.domain.classification.visual.ColorPalettes
import com.tadmor.domain.classification.visual.SurfaceComposition

/**
 * Debug screen for verifying the atmosphere shader port against the atmosphere.html presets.
 * Each preset is a hardcoded [PlanetGlobeParams] with exact values from the web PoC.
 * Also supports loading the current planet's params from [PlanetGlobeDebug] and live-tweaking.
 * Accessible from Settings → Developer → Atmosphere Presets.
 */
@Composable
fun AtmospherePresetScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val appContext = LocalContext.current.applicationContext

    var selectedPreset by remember { mutableStateOf("Earth") }
    var showSliders by remember { mutableStateOf(false) }
    var ambientLight by remember { mutableStateOf(false) }

    // Tweakable overrides — null means use preset value
    var tweakAtmThickness by remember { mutableFloatStateOf(Float.NaN) }
    var tweakDensityMul by remember { mutableFloatStateOf(Float.NaN) }
    var tweakRayleighSH by remember { mutableFloatStateOf(Float.NaN) }
    var tweakMieSH by remember { mutableFloatStateOf(Float.NaN) }
    var tweakFogDens by remember { mutableFloatStateOf(Float.NaN) }
    var tweakFogSH by remember { mutableFloatStateOf(Float.NaN) }
    var tweakSunInt by remember { mutableFloatStateOf(Float.NaN) }
    var tweakExposure by remember { mutableFloatStateOf(Float.NaN) }

    fun resetTweaks() {
        tweakAtmThickness = Float.NaN
        tweakDensityMul = Float.NaN
        tweakRayleighSH = Float.NaN
        tweakMieSH = Float.NaN
        tweakFogDens = Float.NaN
        tweakFogSH = Float.NaN
        tweakSunInt = Float.NaN
        tweakExposure = Float.NaN
    }

    val cameraController = remember {
        CameraController(
            nearPlane = 100f,
            farPlane = 2_000_000f,
            minDistance = 10_000f,
            maxDistance = 500_000f,
        ).apply {
            setDistance(20_000f)
            setOrbitAngles(0f, 15f)
        }
    }

    val bridge = remember { GLBridge(PlanetGlobeParams()) }

    // Resolve base params from preset or captured planet
    val baseParams = if (selectedPreset == CURRENT_PLANET_KEY) {
        PlanetGlobeDebug.lastParams ?: PRESETS["Earth"]!!
    } else {
        PRESETS[selectedPreset] ?: PRESETS["Earth"]!!
    }

    // Apply tweaks via density multiplier scaling.
    // The density multiplier uniformly scales all scattering/absorption coefficients.
    // We infer the original density from Rayleigh B (the most reliable channel).
    val densityMul = if (!tweakDensityMul.isNaN()) tweakDensityMul else 1f
    val params = baseParams.copy(
        atmosphereThicknessKm = if (!tweakAtmThickness.isNaN()) tweakAtmThickness else baseParams.atmosphereThicknessKm,
        rayleighR = baseParams.rayleighR * densityMul,
        rayleighG = baseParams.rayleighG * densityMul,
        rayleighB = baseParams.rayleighB * densityMul,
        rayleighScaleHeightKm = if (!tweakRayleighSH.isNaN()) tweakRayleighSH else baseParams.rayleighScaleHeightKm,
        mieR = baseParams.mieR * densityMul,
        mieG = baseParams.mieG * densityMul,
        mieB = baseParams.mieB * densityMul,
        mieAbsorptionR = baseParams.mieAbsorptionR * densityMul,
        mieAbsorptionG = baseParams.mieAbsorptionG * densityMul,
        mieAbsorptionB = baseParams.mieAbsorptionB * densityMul,
        mieScaleHeightKm = if (!tweakMieSH.isNaN()) tweakMieSH else baseParams.mieScaleHeightKm,
        ozoneR = baseParams.ozoneR * densityMul,
        ozoneG = baseParams.ozoneG * densityMul,
        ozoneB = baseParams.ozoneB * densityMul,
        fogDensity = if (!tweakFogDens.isNaN()) tweakFogDens else baseParams.fogDensity,
        fogScaleHeightKm = if (!tweakFogSH.isNaN()) tweakFogSH else baseParams.fogScaleHeightKm,
        sunIntensity = if (!tweakSunInt.isNaN()) tweakSunInt else baseParams.sunIntensity,
        cameraExposure = if (!tweakExposure.isNaN()) tweakExposure else baseParams.cameraExposure,
    )

    val radius = params.planetRadiusKm

    // Re-configure camera for this planet's scale
    remember(selectedPreset) {
        resetTweaks()
        cameraController.nearPlane = radius * 0.01f
        cameraController.farPlane = radius * 20f
        cameraController.minDistance = radius * 1.5f
        cameraController.maxDistance = radius * 8f
        cameraController.setDistance(radius * 3f)
        true
    }

    val finalParams = if (ambientLight) params.copy(
        sunDirX = 0f, sunDirY = 0f, sunDirZ = -1f,
        sunIntensity = params.sunIntensity * 0.3f,
    ) else params
    bridge.post(finalParams)

    DisposableEffect(Unit) {
        onDispose {
            bridge.post(PlanetGlobeParams(isVisible = false))
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        // GL view — fullscreen
        AndroidView(
            factory = { ctx ->
                val renderer = PlanetRenderer(appContext, cameraController, bridge)
                ExoGLSurfaceView(ctx, renderer, cameraController).also {
                    it.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Back button (top-left)
        val backLineColor = colors.textSecondary
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(spacing.md)
                .size(40.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onBack,
                )
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val arm = size.width * 0.28f
                    drawLine(backLineColor, Offset(cx + arm * 0.1f, cy - arm), Offset(cx - arm, cy), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(backLineColor, Offset(cx - arm, cy), Offset(cx + arm * 0.1f, cy + arm), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
                },
        ) {}

        // Top-right button row
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Ambient light toggle (sun icon)
            val ambientColor = if (ambientLight) colors.accentGold else colors.textSecondary
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { ambientLight = !ambientLight },
                    )
                    .drawBehind {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val sw = 1.2.dp.toPx()
                        // Sun: circle + rays
                        drawCircle(ambientColor, radius = size.width * 0.16f, center = Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
                        val rayLen = size.width * 0.12f
                        val rayStart = size.width * 0.22f
                        for (a in 0 until 8) {
                            val angle = a * 45f * (3.14159f / 180f)
                            val cos = kotlin.math.cos(angle)
                            val sin = kotlin.math.sin(angle)
                            drawLine(ambientColor,
                                Offset(cx + cos * rayStart, cy + sin * rayStart),
                                Offset(cx + cos * (rayStart + rayLen), cy + sin * (rayStart + rayLen)),
                                strokeWidth = sw, cap = StrokeCap.Round)
                        }
                    },
            ) {}

            // Sliders toggle button
            val toggleColor = if (showSliders) colors.accentGold else colors.textSecondary
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { showSliders = !showSliders },
                    )
                    .drawBehind {
                        // Three horizontal lines (hamburger/slider icon)
                        val w = size.width * 0.6f
                        val x0 = (size.width - w) / 2f
                        val sw = 1.2.dp.toPx()
                        for (i in 0..2) {
                            val y = size.height * (0.3f + i * 0.2f)
                            drawLine(toggleColor, Offset(x0, y), Offset(x0 + w, y), strokeWidth = sw, cap = StrokeCap.Round)
                            val dotX = x0 + w * when (i) { 0 -> 0.3f; 1 -> 0.7f; else -> 0.5f }
                            drawCircle(toggleColor, radius = 2.5.dp.toPx(), center = Offset(dotX, y))
                        }
                    },
            ) {}
        }

        // Preset name (top-center)
        val displayName = if (selectedPreset == CURRENT_PLANET_KEY) {
            PlanetGlobeDebug.lastPlanetName.ifEmpty { "Current" }
        } else {
            selectedPreset
        }
        BasicText(
            text = displayName,
            style = type.titleMedium.copy(color = colors.textPrimary),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = spacing.md + 8.dp),
        )

        // Slider panel (right side, when visible)
        if (showSliders) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(220.dp)
                    .padding(end = 8.dp, top = 60.dp, bottom = 80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.background.copy(alpha = 0.85f))
                    .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                    .padding(8.dp),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Parameter readout
                    item {
                        BasicText(
                            text = "Params",
                            style = type.labelLarge.copy(color = colors.accentGold),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    // Key params with sliders
                    item {
                        DebugSlider(
                            label = "Atm Thickness",
                            value = params.atmosphereThicknessKm,
                            range = 0f..2000f,
                            format = { "%.0f km".format(it) },
                            onValueChange = { tweakAtmThickness = it },
                        )
                    }
                    item {
                        DebugSlider(
                            label = "Density ×",
                            value = densityMul,
                            range = 0f..5f,
                            format = { "%.3f".format(it) },
                            onValueChange = { tweakDensityMul = it },
                        )
                    }
                    item {
                        DebugSlider(
                            label = "Rayleigh SH",
                            value = params.rayleighScaleHeightKm,
                            range = 0.1f..100f,
                            format = { "%.1f km".format(it) },
                            onValueChange = { tweakRayleighSH = it },
                        )
                    }
                    item {
                        DebugSlider(
                            label = "Mie SH",
                            value = params.mieScaleHeightKm,
                            range = 0.1f..50f,
                            format = { "%.1f km".format(it) },
                            onValueChange = { tweakMieSH = it },
                        )
                    }
                    item {
                        DebugSlider(
                            label = "Fog Density",
                            value = params.fogDensity,
                            range = 0f..1f,
                            format = { "%.4f".format(it) },
                            onValueChange = { tweakFogDens = it },
                        )
                    }
                    item {
                        DebugSlider(
                            label = "Fog SH",
                            value = params.fogScaleHeightKm,
                            range = 0f..100f,
                            format = { "%.1f km".format(it) },
                            onValueChange = { tweakFogSH = it },
                        )
                    }
                    item {
                        DebugSlider(
                            label = "Sun Intensity",
                            value = params.sunIntensity,
                            range = 0.01f..200f,
                            format = { "%.1f".format(it) },
                            onValueChange = { tweakSunInt = it },
                        )
                    }
                    item {
                        DebugSlider(
                            label = "Exposure",
                            value = params.cameraExposure,
                            range = 0.01f..100f,
                            format = { "%.2f".format(it) },
                            onValueChange = { tweakExposure = it },
                        )
                    }

                    // Read-only info
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        BasicText(
                            text = "Read-only",
                            style = type.labelLarge.copy(color = colors.accentGold),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    item {
                        ParamRow("Radius", "%.0f km".format(baseParams.planetRadiusKm))
                    }
                    item {
                        ParamRow("Oblateness", "%.4f".format(baseParams.oblateness))
                    }
                    item {
                        ParamRow("Rayleigh (pre-scaled)", "%.4f / %.4f / %.4f".format(
                            baseParams.rayleighR, baseParams.rayleighG, baseParams.rayleighB,
                        ))
                    }
                    item {
                        // Show the effective density: reverse-engineer from pre-scaled blue / base blue
                        // Base blue is 33.0 (possibly scaled by composition), so density = prescaled / (33 * 1e-3)
                        val effectiveDensity = if (baseParams.rayleighB > 0f) {
                            baseParams.rayleighB / 0.033f
                        } else 0f
                        ParamRow("Eff. Density ×", "%.3f".format(effectiveDensity))
                    }
                    item {
                        ParamRow("Mie", "%.4f / %.4f / %.4f".format(
                            baseParams.mieR, baseParams.mieG, baseParams.mieB,
                        ))
                    }
                    item {
                        ParamRow("Mie Abs", "%.4f / %.4f / %.4f".format(
                            baseParams.mieAbsorptionR, baseParams.mieAbsorptionG, baseParams.mieAbsorptionB,
                        ))
                    }
                    item {
                        ParamRow("Ozone", "%.4f / %.4f / %.4f".format(
                            baseParams.ozoneR, baseParams.ozoneG, baseParams.ozoneB,
                        ))
                    }
                    item {
                        ParamRow("Sun Dir", "%.2f, %.2f, %.2f".format(
                            baseParams.sunDirX, baseParams.sunDirY, baseParams.sunDirZ,
                        ))
                    }
                    item {
                        ParamRow("Sun Color", "%.2f, %.2f, %.2f".format(
                            baseParams.sunColorR, baseParams.sunColorG, baseParams.sunColorB,
                        ))
                    }
                    item {
                        ParamRow("Sun Dist", "%.3f AU".format(baseParams.sunDistanceAU))
                    }
                    item {
                        ParamRow("Cloud Cov", "%.2f".format(baseParams.cloudCoverage))
                    }
                    item {
                        ParamRow("Cloud Dens", "%.2f".format(baseParams.cloudDensity))
                    }
                    item {
                        ParamRow("Fog Patch", "%.2f".format(baseParams.fogPatchiness))
                    }

                    // Reset button
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        val resetShape = RoundedCornerShape(8.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(resetShape)
                                .background(colors.surfaceRaised)
                                .border(1.dp, colors.divider, resetShape)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { resetTweaks() },
                                )
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                text = "Reset Tweaks",
                                style = type.labelLarge.copy(color = colors.textSecondary),
                            )
                        }
                    }
                }
            }
        }

        // Preset selector (bottom)
        LazyRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = spacing.xl, start = spacing.md, end = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(allPresetNames) { name ->
                val isSelected = name == selectedPreset
                val shape = RoundedCornerShape(20.dp)
                val displayLabel = if (name == CURRENT_PLANET_KEY) {
                    PlanetGlobeDebug.lastPlanetName.ifEmpty { "Current" }
                } else {
                    name
                }
                val hasCaptured = name != CURRENT_PLANET_KEY || PlanetGlobeDebug.lastParams != null
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (isSelected) colors.surfaceRaised else colors.background.copy(alpha = 0.7f))
                        .border(
                            1.dp,
                            when {
                                isSelected -> colors.textSecondary
                                name == CURRENT_PLANET_KEY -> colors.accentGold.copy(alpha = 0.5f)
                                else -> colors.divider
                            },
                            shape,
                        )
                        .then(
                            if (hasCaptured) Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { selectedPreset = name },
                            ) else Modifier
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    BasicText(
                        text = displayLabel,
                        style = type.labelLarge.copy(
                            color = when {
                                isSelected -> colors.textPrimary
                                name == CURRENT_PLANET_KEY && hasCaptured -> colors.accentGold
                                name == CURRENT_PLANET_KEY -> colors.textMuted
                                else -> colors.textTertiary
                            },
                        ),
                    )
                }
            }
        }
    }
}

// ── Debug slider ────────────────────────────────────────────────────────────

@Composable
private fun DebugSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = label,
                style = type.labelSmall.copy(color = colors.textTertiary),
            )
            BasicText(
                text = format(value),
                style = type.labelSmall.copy(color = colors.textSecondary),
            )
        }

        // Track + thumb
        val trackColor = colors.divider
        val fillColor = colors.accentGold
        val thumbColor = colors.textPrimary
        var widthPx by remember { mutableFloatStateOf(1f) }
        val thumbR = with(density) { 5.dp.toPx() }
        val trackH = with(density) { 2.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(range) {
                    detectTapGestures { offset ->
                        val fraction = ((offset.x - thumbR) / (widthPx - 2 * thumbR)).coerceIn(0f, 1f)
                        onValueChange(range.start + fraction * (range.endInclusive - range.start))
                    }
                }
                .pointerInput(range) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val fraction = ((change.position.x - thumbR) / (widthPx - 2 * thumbR)).coerceIn(0f, 1f)
                        onValueChange(range.start + fraction * (range.endInclusive - range.start))
                    }
                }
                .drawBehind {
                    val cy = size.height / 2f
                    val x0 = thumbR
                    val x1 = size.width - thumbR
                    val trackRange = x1 - x0
                    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val thumbX = x0 + fraction * trackRange

                    // Track background
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(x0, cy - trackH / 2),
                        size = Size(trackRange, trackH),
                        cornerRadius = CornerRadius(trackH / 2),
                    )
                    // Filled portion
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = fillColor,
                            topLeft = Offset(x0, cy - trackH / 2),
                            size = Size(fraction * trackRange, trackH),
                            cornerRadius = CornerRadius(trackH / 2),
                        )
                    }
                    // Thumb
                    drawCircle(thumbColor, radius = thumbR, center = Offset(thumbX, cy))
                },
        )
    }
}

@Composable
private fun ParamRow(label: String, value: String) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = label,
            style = type.labelSmall.copy(color = colors.textMuted),
        )
        BasicText(
            text = value,
            style = type.labelSmall.copy(color = colors.textTertiary),
        )
    }
}

// ── Preset data ──────────────────────────────────────────────────────────────

private const val CURRENT_PLANET_KEY = "__current__"

private val PRESET_NAMES = listOf(
    "Earth", "Mars", "Venus", "Jupiter", "Saturn", "Uranus", "Titan", "Neptune", "Triton", "TRAPPIST-1 e",
    "Jupiter (Textured)", "Neptune (Textured)",
    "Earth (Textured)", "Mars (Textured)", "Ocean World", "Lava World", "Ice World", "Mercury (Textured)", "Io",
)

private val allPresetNames = listOf(CURRENT_PLANET_KEY) + PRESET_NAMES

/** Helper to convert hex color string "#RRGGBB" to GL floats. */
private fun hexToRgb(hex: String): Triple<Float, Float, Float> {
    val c = hex.removePrefix("#").toLong(16)
    return Triple(
        ((c shr 16) and 0xFF) / 255f,
        ((c shr 8) and 0xFF) / 255f,
        (c and 0xFF) / 255f,
    )
}

/**
 * Exact presets from atmosphere.html. Values transcribed directly.
 * Scattering coefficients are pre-scaled: base * 1e-3 * densityMultiplier.
 */
private val PRESETS: Map<String, PlanetGlobeParams> by lazy {
    mapOf(
        "Earth" to buildPreset(
            planetRadius = 6371f, oblateness = 0.00335f,
            planetColor = "#142046", atmosphereThickness = 100f,
            density = 1f,
            rayleighSH = 8.5f, rayleighR = 5.5f, rayleighG = 13f, rayleighB = 33f,
            mieSH = 1.2f, mieR = 1.5f, mieG = 1.5f, mieB = 1.5f,
            mieAbsR = 0.2f, mieAbsG = 0.3f, mieAbsB = 0.4f,
            mieG1 = 0.76f, mieG2 = -0.1f, mieBlend = 0.1f, mieDirt = 0.35f,
            ozR = 0.9f, ozG = 1.3f, ozB = 0.1f, ozCenter = 25f, ozWidth = 11f,
            cloudColor = "#ffffff", cloudCov = 0.55f, cloudAlt = 4f, cloudDens = 0.85f,
            cloudSize = 2f, cloudDist = 0.6f, cloudBump = 0.05f, cloudBand = 0.25f,
            fogColor = "#c5d3de", fogDens = 0.02f, fogSH = 3f, fogPatch = 0.95f,
            sunColor = "#fffaf2", sunInt = 40f, sunDist = 1f, sunSize = 1f,
            exposure = 0.4f,
        ),
        "Mars" to buildPreset(
            planetRadius = 3389f, oblateness = 0.00589f,
            planetColor = "#6e3820", atmosphereThickness = 150f,
            density = 0.006f,
            rayleighSH = 11.1f, rayleighR = 10.5f, rayleighG = 9f, rayleighB = 6.5f,
            mieSH = 2.5f, mieR = 8f, mieG = 10f, mieB = 12.5f,
            mieAbsR = 34.2f, mieAbsG = 0.8f, mieAbsB = 0.4f,
            mieG1 = 0.85f, mieG2 = -0.2f, mieBlend = 0.2f, mieDirt = 0.75f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#cabfb1", cloudCov = 0.6f, cloudAlt = 35f, cloudDens = 0.01f,
            cloudSize = 1.8f, cloudDist = 0.1f, cloudBump = 0.15f, cloudBand = 0f,
            fogColor = "#87674b", fogDens = 0.02f, fogSH = 5f, fogPatch = 0.9f,
            sunColor = "#fffaf2", sunInt = 17.2f, sunDist = 1.524f, sunSize = 1f,
            exposure = 1.5f,
        ),
        "Venus" to buildPreset(
            planetRadius = 6051f, oblateness = 0f,
            planetColor = "#c2904c", atmosphereThickness = 400f,
            density = 53f,
            rayleighSH = 15.9f, rayleighR = 3.2f, rayleighG = 4f, rayleighB = 8f,
            mieSH = 12.5f, mieR = 0.08f, mieG = 0.06f, mieB = 0f,
            mieAbsR = 0f, mieAbsG = 0.4f, mieAbsB = 2.5f,
            mieG1 = 0.85f, mieG2 = -0.1f, mieBlend = 0.05f, mieDirt = 0.1f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#efe3cc", cloudCov = 1f, cloudAlt = 80f, cloudDens = 0.85f,
            cloudSize = 5f, cloudDist = 1.5f, cloudBump = 0.015f, cloudBand = 0.8f,
            fogColor = "#ded665", fogDens = 0.25f, fogSH = 3f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 76.5f, sunDist = 0.723f, sunSize = 1f,
            exposure = 0.2f,
        ),
        "Jupiter" to buildPreset(
            planetRadius = 69911f, oblateness = 0.06487f,
            planetColor = "#d39c7e", atmosphereThickness = 400f,
            density = 0.05f,
            rayleighSH = 27f, rayleighR = 0.2f, rayleighG = 0.5f, rayleighB = 0.9f,
            mieSH = 5f, mieR = 4.6f, mieG = 4.6f, mieB = 7.3f,
            mieAbsR = 85f, mieAbsG = 71f, mieAbsB = 72.5f,
            mieG1 = 0.85f, mieG2 = -0.25f, mieBlend = 0.28f, mieDirt = 0.8f,
            ozR = 3.9f, ozG = 4.1f, ozB = 9.65f, ozCenter = 10f, ozWidth = 11f,
            cloudColor = "#ffffff", cloudCov = 0f, cloudAlt = 200f, cloudDens = 0f,
            cloudSize = 1.5f, cloudDist = 2.5f, cloudBump = 0f, cloudBand = 0f,
            fogColor = "#b59371", fogDens = 0f, fogSH = 50f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 1.5f, sunDist = 5.2f, sunSize = 1f,
            exposure = 17f,
        ),
        "Saturn" to buildPreset(
            planetRadius = 58232f, oblateness = 0.09796f,
            planetColor = "#c9aa6e", atmosphereThickness = 350f,
            density = 0.04f,
            rayleighSH = 59.5f, rayleighR = 0.15f, rayleighG = 0.35f, rayleighB = 0.7f,
            mieSH = 8f, mieR = 3.5f, mieG = 3.5f, mieB = 5.5f,
            mieAbsR = 60f, mieAbsG = 50f, mieAbsB = 55f,
            mieG1 = 0.82f, mieG2 = -0.2f, mieBlend = 0.25f, mieDirt = 0.6f,
            ozR = 2f, ozG = 2.5f, ozB = 6f, ozCenter = 12f, ozWidth = 14f,
            cloudColor = "#ffffff", cloudCov = 0f, cloudAlt = 150f, cloudDens = 0f,
            cloudSize = 1.5f, cloudDist = 2f, cloudBump = 0f, cloudBand = 0f,
            fogColor = "#c9a060", fogDens = 0f, fogSH = 40f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 0.37f, sunDist = 9.58f, sunSize = 1f,
            exposure = 68f,
            rings = RingPreset(
                inner = 1.24f, outer = 2.27f, opacity = 0.85f,
                colors = listOf("#c8b898", "#a89070", "#d4c8a8", "#8a7860", "#e0d0b0"),
                gapCount = 3, dustiness = 0.15f, seed = 0.42f,
            ),
        ),
        "Titan" to buildPreset(
            planetRadius = 2574f, oblateness = 0f,
            planetColor = "#5c4523", atmosphereThickness = 1000f,
            density = 4.44f,
            rayleighSH = 36f, rayleighR = 0.1f, rayleighG = 0.2f, rayleighB = 0.25f,
            mieSH = 10.5f, mieR = 26.5f, mieG = 20f, mieB = 3f,
            mieAbsR = 7.3f, mieAbsG = 6f, mieAbsB = 0f,
            mieG1 = 0.75f, mieG2 = -0.2f, mieBlend = 0.15f, mieDirt = 0.5f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#d9aa41", cloudCov = 1f, cloudAlt = 45f, cloudDens = 0.7f,
            cloudSize = 5f, cloudDist = 0.5f, cloudBump = 0.01f, cloudBand = 0.2f,
            fogColor = "#dc8025", fogDens = 0.2f, fogSH = 12f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 0.44f, sunDist = 9.58f, sunSize = 1f,
            exposure = 25f,
        ),
        "Neptune" to buildPreset(
            planetRadius = 24622f, oblateness = 0.01708f,
            planetColor = "#476fd2", atmosphereThickness = 400f,
            density = 0.1f,
            rayleighSH = 20f, rayleighR = 0.9f, rayleighG = 1.5f, rayleighB = 3.5f,
            mieSH = 6.2f, mieR = 0f, mieG = 0.6f, mieB = 7.3f,
            mieAbsR = 0.5f, mieAbsG = 0.2f, mieAbsB = 0.1f,
            mieG1 = 0.55f, mieG2 = -0.25f, mieBlend = 0.1f, mieDirt = 0.2f,
            ozR = 2f, ozG = 0.6f, ozB = 7.6f, ozCenter = 15f, ozWidth = 16f,
            cloudColor = "#d6e8ff", cloudCov = 0f, cloudAlt = 80f, cloudDens = 0.15f,
            cloudSize = 3f, cloudDist = 1.5f, cloudBump = 0.05f, cloudBand = 2f,
            fogColor = "#123963", fogDens = 0f, fogSH = 40f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 0.04f, sunDist = 30.1f, sunSize = 1f,
            exposure = 620f,
        ),
        "Uranus" to buildPreset(
            planetRadius = 25362f, oblateness = 0.02293f,
            planetColor = "#9fc4d8", atmosphereThickness = 350f,
            density = 0.06f,
            rayleighSH = 27f, rayleighR = 0.6f, rayleighG = 1.1f, rayleighB = 2.5f,
            mieSH = 7f, mieR = 0.3f, mieG = 0.5f, mieB = 5.5f,
            mieAbsR = 0.3f, mieAbsG = 0.15f, mieAbsB = 0.05f,
            mieG1 = 0.60f, mieG2 = -0.2f, mieBlend = 0.1f, mieDirt = 0.15f,
            ozR = 1.5f, ozG = 0.4f, ozB = 6f, ozCenter = 18f, ozWidth = 20f,
            cloudColor = "#d6f0ff", cloudCov = 0f, cloudAlt = 100f, cloudDens = 0f,
            cloudSize = 2f, cloudDist = 1f, cloudBump = 0f, cloudBand = 0f,
            fogColor = "#9fc4d8", fogDens = 0f, fogSH = 35f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 0.015f, sunDist = 19.2f, sunSize = 1f,
            exposure = 1700f,
            rings = RingPreset(
                inner = 1.64f, outer = 2.0f, opacity = 0.4f,
                colors = listOf("#404040", "#505050", "#3a3a3a"),
                gapCount = 1, dustiness = 0.75f, seed = 0.71f,
            ),
        ),
        "Triton" to buildPreset(
            planetRadius = 1353f, oblateness = 0f,
            planetColor = "#95a1a8", atmosphereThickness = 150f,
            density = 0.000014f,
            rayleighSH = 20f, rayleighR = 8f, rayleighG = 16f, rayleighB = 28f,
            mieSH = 1f, mieR = 3f, mieG = 3f, mieB = 3f,
            mieAbsR = 0.2f, mieAbsG = 0.2f, mieAbsB = 0.2f,
            mieG1 = 0.70f, mieG2 = -0.1f, mieBlend = 0.05f, mieDirt = 0.1f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#daefff", cloudCov = 0.4f, cloudAlt = 4f, cloudDens = 0.02f,
            cloudSize = 1.3f, cloudDist = 0.1f, cloudBump = 0.15f, cloudBand = 0f,
            fogColor = "#ffffff", fogDens = 0f, fogSH = 1f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 0.04f, sunDist = 30.07f, sunSize = 1f,
            exposure = 620f,
        ),
        "TRAPPIST-1 e" to buildPreset(
            planetRadius = 5860f, oblateness = 0f,
            planetColor = "#371e06", atmosphereThickness = 100f,
            density = 1.15f,
            rayleighSH = 9f, rayleighR = 4.5f, rayleighG = 8f, rayleighB = 18.5f,
            mieSH = 1.2f, mieR = 15.5f, mieG = 9f, mieB = 0.6f,
            mieAbsR = 0.4f, mieAbsG = 0.5f, mieAbsB = 0.7f,
            mieG1 = 0.6f, mieG2 = -0.15f, mieBlend = 0.1f, mieDirt = 0.4f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#ffffff", cloudCov = 0.57f, cloudAlt = 4f, cloudDens = 0.5f,
            cloudSize = 3.3f, cloudDist = 0.8f, cloudBump = 0.11f, cloudBand = 0.52f,
            fogColor = "#e0c1b1", fogDens = 0.002f, fogSH = 3f, fogPatch = 0.45f,
            sunColor = "#ff8943", sunInt = 26f, sunDist = 0.029f, sunSize = 0.12f,
            exposure = 1f,
        ),
        // ── Gas giant texture presets ──
        "Jupiter (Textured)" to buildPreset(
            planetRadius = 69911f, oblateness = 0.06487f,
            planetColor = "#d39c7e", atmosphereThickness = 400f,
            density = 0.05f,
            rayleighSH = 27f, rayleighR = 0.2f, rayleighG = 0.5f, rayleighB = 0.9f,
            mieSH = 5f, mieR = 4.6f, mieG = 4.6f, mieB = 7.3f,
            mieAbsR = 85f, mieAbsG = 71f, mieAbsB = 72.5f,
            mieG1 = 0.85f, mieG2 = -0.25f, mieBlend = 0.28f, mieDirt = 0.8f,
            ozR = 3.9f, ozG = 4.1f, ozB = 9.65f, ozCenter = 10f, ozWidth = 11f,
            cloudColor = "#ffffff", cloudCov = 0f, cloudAlt = 200f, cloudDens = 0f,
            cloudSize = 1.5f, cloudDist = 2.5f, cloudBump = 0f, cloudBand = 0f,
            fogColor = "#b59371", fogDens = 0f, fogSH = 50f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 1.5f, sunDist = 5.2f, sunSize = 1f,
            exposure = 17f,
        ).copy(
            seed = 12345L,
            gasGiantBake = GasGiantBakeData(
                // Jupiter PoC default colors
                color1R = 0.878f, color1G = 0.784f, color1B = 0.690f, // #e0c8b0
                color2R = 0.690f, color2G = 0.784f, color2B = 0.878f, // #b0c8e0
                color3R = 0.565f, color3G = 0.522f, color3B = 0.502f, // #908580
                color4R = 0.439f, color4G = 0.541f, color4B = 0.600f, // #708a99
                color5R = 0.651f, color5G = 0.294f, color5B = 0.196f, // #a64b32
                bands = 6f,
                bandBreakup = 0.3f,
                bandSoftness = 0.2f,
                contrast = 1.0f,
                microDetails = 0.7f,
                striations = 0.35f,
                turbulence = 0.8f,
                stormIntensity = 0.7f,
                poleSize = 0.4f,
                noiseScale = 4.0f,
                permSeed = 12345,
                macroStorms = listOf(
                    StormData(-0.32f, -0.22f, 0.42f, 0.25f, 2.0f),
                ),
                microStorms = listOf(
                    MicroStormData(0.3f, 0.35f, 0.4f, 0.025f, 1.5f, 1),
                    MicroStormData(0.1f, 0.36f, 0.5f, 0.020f, 1.5f, 1),
                    MicroStormData(-0.2f, 0.34f, 0.6f, 0.022f, 1.5f, 1),
                    MicroStormData(-0.4f, 0.35f, 0.3f, 0.018f, 1.5f, 1),
                ),
            ),
        ),
        "Neptune (Textured)" to buildPreset(
            planetRadius = 24622f, oblateness = 0.01708f,
            planetColor = "#476fd2", atmosphereThickness = 400f,
            density = 0.1f,
            rayleighSH = 20f, rayleighR = 0.9f, rayleighG = 1.5f, rayleighB = 3.5f,
            mieSH = 6.2f, mieR = 0f, mieG = 0.6f, mieB = 7.3f,
            mieAbsR = 0.5f, mieAbsG = 0.2f, mieAbsB = 0.1f,
            mieG1 = 0.55f, mieG2 = -0.25f, mieBlend = 0.1f, mieDirt = 0.2f,
            ozR = 2f, ozG = 0.6f, ozB = 7.6f, ozCenter = 15f, ozWidth = 16f,
            cloudColor = "#d6e8ff", cloudCov = 0f, cloudAlt = 80f, cloudDens = 0.15f,
            cloudSize = 3f, cloudDist = 1.5f, cloudBump = 0.05f, cloudBand = 2f,
            fogColor = "#123963", fogDens = 0f, fogSH = 40f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 0.04f, sunDist = 30.1f, sunSize = 1f,
            exposure = 620f,
        ).copy(
            seed = 67890L,
            gasGiantBake = GasGiantBakeData(
                // Neptune-like: soft bands, blue/cyan palette
                color1R = 0.35f, color1G = 0.55f, color1B = 0.75f,
                color2R = 0.30f, color2G = 0.48f, color2B = 0.68f,
                color3R = 0.20f, color3G = 0.35f, color3B = 0.55f,
                color4R = 0.25f, color4G = 0.40f, color4B = 0.58f,
                color5R = 0.40f, color5G = 0.55f, color5B = 0.70f,
                bands = 4f,
                bandBreakup = 0.15f,
                bandSoftness = 0.6f,
                contrast = 0.2f,
                microDetails = 0.1f,
                striations = 0.08f,
                turbulence = 0.15f,
                stormIntensity = 0.1f,
                poleSize = 0.15f,
                noiseScale = 3.0f,
                permSeed = 67890,
                macroStorms = emptyList(),
                microStorms = emptyList(),
            ),
        ),

        // ── Terrestrial texture presets — palettes derived from surface composition at call time ──
        "Earth (Textured)" to buildPreset(
            planetRadius = 6371f, oblateness = 0.00335f,
            planetColor = "#142046", atmosphereThickness = 100f,
            density = 1f,
            rayleighSH = 8.5f, rayleighR = 5.5f, rayleighG = 13f, rayleighB = 33f,
            mieSH = 1.2f, mieR = 1.5f, mieG = 1.5f, mieB = 1.5f,
            mieAbsR = 0.2f, mieAbsG = 0.3f, mieAbsB = 0.4f,
            mieG1 = 0.76f, mieG2 = -0.1f, mieBlend = 0.1f, mieDirt = 0.35f,
            ozR = 0.9f, ozG = 1.3f, ozB = 0.1f, ozCenter = 25f, ozWidth = 11f,
            cloudColor = "#ffffff", cloudCov = 0.55f, cloudAlt = 4f, cloudDens = 0.85f,
            cloudSize = 2f, cloudDist = 0.6f, cloudBump = 0.05f, cloudBand = 0.25f,
            fogColor = "#c5d3de", fogDens = 0.02f, fogSH = 3f, fogPatch = 0.95f,
            sunColor = "#fffaf2", sunInt = 40f, sunDist = 1f, sunSize = 1f,
            exposure = 0.4f,
        ).copy(
            seed = 11111L,
            bumpStrength = 0.35f,
            waterSpecularStrength = 0.85f,
            terrestrialBake = buildTerrestrialBake(
                surface = SurfaceComposition(silicates = 0.65f, iron = 0.08f, water = 0.22f, carbon = 0.02f),
                temp = 288f, hasO2 = true, isLava = false, seed = 11111L,
                seaLevel = 0.45f, polarCap = 0.18f, roughness = 0.55f,
                planetRadiusKm = 6371f,
                craterDensityField = 0.05f, permSeed = 11111,
                waterSpecular = 0.85f,
                craters = listOf(
                    CraterData(0.2f, 0.3f, 0.9f, 0.04f, 0.4f, 0.7f),
                    CraterData(-0.5f, -0.2f, 0.8f, 0.03f, 0.3f, 0.8f),
                ),
            ),
        ),
        "Mars (Textured)" to buildPreset(
            planetRadius = 3389f, oblateness = 0.00589f,
            planetColor = "#6e3820", atmosphereThickness = 150f,
            density = 0.006f,
            rayleighSH = 11.1f, rayleighR = 10.5f, rayleighG = 9f, rayleighB = 6.5f,
            mieSH = 2.5f, mieR = 8f, mieG = 10f, mieB = 12.5f,
            mieAbsR = 34.2f, mieAbsG = 0.8f, mieAbsB = 0.4f,
            mieG1 = 0.85f, mieG2 = -0.2f, mieBlend = 0.2f, mieDirt = 0.75f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#cabfb1", cloudCov = 0.05f, cloudAlt = 35f, cloudDens = 0.01f,
            cloudSize = 1.8f, cloudDist = 0.1f, cloudBump = 0.15f, cloudBand = 0f,
            fogColor = "#87674b", fogDens = 0.02f, fogSH = 5f, fogPatch = 0.9f,
            sunColor = "#fffaf2", sunInt = 17.2f, sunDist = 1.524f, sunSize = 1f,
            exposure = 1.5f,
        ).copy(
            seed = 22222L,
            bumpStrength = 0.55f,
            terrestrialBake = buildTerrestrialBake(
                // High iron (oxidised) + low silicates → rust-red terrain
                surface = SurfaceComposition(silicates = 0.52f, iron = 0.44f, water = 0.02f, carbon = 0.01f),
                temp = 210f, hasO2 = true, isLava = false, seed = 22222L,
                seaLevel = 0f, polarCap = 0.12f, roughness = 0.65f,
                planetRadiusKm = 3389f,
                craterDensityField = 0.45f, permSeed = 22222,
                craters = listOf(
                    CraterData(0.0f, 0.0f, 1.0f, 0.10f, 0.7f, 0.25f),
                    CraterData(0.6f, 0.1f, 0.8f, 0.06f, 0.6f, 0.30f),
                    CraterData(-0.3f, 0.4f, 0.9f, 0.05f, 0.5f, 0.40f),
                    CraterData(0.1f, -0.5f, 0.9f, 0.04f, 0.6f, 0.35f),
                    CraterData(-0.7f, -0.2f, 0.7f, 0.035f, 0.5f, 0.45f),
                ),
            ),
        ),
        "Ocean World" to buildPreset(
            planetRadius = 7500f, oblateness = 0.002f,
            planetColor = "#0a2050", atmosphereThickness = 120f,
            density = 1.2f,
            rayleighSH = 9f, rayleighR = 4f, rayleighG = 11f, rayleighB = 30f,
            mieSH = 1.5f, mieR = 0.8f, mieG = 0.8f, mieB = 0.8f,
            mieAbsR = 0.1f, mieAbsG = 0.15f, mieAbsB = 0.2f,
            mieG1 = 0.76f, mieG2 = -0.1f, mieBlend = 0.1f, mieDirt = 0.1f,
            ozR = 1.2f, ozG = 1.5f, ozB = 0.15f, ozCenter = 22f, ozWidth = 12f,
            cloudColor = "#e8f0f8", cloudCov = 0.80f, cloudAlt = 3f, cloudDens = 0.90f,
            cloudSize = 2.5f, cloudDist = 0.8f, cloudBump = 0.04f, cloudBand = 0.1f,
            fogColor = "#a0c8e0", fogDens = 0.015f, fogSH = 4f, fogPatch = 0.80f,
            sunColor = "#fffaf2", sunInt = 40f, sunDist = 1f, sunSize = 1f,
            exposure = 0.4f,
        ).copy(
            seed = 33333L,
            bumpStrength = 0.25f,
            waterSpecularStrength = 0.90f,
            terrestrialBake = buildTerrestrialBake(
                // Near-global ocean: high water fraction, rocky island peaks
                surface = SurfaceComposition(silicates = 0.68f, iron = 0.05f, water = 0.20f, carbon = 0.01f),
                temp = 295f, hasO2 = true, isLava = false, seed = 33333L,
                seaLevel = 0.75f, polarCap = 0.10f, roughness = 0.40f,
                planetRadiusKm = 7500f,
                craterDensityField = 0.02f, permSeed = 33333,
                waterSpecular = 0.90f,
            ),
        ),
        "Lava World" to buildPreset(
            planetRadius = 12000f, oblateness = 0.001f,
            planetColor = "#3a1800", atmosphereThickness = 600f,
            density = 80f,
            rayleighSH = 20f, rayleighR = 3f, rayleighG = 3.5f, rayleighB = 7f,
            mieSH = 10f, mieR = 0.05f, mieG = 0.04f, mieB = 0f,
            mieAbsR = 0f, mieAbsG = 0.3f, mieAbsB = 2f,
            mieG1 = 0.85f, mieG2 = -0.1f, mieBlend = 0.05f, mieDirt = 0.15f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#e0c080", cloudCov = 1f, cloudAlt = 120f, cloudDens = 0.80f,
            cloudSize = 5f, cloudDist = 1.5f, cloudBump = 0.02f, cloudBand = 0.7f,
            fogColor = "#d06020", fogDens = 0.4f, fogSH = 5f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 76.5f, sunDist = 0.5f, sunSize = 1f,
            exposure = 0.1f,
        ).copy(
            seed = 44444L,
            bumpStrength = 0.15f,
            lavaEmission = 1f,
            terrestrialBake = buildTerrestrialBake(
                // Dark basalt + iron, extreme temp → lava fills basins via sea-level mechanism
                surface = SurfaceComposition(silicates = 0.60f, iron = 0.30f, carbon = 0.05f),
                temp = 1800f, hasO2 = false, isLava = true, seed = 44444L,
                seaLevel = 0.85f, polarCap = 0f, roughness = 0.50f,
                planetRadiusKm = 12000f,
                craterDensityField = 0f, permSeed = 44444,
            ),
        ),
        "Ice World" to buildPreset(
            planetRadius = 5000f, oblateness = 0.001f,
            planetColor = "#d0e4f0", atmosphereThickness = 60f,
            density = 0.01f,
            rayleighSH = 8f, rayleighR = 5f, rayleighG = 12f, rayleighB = 28f,
            mieSH = 1.5f, mieR = 0.5f, mieG = 0.5f, mieB = 0.5f,
            mieAbsR = 0.1f, mieAbsG = 0.1f, mieAbsB = 0.15f,
            mieG1 = 0.70f, mieG2 = -0.1f, mieBlend = 0.1f, mieDirt = 0.1f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 20f, ozWidth = 10f,
            cloudColor = "#f0f4f8", cloudCov = 0.30f, cloudAlt = 3f, cloudDens = 0.50f,
            cloudSize = 2f, cloudDist = 0.3f, cloudBump = 0.06f, cloudBand = 0.1f,
            fogColor = "#c8d8e8", fogDens = 0.01f, fogSH = 4f, fogPatch = 0.6f,
            sunColor = "#e8eeff", sunInt = 3f, sunDist = 5f, sunSize = 1f,
            exposure = 3f,
        ).copy(
            seed = 55555L,
            bumpStrength = 0.30f,
            terrestrialBake = buildTerrestrialBake(
                // Frigid world: cold silicates + water ice + trace nitrogen
                surface = SurfaceComposition(silicates = 0.55f, water = 0.35f, iron = 0.05f, nitrogen = 0.03f),
                temp = 80f, hasO2 = false, isLava = false, seed = 55555L,
                seaLevel = 0.55f, polarCap = 0.55f, roughness = 0.45f,
                planetRadiusKm = 5000f,
                craterDensityField = 0.25f, permSeed = 55555,
                craters = listOf(
                    CraterData(0.4f, 0.2f, 0.9f, 0.06f, 0.55f, 0.50f),
                    CraterData(-0.3f, -0.3f, 0.9f, 0.04f, 0.50f, 0.55f),
                    CraterData(0.0f, 0.5f, 0.9f, 0.03f, 0.45f, 0.60f),
                ),
            ),
        ),
        "Mercury (Textured)" to buildPreset(
            planetRadius = 2440f, oblateness = 0.0009f,
            planetColor = "#888080", atmosphereThickness = 0f,
            density = 0f,
            rayleighSH = 8.5f, rayleighR = 0f, rayleighG = 0f, rayleighB = 0f,
            mieSH = 1.2f, mieR = 0f, mieG = 0f, mieB = 0f,
            mieAbsR = 0f, mieAbsG = 0f, mieAbsB = 0f,
            mieG1 = 0.76f, mieG2 = -0.1f, mieBlend = 0.1f, mieDirt = 0f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#ffffff", cloudCov = 0f, cloudAlt = 0f, cloudDens = 0f,
            cloudSize = 1f, cloudDist = 0f, cloudBump = 0f, cloudBand = 0f,
            fogColor = "#888880", fogDens = 0f, fogSH = 3f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 76.5f, sunDist = 0.387f, sunSize = 1f,
            exposure = 0.15f,
        ).copy(
            seed = 66666L,
            bumpStrength = 0.70f,
            terrestrialBake = buildTerrestrialBake(
                // Iron-dominated airless body: metallic grey, heavily cratered
                surface = SurfaceComposition(iron = 0.55f, silicates = 0.42f, carbon = 0.02f),
                temp = 440f, hasO2 = false, isLava = false, seed = 66666L,
                seaLevel = 0f, polarCap = 0.06f, roughness = 0.70f,
                planetRadiusKm = 2440f,
                craterDensityField = 0.80f, permSeed = 66666,
                craters = listOf(
                    CraterData(0.0f, 0.1f, 1.0f, 0.12f, 0.75f, 0.10f),
                    CraterData(0.5f, 0.0f, 0.9f, 0.08f, 0.80f, 0.08f),
                    CraterData(-0.4f, 0.3f, 0.9f, 0.07f, 0.70f, 0.15f),
                    CraterData(0.2f, -0.4f, 0.9f, 0.055f, 0.85f, 0.05f),
                    CraterData(-0.6f, -0.1f, 0.8f, 0.045f, 0.80f, 0.10f),
                    CraterData(0.3f, 0.5f, 0.8f, 0.040f, 0.75f, 0.12f),
                    CraterData(-0.2f, -0.6f, 0.8f, 0.035f, 0.72f, 0.15f),
                    CraterData(0.7f, 0.2f, 0.7f, 0.030f, 0.78f, 0.08f),
                ),
            ),
        ),
        "Io" to buildPreset(
            // Tidally-heated silicate moon: sulfur-covered surface with extreme
            // volcanism (400+ active hotspots on real Io). Thin SO₂ exosphere
            // from sulfur frost sublimation, essentially airless at visible scales.
            planetRadius = 1821f, oblateness = 0.0013f,
            planetColor = "#c4a438", atmosphereThickness = 0f,
            density = 0f,
            rayleighSH = 8.5f, rayleighR = 0f, rayleighG = 0f, rayleighB = 0f,
            mieSH = 1.2f, mieR = 0f, mieG = 0f, mieB = 0f,
            mieAbsR = 0f, mieAbsG = 0f, mieAbsB = 0f,
            mieG1 = 0.76f, mieG2 = -0.1f, mieBlend = 0.1f, mieDirt = 0f,
            ozR = 0f, ozG = 0f, ozB = 0f, ozCenter = 25f, ozWidth = 15f,
            cloudColor = "#ffffff", cloudCov = 0f, cloudAlt = 0f, cloudDens = 0f,
            cloudSize = 1f, cloudDist = 0f, cloudBump = 0f, cloudBand = 0f,
            fogColor = "#c4a438", fogDens = 0f, fogSH = 3f, fogPatch = 0f,
            sunColor = "#fffaf2", sunInt = 1.5f, sunDist = 5.2f, sunSize = 1f,
            exposure = 14f,
        ).copy(
            seed = 77777L,
            bumpStrength = 0.55f,
            lavaEmission = 1f,  // hotspots need to glow
            terrestrialBake = buildTerrestrialBake(
                // Silicate-dominant (Terra bulk) with high sulfur surface coating,
                // trace iron. No water — stripped long ago by radiation + heat.
                surface = SurfaceComposition(silicates = 0.55f, sulfur = 0.38f, iron = 0.05f, carbon = 0.02f),
                temp = 140f, hasO2 = false, isLava = false, seed = 77777L,
                seaLevel = 0f, polarCap = 0.08f, roughness = 0.55f,
                planetRadiusKm = 1821f,
                // Io's surface is young (constant resurfacing by volcanism) — almost no
                // impact craters survive. The surface is all calderas and lava flows.
                craterDensityField = 0f, permSeed = 77777,
                volcanism = 0.95f,  // extreme: triggers hotspot painting
            ),
        ),
    )
}

/**
 * Builds [TerrestrialBakeData] from surface composition fractions.
 * Palette (t0–t5) is derived from [buildTerrainColors]; water/polar colors derived
 * via the same rules as [buildTerrestrialBakeData] in PlanetGlobeParams.
 * noiseScale is computed from planetRadiusKm — not hardcoded.
 */
private fun buildTerrestrialBake(
    surface: SurfaceComposition,
    temp: Float,
    hasO2: Boolean,
    isLava: Boolean,
    seed: Long,
    seaLevel: Float,
    polarCap: Float,
    roughness: Float,
    planetRadiusKm: Float,
    tidallyLocked: Boolean = false,
    craterDensityField: Float,
    permSeed: Int,
    craters: List<CraterData> = emptyList(),
    volcanism: Float = 0f,
    waterSpecular: Float = 0f,
    waterIsIce: Boolean = false,
): TerrestrialBakeData {
    val terrain = buildTerrainColors(surface, temp, hasO2, seed)

    val waterColor: Long = when {
        isLava -> 0xFFFF3A08L
        surface.methane > 0.25f && temp < 150f -> 0xFF2A1A0AL
        // Salt 4 matches the polar cap path below so the settings preview
        // also gets a unified frozen-sea + cap colour, mirroring the main
        // globe's `buildTerrestrialBakeData` behaviour.
        temp < 273f -> pickColor(ColorPalettes.WATER_ICE, seed, 4)
        temp < 373f -> pickColor(ColorPalettes.WATER_LIQUID, seed, 2)
        else -> 0xFFF0F0F0L
    }
    val (waterR, waterG, waterB) = waterColor.toGlRgb()

    val polarColor: Long = when {
        temp > 350f -> pickColor(ColorPalettes.SILICATE_LIGHT, seed, 4)
        surface.nitrogen > 0.05f && temp < 100f -> pickColor(ColorPalettes.NITROGEN_ICE, seed, 4)
        surface.sulfur > 0.10f && temp < 200f -> pickColor(ColorPalettes.SULFUR_FROST, seed, 4)
        else -> pickColor(ColorPalettes.WATER_ICE, seed, 4)
    }
    val (polarR, polarG, polarB) = polarColor.toGlRgb()

    // Noise scale derived from planet radius — same formula as buildTerrestrialBakeData
    val pocM = (2.8f - 1.2f) / (10204f - 3389f)
    val pocC = 1.2f - pocM * 3389f
    val noiseScale = (pocM * planetRadiusKm + pocC).coerceIn(0.8f, 4.0f)

    // For lava presets, force a mid sea-level so basins fill with lava like oceans.
    val effectiveSeaLevel = if (isLava) seaLevel.coerceAtLeast(0.4f) else seaLevel

    return TerrestrialBakeData(
        t0R = terrain[0],  t0G = terrain[1],  t0B = terrain[2],
        t1R = terrain[3],  t1G = terrain[4],  t1B = terrain[5],
        t2R = terrain[6],  t2G = terrain[7],  t2B = terrain[8],
        t3R = terrain[9],  t3G = terrain[10], t3B = terrain[11],
        t4R = terrain[12], t4G = terrain[13], t4B = terrain[14],
        t5R = terrain[15], t5G = terrain[16], t5B = terrain[17],
        colorWaterR = waterR, colorWaterG = waterG, colorWaterB = waterB,
        waterIsIce = waterIsIce,
        colorPolarR = polarR, colorPolarG = polarG, colorPolarB = polarB,
        waterFraction = surface.water,
        carbonFraction = surface.carbon,
        tholinFraction = surface.tholins,
        seaLevel = effectiveSeaLevel,
        polarCap = if (temp > 350f) 0f else polarCap,
        roughness = roughness,
        noiseScale = noiseScale,
        tidallyLocked = tidallyLocked,
        subSolarX = 0.866f, subSolarY = 0.5f, subSolarZ = 0f,
        craterDensityField = craterDensityField,
        volcanism = volcanism,
        moltenSurface = temp > 900f,
        waterSpecular = waterSpecular,
        permSeed = permSeed,
        craters = craters,
    )
}

private fun buildPreset(
    planetRadius: Float, oblateness: Float,
    planetColor: String, atmosphereThickness: Float,
    density: Float,
    rayleighSH: Float, rayleighR: Float, rayleighG: Float, rayleighB: Float,
    mieSH: Float, mieR: Float, mieG: Float, mieB: Float,
    mieAbsR: Float, mieAbsG: Float, mieAbsB: Float,
    mieG1: Float, mieG2: Float, mieBlend: Float, mieDirt: Float,
    ozR: Float, ozG: Float, ozB: Float, ozCenter: Float, ozWidth: Float,
    cloudColor: String, cloudCov: Float, cloudAlt: Float, cloudDens: Float,
    cloudSize: Float, cloudDist: Float, cloudBump: Float, cloudBand: Float,
    fogColor: String, fogDens: Float, fogSH: Float, fogPatch: Float,
    sunColor: String, sunInt: Float, sunDist: Float, sunSize: Float,
    exposure: Float,
    // Optional ring parameters
    rings: RingPreset? = null,
): PlanetGlobeParams {
    val (pcR, pcG, pcB) = hexToRgb(planetColor)
    val (ccR, ccG, ccB) = hexToRgb(cloudColor)
    val (fcR, fcG, fcB) = hexToRgb(fogColor)
    val (scR, scG, scB) = hexToRgb(sunColor)
    val scale = 1e-3f * density
    return PlanetGlobeParams(
        isVisible = true,
        planetRadiusKm = planetRadius,
        oblateness = oblateness,
        planetColorR = pcR, planetColorG = pcG, planetColorB = pcB,
        atmosphereThicknessKm = atmosphereThickness,
        rayleighR = rayleighR * scale, rayleighG = rayleighG * scale, rayleighB = rayleighB * scale,
        rayleighScaleHeightKm = rayleighSH,
        mieR = mieR * scale, mieG = mieG * scale, mieB = mieB * scale,
        mieAbsorptionR = mieAbsR * scale, mieAbsorptionG = mieAbsG * scale, mieAbsorptionB = mieAbsB * scale,
        mieScaleHeightKm = mieSH,
        miePhaseG = mieG1, miePhaseG2 = mieG2, miePhaseBlend = mieBlend, mieDirtiness = mieDirt,
        ozoneR = ozR * scale, ozoneG = ozG * scale, ozoneB = ozB * scale,
        ozoneCenterKm = ozCenter, ozoneWidthKm = ozWidth,
        cloudColorR = ccR, cloudColorG = ccG, cloudColorB = ccB,
        cloudCoverage = cloudCov, cloudDensity = cloudDens, cloudAltitudeKm = cloudAlt,
        cloudSize = cloudSize, cloudDistortion = cloudDist, cloudBumpiness = cloudBump, cloudBanding = cloudBand,
        fogColorR = fcR, fogColorG = fcG, fogColorB = fcB,
        fogDensity = fogDens, fogScaleHeightKm = fogSH, fogPatchiness = fogPatch,
        sunColorR = scR, sunColorG = scG, sunColorB = scB,
        sunIntensity = sunInt, sunDistanceAU = sunDist, sunSize = sunSize,
        cameraExposure = exposure,
        time = 42f, // static — deterministic cloud config
        cloudDriftSpeed = 0.03f, // ~3.5 min per revolution (Earth-like)
        hasRings = rings != null,
        ringInner = rings?.inner ?: 0f,
        ringOuter = rings?.outer ?: 0f,
        ringOpacity = rings?.opacity ?: 0f,
        ringColors = rings?.colors?.map { hexToRgb(it) } ?: emptyList(),
        ringGapCount = rings?.gapCount ?: 0,
        ringDustiness = rings?.dustiness ?: 0f,
        ringSeed = rings?.seed ?: 0f,
    )
}

private data class RingPreset(
    val inner: Float,
    val outer: Float,
    val opacity: Float,
    val colors: List<String>,
    val gapCount: Int,
    val dustiness: Float,
    val seed: Float,
)
