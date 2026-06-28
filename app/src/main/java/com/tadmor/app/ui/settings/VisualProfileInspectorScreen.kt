package com.tadmor.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tadmor.app.ui.components.ExoSearchBar
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.domain.classification.visual.AtmosphereOptics
import com.tadmor.domain.classification.visual.AtmosphericComposition
import com.tadmor.domain.classification.visual.CraterProfile
import com.tadmor.domain.classification.visual.GasGiantProfile
import com.tadmor.domain.classification.visual.RingProfile
import com.tadmor.domain.classification.visual.SurfaceComposition
import com.tadmor.domain.classification.visual.VisualProfile
import com.tadmor.domain.model.Planet

@Composable
fun VisualProfileInspectorScreen(
    onBack: () -> Unit,
    viewModel: VisualProfileInspectorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Header
        Column(
            modifier = Modifier.padding(horizontal = spacing.xxxl, vertical = spacing.lg),
        ) {
            // Back button
            Row(
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {
                        if (state.selectedPlanet != null) viewModel.clearSelection()
                        else onBack()
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .drawBehind {
                            val sw = 1.2.dp.toPx()
                            val midY = size.height / 2f
                            val tipX = size.width * 0.2f
                            drawLine(
                                colors.textTertiary, Offset(tipX, midY),
                                Offset(size.width * 0.8f, size.height * 0.15f),
                                sw, cap = StrokeCap.Round,
                            )
                            drawLine(
                                colors.textTertiary, Offset(tipX, midY),
                                Offset(size.width * 0.8f, size.height * 0.85f),
                                sw, cap = StrokeCap.Round,
                            )
                        },
                )
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = if (state.selectedPlanet != null) "Back to search" else "Settings",
                    style = type.bodyLarge.copy(color = colors.textTertiary),
                )
            }
            Spacer(Modifier.height(spacing.md))
            BasicText(
                text = "Visual Profile Inspector",
                style = type.displayMedium.copy(color = colors.textPrimary),
            )
        }

        if (state.selectedPlanet != null && state.selectedProfile != null) {
            // Profile detail view
            ProfileDetailView(
                planet = state.selectedPlanet!!,
                star = state.selectedStar,
                classLabel = state.selectedClassLabel ?: "",
                profile = state.selectedProfile!!,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.xxxl),
            )
        } else {
            // Search view
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.xxxl),
            ) {
                ExoSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    placeholder = "Search planets...",
                )
                Spacer(Modifier.height(spacing.md))

                if (state.query.length < 2) {
                    BasicText(
                        text = "Type at least 2 characters to search",
                        style = type.bodyMedium.copy(color = colors.textMuted),
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                } else if (state.searchResults.isEmpty()) {
                    BasicText(
                        text = "No planets found",
                        style = type.bodyMedium.copy(color = colors.textMuted),
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                } else {
                    LazyColumn {
                        items(state.searchResults, key = { it.name }) { planet ->
                            PlanetSearchRow(
                                planet = planet,
                                onClick = { viewModel.selectPlanet(planet.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanetSearchRow(
    planet: Planet,
    onClick: () -> Unit,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = planet.name,
                style = type.bodyLarge.copy(color = colors.textPrimary),
            )
            BasicText(
                text = planet.hostname,
                style = type.labelLarge.copy(color = colors.textTertiary),
            )
        }
        // Chevron
        Box(
            modifier = Modifier
                .size(12.dp)
                .drawBehind {
                    val sw = 1.2.dp.toPx()
                    val midY = size.height / 2f
                    drawLine(
                        colors.textMuted,
                        Offset(size.width * 0.2f, size.height * 0.15f),
                        Offset(size.width * 0.8f, midY),
                        sw, cap = StrokeCap.Round,
                    )
                    drawLine(
                        colors.textMuted,
                        Offset(size.width * 0.2f, size.height * 0.85f),
                        Offset(size.width * 0.8f, midY),
                        sw, cap = StrokeCap.Round,
                    )
                },
        )
    }
}

@Composable
private fun ProfileDetailView(
    planet: Planet,
    star: com.tadmor.domain.model.Star?,
    classLabel: String,
    profile: VisualProfile,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Planet header
        BasicText(
            text = planet.name,
            style = type.displayMedium.copy(color = colors.textPrimary),
        )
        BasicText(
            text = classLabel,
            style = type.bodyLarge.copy(color = colors.accentGold),
        )
        if (star != null) {
            BasicText(
                text = "Host: ${star.hostname}" +
                    (star.spectralType?.let { " ($it)" } ?: ""),
                style = type.bodyMedium.copy(color = colors.textTertiary),
            )
        }

        Spacer(Modifier.height(spacing.xxxl))

        // Classification section
        SectionLabel("CLASSIFICATION")
        PropRow("Seed", profile.seed.toString())
        profile.bulkClass?.let { PropRow("Bulk class", it.label) }
        profile.gasGiantProfile?.let { PropRow("Gas giant type", it.type.label) }

        Spacer(Modifier.height(spacing.xxl))

        // Surface composition
        if (profile.surfaceComposition != null) {
            SectionLabel("SURFACE COMPOSITION")
            SurfaceSection(profile.surfaceComposition!!)
            Spacer(Modifier.height(spacing.xxl))
        }

        // Gas giant profile
        if (profile.gasGiantProfile != null) {
            SectionLabel("GAS GIANT BANDS")
            GasGiantSection(profile.gasGiantProfile!!)
            Spacer(Modifier.height(spacing.xxl))
        }

        // Atmosphere
        SectionLabel("ATMOSPHERE")
        AtmosphereSection(profile.atmosphere)
        Spacer(Modifier.height(spacing.xxl))

        // Optics
        if (profile.atmosphere.present) {
            SectionLabel("ATMOSPHERE OPTICS")
            OpticsSection(profile.atmosphereOptics)
            Spacer(Modifier.height(spacing.xxl))
        }

        // Physical
        SectionLabel("PHYSICAL")
        PropRow("Surface temperature", "${fmt(profile.surfaceTemperatureK)} K (${fmt(profile.surfaceTemperatureK - 273.15f)} \u00B0C)")
        PropRow("Sea level", fmt(profile.seaLevel))
        PropRow("Volcanic activity", fmt(profile.volcanicActivity))
        PropRow("Polar cap extent", fmt(profile.polarCapExtent))
        PropRow("Roughness", fmt(profile.roughness))
        PropRow("Albedo", fmt(profile.albedo))
        Spacer(Modifier.height(spacing.xxl))

        // Craters
        SectionLabel("CRATERS")
        CraterSection(profile.craterProfile)
        Spacer(Modifier.height(spacing.xxl))

        // Dynamics
        SectionLabel("DYNAMICS")
        PropRow("Tidally locked", if (profile.tidallyLocked) "Yes" else "No")
        PropRow("Rotation period", "${fmt(profile.rotationPeriodHours)} hours")
        PropRow("Oblateness", fmt(profile.oblateness))
        PropRow("Tidal elongation", fmt(profile.tidalElongation))
        PropRow("Axial tilt", "${fmt(profile.axialTilt)}\u00B0")
        Spacer(Modifier.height(spacing.xxl))

        // Rings
        if (profile.ringProfile != null) {
            SectionLabel("RINGS")
            RingSection(profile.ringProfile!!)
            Spacer(Modifier.height(spacing.xxl))
        }

        Spacer(Modifier.height(spacing.xxxxl))
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Column {
        BasicText(
            text = text,
            style = type.labelSmall.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )
        Spacer(Modifier.height(ExoTheme.spacing.sm))
    }
}

@Composable
private fun SubLabel(text: String) {
    BasicText(
        text = text,
        style = ExoTheme.type.labelLarge.copy(color = ExoTheme.colors.textSecondary),
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun PropRow(label: String, value: String) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = label,
            style = type.bodyMedium.copy(color = colors.textTertiary),
        )
        BasicText(
            text = value,
            style = type.bodyMedium.copy(color = colors.textPrimary),
        )
    }
}

@Composable
private fun ColorPropRow(label: String, argb: Long) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = type.bodyMedium.copy(color = colors.textTertiary),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = "0x${argb.toString(16).uppercase().padStart(8, '0')}",
                style = type.bodyMedium.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.width(8.dp))
            ColorSwatch(argb, large = false)
        }
    }
}

