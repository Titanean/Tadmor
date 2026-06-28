package com.tadmor.app.ui.system

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.components.ExoSearchBar
import com.tadmor.app.ui.components.touchRipple
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.app.ui.util.AstroFormat
import com.tadmor.domain.model.ProperNames
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.effectiveSpectralType
import com.tadmor.domain.model.isPulsar
import com.tadmor.domain.model.TemperatureUnit
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.model.limitPrefix

@Composable
fun StarSearchContent(
    searchQuery: String,
    results: List<Star>,
    settings: UserSettings,
    onQueryChange: (String) -> Unit,
    onStarSelected: (String) -> Unit,
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val navPadding = LocalBottomBarHeight.current

    LazyColumn(
        // Local `.background(colors.background)` because SystemScreen's outer
        // Box was changed to black (so SurfaceView punch-holes in DETAIL/PLANET
        // pages don't expose dark-blue during slide-in transitions). The SEARCH
        // page never owned its own background — it inherited from the parent.
        modifier = modifier.background(colors.background),
        contentPadding = PaddingValues(bottom = spacing.xxxl + navPadding),
    ) {
        // Header
        item {
            Column(
                modifier = Modifier.padding(
                    start = spacing.xxxl,
                    end = spacing.xxxl,
                    top = 18.dp,
                ),
            ) {
                BasicText(
                    text = "SYSTEM",
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = "Host stars",
                    style = type.displayLarge.copy(color = colors.textPrimary),
                )
            }
        }

        // Search bar
        item {
            ExoSearchBar(
                query = searchQuery,
                onQueryChange = onQueryChange,
                placeholder = "Search...",
                isActive = isActive,
                modifier = Modifier.padding(
                    horizontal = spacing.xxxl,
                    vertical = spacing.md,
                ),
            )
        }

        // Results — placement-only animateItem so rows smoothly slide
        // to their new positions as the user types and the result set
        // changes, matching the catalog's planet-card behaviour.
        items(
            items = results,
            key = { it.hostname },
        ) { star ->
            StarRow(
                star = star,
                settings = settings,
                onClick = { onStarSelected(star.hostname) },
                modifier = Modifier
                    .animateItem(
                        placementSpec = tween(260, easing = FastOutSlowInEasing),
                    )
                    .padding(
                        horizontal = spacing.lg,
                        vertical = spacing.xs / 2,
                    ),
            )
        }

        // Empty state
        if (results.isEmpty() && searchQuery.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.xxxl, vertical = spacing.xxxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "Search for a host star above, or tap a planet in the catalog to view its system.",
                        style = type.bodyLarge.copy(
                            color = colors.textTertiary,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StarRow(
    star: Star,
    settings: UserSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val cardShape = RoundedCornerShape(4.dp)
    val spectralColor = TeffColor.forStar(star.teffK, star.effectiveSpectralType())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.surfaceCard)
            .touchRipple(
                color = Color.White,
                startAlpha = 0.10f,
                onClick = onClick,
            )
            .border(1.dp, colors.surfaceBorderHalf, cardShape)
            .padding(vertical = spacing.lg, horizontal = spacing.xl),
    ) {
        // Star name (with proper name if available)
        val starProperName = ProperNames.forStar(star.hostname)
        val displayName = if (settings.useProperNames && starProperName != null) starProperName else star.hostname
        val altName = if (settings.useProperNames && starProperName != null) star.hostname else starProperName

        BasicText(
            text = displayName,
            style = type.titleMedium.copy(color = colors.textPrimary),
        )
        if (altName != null) {
            BasicText(
                text = altName,
                style = type.labelSmall.copy(color = colors.textMuted),
            )
        }

        // Alternate catalog designations (HD, HIP, TIC)
        val designations = buildAlternateDesignations(star)
        if (designations != null) {
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = designations,
                style = type.labelSmall.copy(color = colors.textTertiary),
            )
        }

        // Subtitle: spectral type + distance
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val spectral = formatStarSpectral(star, settings.useEstimates)
            val distance = formatStarDistance(star, settings)
            if (spectral != null) {
                val isEstimatedSp = spectral.startsWith("~")
                val displaySp = if (isEstimatedSp) spectral.removePrefix("~") else spectral
                BasicText(
                    text = displaySp,
                    style = type.labelLarge.copy(
                        color = spectralColor ?: colors.textTertiary,
                        fontStyle = if (isEstimatedSp) FontStyle.Italic else FontStyle.Normal,
                    ),
                )
            }
            if (spectral != null && distance != null) {
                BasicText(
                    text = " · ",
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
            }
            if (distance != null) {
                BasicText(
                    text = distance,
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
            }
            if (spectral == null && distance == null) {
                BasicText(
                    text = "—",
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
            }
        }

        // Stat row
        Spacer(Modifier.height(spacing.md))
        if (ExoTheme.isAccessible) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xxl)) {
                    Box(Modifier.weight(1f)) { StarStat("TEFF", formatStarTeff(star, settings)) }
                    Box(Modifier.weight(1f)) { StarStat("MASS", star.massSolar?.let { "${limitPrefix(star.massSolarLimit)}%.2f M☉".format(it) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xxl)) {
                    Box(Modifier.weight(1f)) { StarStat("RADIUS", star.radiusSolar?.let { "${limitPrefix(star.radiusSolarLimit)}%.2f R☉".format(it) }) }
                    Box(Modifier.weight(1f)) { StarStat("PLANETS", star.planetCount?.toString()) }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xxl)) {
                StarStat("TEFF", formatStarTeff(star, settings))
                StarStat("MASS", star.massSolar?.let { "${limitPrefix(star.massSolarLimit)}%.2f M☉".format(it) })
                StarStat("RADIUS", star.radiusSolar?.let { "${limitPrefix(star.radiusSolarLimit)}%.2f R☉".format(it) })
                StarStat("PLANETS", star.planetCount?.toString())
            }
        }
    }
}

@Composable
private fun StarStat(label: String, value: String?) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Column {
        BasicText(
            text = label,
            style = type.labelLarge.copy(color = colors.textMuted),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = AstroFormat.astroAnnotated(value ?: "—"),
            style = type.bodyMedium.copy(
                color = if (value != null) colors.textSecondary else colors.textTertiary,
            ),
        )
    }
}