@Composable
private fun PercentBar(label: String, value: Float) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val pct = (value * 100f)

    if (value < 0.001f) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = type.bodyMedium.copy(color = colors.textTertiary),
            modifier = Modifier.width(80.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceRaised),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accentGold),
            )
        }
        BasicText(
            text = "${String.format("%.1f", pct)}%",
            style = type.labelLarge.copy(color = colors.textSecondary),
            modifier = Modifier.width(48.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun SurfaceSection(surface: SurfaceComposition) {
    PercentBar("Silicates", surface.silicates)
    PercentBar("Iron", surface.iron)
    PercentBar("Water", surface.water)
    PercentBar("Sulfur", surface.sulfur)
    PercentBar("Carbon", surface.carbon)
    PercentBar("Nitrogen", surface.nitrogen)
    PercentBar("Methane", surface.methane)
    PercentBar("Ammonia", surface.ammonia)
    PercentBar("Tholins", surface.tholins)
}

@Composable
private fun AtmosphereSection(atm: AtmosphericComposition) {
    PropRow("Present", if (atm.present) "Yes" else "No")
    if (!atm.present) return
    PropRow("Surface pressure", "${fmt(atm.surfacePressureBar)} bar")
    PercentBar("H\u2082", atm.h2)
    PercentBar("He", atm.he)
    PercentBar("N\u2082", atm.n2)
    PercentBar("O\u2082", atm.o2)
    PercentBar("CO\u2082", atm.co2)
    PercentBar("H\u2082O", atm.h2o)
    PercentBar("CH\u2084", atm.ch4)
    PercentBar("NH\u2083", atm.nh3)
    PercentBar("SO\u2082", atm.so2)
    PercentBar("H\u2082S", atm.h2s)
}

@Composable
private fun OpticsSection(optics: AtmosphereOptics) {
    // Atmosphere extent
    PropRow("Atmosphere thickness", "${fmt(optics.atmosphereThicknessKm)} km")
    PropRow("Density multiplier", "${fmt(optics.densityMultiplier)}\u00D7 Earth")

    Spacer(Modifier.height(ExoTheme.spacing.xs))
    SubLabel("Rayleigh scattering")
    PropRow("Scale height", "${fmt(optics.rayleighScaleHeightKm)} km")
    PropRow("Coefficients (R/G/B)", "${fmt(optics.rayleighR)} / ${fmt(optics.rayleighG)} / ${fmt(optics.rayleighB)}")

    Spacer(Modifier.height(ExoTheme.spacing.xs))
    SubLabel("Mie scattering (aerosols)")
    PropRow("Scale height", "${fmt(optics.mieScaleHeightKm)} km")
    PropRow("Scatter (R/G/B)", "${fmt(optics.mieR)} / ${fmt(optics.mieG)} / ${fmt(optics.mieB)}")
    PropRow("Absorption (R/G/B)", "${fmt(optics.mieAbsorptionR)} / ${fmt(optics.mieAbsorptionG)} / ${fmt(optics.mieAbsorptionB)}")
    PropRow("Phase (G/G2/blend)", "${fmt(optics.miePhaseG)} / ${fmt(optics.miePhaseG2)} / ${fmt(optics.miePhaseBlend)}")
    PropRow("Dirtiness", fmt(optics.mieDirtiness))

    if (optics.ozoneR > 0f || optics.ozoneG > 0f || optics.ozoneB > 0f) {
        Spacer(Modifier.height(ExoTheme.spacing.xs))
        SubLabel("Absorption band")
        PropRow("Coefficients (R/G/B)", "${fmt(optics.ozoneR)} / ${fmt(optics.ozoneG)} / ${fmt(optics.ozoneB)}")
        PropRow("Center / width", "${fmt(optics.ozoneCenterKm)} / ${fmt(optics.ozoneWidthKm)} km")
    }

    Spacer(Modifier.height(ExoTheme.spacing.xs))
    SubLabel("Clouds")
    PropRow("Coverage", fmt(optics.cloudCoverage))
    if (optics.cloudCoverage > 0f) {
        ColorPropRow("Color", optics.cloudColor)
        PropRow("Altitude", "${fmt(optics.cloudAltitudeKm)} km")
        PropRow("Density", fmt(optics.cloudDensity))
        PropRow("Size / distortion", "${fmt(optics.cloudSize)} / ${fmt(optics.cloudDistortion)}")
        PropRow("Bumpiness / banding", "${fmt(optics.cloudBumpiness)} / ${fmt(optics.cloudBanding)}")
    }

    if (optics.fogDensity > 0f) {
        Spacer(Modifier.height(ExoTheme.spacing.xs))
        SubLabel("Fog")
        ColorPropRow("Color", optics.fogColor)
        PropRow("Density", fmt(optics.fogDensity))
        PropRow("Scale height", "${fmt(optics.fogScaleHeightKm)} km")
        PropRow("Patchiness", fmt(optics.fogPatchiness))
    }

    Spacer(Modifier.height(ExoTheme.spacing.xs))
    SubLabel("Illumination")
    ColorPropRow("Star tint", optics.starTintColor)
    PropRow("Sun intensity", fmt(optics.sunIntensity))
    PropRow("Sun distance", "${fmt(optics.sunDistanceAU)} AU")
}

@Composable
private fun GasGiantSection(gg: GasGiantProfile) {
    PropRow("Metallicity enrichment", "${fmt(gg.metallicityEnrichment)}\u00D7")
    PropRow("Methane abundance", fmt(gg.methaneAbundance))
    Spacer(Modifier.height(ExoTheme.spacing.sm))
    BasicText(
        text = "Band colors",
        style = ExoTheme.type.bodyMedium.copy(color = ExoTheme.colors.textTertiary),
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        gg.bandColors.forEachIndexed { i, color ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ColorSwatch(color, large = true)
                BasicText(
                    text = "${i + 1}",
                    style = ExoTheme.type.labelSmall.copy(color = ExoTheme.colors.textMuted),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ColorSwatch(gg.poleColor, large = true)
            BasicText(
                text = "P",
                style = ExoTheme.type.labelSmall.copy(color = ExoTheme.colors.textMuted),
            )
        }
    }
    Spacer(Modifier.height(ExoTheme.spacing.sm))
    PropRow("Pole fraction", fmt(gg.poleFraction))
    PropRow("Storm intensity", fmt(gg.stormIntensity))
    PropRow("Banding strength", fmt(gg.bandingStrength))
    PropRow("Band breakup", fmt(gg.bandBreakup))
    PropRow("Band softness", fmt(gg.bandSoftness))
    PropRow("Micro detail", fmt(gg.microDetail))
    PropRow("Striations", fmt(gg.striations))
    PropRow("Turbulence", fmt(gg.turbulence))
    PropRow("Contrast", fmt(gg.contrast))
}

@Composable
private fun ColorSwatch(argb: Long, large: Boolean = false) {
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    val color = Color(r, g, b)
    val sz = if (large) 28.dp else 16.dp

    Box(
        modifier = Modifier
            .size(sz)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .border(1.dp, ExoTheme.colors.divider, RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun CraterSection(craters: CraterProfile) {
    PropRow("Density", fmt(craters.density))
    PropRow("Degradation", fmt(craters.degradation))
    PropRow("Size exponent", fmt(craters.sizeExponent))
    PropRow("Regional variation", fmt(craters.regionalVariation))
    PropRow("Max crater scale", fmt(craters.maxCraterScale))
}

@Composable
private fun RingSection(ring: RingProfile) {
    PropRow("Inner radius", "${fmt(ring.innerRadius)} R\u2091")
    PropRow("Outer radius", "${fmt(ring.outerRadius)} R\u2091")
    PropRow("Opacity", fmt(ring.opacity))
    PropRow("Gap count", ring.gapCount.toString())
    PropRow("Dustiness", fmt(ring.dustiness))
    PropRow("Tilt", "${fmt(ring.tiltDeg)}\u00B0")
    Spacer(Modifier.height(ExoTheme.spacing.sm))
    BasicText(
        text = "Ring colors",
        style = ExoTheme.type.bodyMedium.copy(color = ExoTheme.colors.textTertiary),
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ring.colors.forEach { color ->
            ColorSwatch(color, large = true)
        }
    }
}

private fun fmt(v: Float): String = String.format("%.4f", v)