// --- Formatting helpers ---

private fun formatStarSpectral(star: Star, useEstimates: Boolean = true): String? {
    star.spectralType?.let {
        return it.trim()
            .replace("&plusmn;", "±")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
    if (star.isPulsar()) return "Q"
    if (useEstimates) star.teffK?.let { return inferSpectralClass(it) }
    return null
}

private fun inferSpectralClass(teffK: Double): String = when {
    teffK >= 30000 -> "~O"
    teffK >= 10000 -> "~B"
    teffK >= 7500 -> "~A"
    teffK >= 6000 -> "~F"
    teffK >= 5200 -> "~G"
    teffK >= 3700 -> "~K"
    teffK >= 2400 -> "~M"
    teffK >= 1300 -> "~L"
    teffK >= 300 -> "~T"
    else -> "~Y"
}

private fun buildAlternateDesignations(star: Star): String? {
    val parts = listOfNotNull(star.hdName, star.hipName, star.ticId)
        .filter { it != star.hostname }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

private fun formatStarTeff(star: Star, settings: UserSettings): String? =
    star.teffK?.let { k ->
        val prefix = limitPrefix(star.teffKLimit)
        when (settings.starTemperatureUnit) {
            TemperatureUnit.KELVIN -> "${prefix}%.0f K".format(k)
            TemperatureUnit.CELSIUS -> "${prefix}%.0f °C".format(k - 273.15)
            TemperatureUnit.FAHRENHEIT -> "${prefix}%.0f °F".format((k - 273.15) * 9.0 / 5.0 + 32.0)
        }
    }

private fun formatStarDistance(star: Star, settings: UserSettings): String? {
    val pc = star.distancePc ?: return null
    return when (settings.distanceUnit) {
        com.tadmor.domain.model.DistanceUnit.PARSECS -> "%.1f pc".format(pc)
        com.tadmor.domain.model.DistanceUnit.LIGHT_YEARS -> "%.1f ly".format(pc * 3.26156)
    }
}
