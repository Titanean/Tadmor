package com.tadmor.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tadmor.app.BuildConfig
import com.tadmor.app.ui.components.NavDestination
import com.tadmor.app.ui.components.pushOnPress
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.util.AstroFormat
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.TemperatureUnit

@Composable
fun SettingsScreen(
    selectedNav: NavDestination = NavDestination.SETTINGS,
    onSelectNav: (NavDestination) -> Unit = {},
viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xxxl)
            .padding(top = 18.dp, bottom = spacing.xxxl + LocalBottomBarHeight.current),
    ) {
        BasicText(
            text = "SETTINGS",
            style = type.labelLarge.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = "Preferences",
            style = type.displayLarge.copy(color = colors.textPrimary),
        )

        Spacer(Modifier.height(spacing.xxxl))

        // Display section
        SectionHeader("DISPLAY")

        Spacer(Modifier.height(spacing.lg))

        ToggleSetting(
            label = "Data availability",
            description = "Show data completeness indicator on planet cards",
            checked = settings.showDataIndicator,
            onCheckedChange = { viewModel.setShowDataIndicator(it) },
        )

        Spacer(Modifier.height(spacing.xxl))

        ToggleSetting(
            label = "Neutron star rotation",
            description = "Spin neutron stars and pulsars in the star globe view",
            checked = settings.neutronStarRotation,
            onCheckedChange = { viewModel.setNeutronStarRotation(it) },
        )

        Spacer(Modifier.height(spacing.xxxl))

        // Controls section
        SectionHeader("CONTROLS")

        Spacer(Modifier.height(spacing.lg))

        ToggleSetting(
            label = "Invert camera controls",
            description = "Drag to move the camera around the scene rather than rotating the scene under a fixed camera",
            checked = settings.invertCameraControls,
            onCheckedChange = { viewModel.setInvertCameraControls(it) },
        )

        Spacer(Modifier.height(spacing.xxxl))

        // Units section
        SectionHeader("UNITS")

        Spacer(Modifier.height(spacing.lg))

        SettingGroup(
            label = "Distance",
            description = "Stellar distance display unit",
        ) {
            OptionRow(
                options = DistanceUnit.entries.map { it.label },
                selected = settings.distanceUnit.label,
                onSelect = { label ->
                    DistanceUnit.entries.find { it.label == label }
                        ?.let { viewModel.setDistanceUnit(it) }
                },
            )
        }

        Spacer(Modifier.height(spacing.xxl))

        SettingGroup(
            label = "Planet temperature",
            description = "Equilibrium temperature display",
        ) {
            OptionRow(
                options = TemperatureUnit.entries.map { it.label },
                selected = settings.temperatureUnit.label,
                onSelect = { label ->
                    TemperatureUnit.entries.find { it.label == label }
                        ?.let { viewModel.setTemperatureUnit(it) }
                },
            )
        }

        Spacer(Modifier.height(spacing.xxl))

        SettingGroup(
            label = "Star temperature",
            description = "Effective temperature (Teff) display",
        ) {
            OptionRow(
                options = TemperatureUnit.entries.map { it.label },
                selected = settings.starTemperatureUnit.label,
                onSelect = { label ->
                    TemperatureUnit.entries.find { it.label == label }
                        ?.let { viewModel.setStarTemperatureUnit(it) }
                },
            )
        }

        Spacer(Modifier.height(spacing.xxxl))

        // Unit symbols section
        SectionHeader("UNIT SYMBOLS")

        Spacer(Modifier.height(spacing.lg))

        SettingGroup(
            label = "Earth units",
            description = AstroFormat.astroAnnotated("Symbol (M⊕, R⊕) or letter (ME, RE)"),
        ) {
            OptionRow(
                options = listOf("Symbol", "Letter"),
                selected = if (settings.useEarthSymbol) "Symbol" else "Letter",
                onSelect = { viewModel.setUseEarthSymbol(it == "Symbol") },
            )
        }

        Spacer(Modifier.height(spacing.xxl))

        SettingGroup(
            label = "Jupiter units",
            description = AstroFormat.astroAnnotated("Symbol (M♃, R♃) or letter (MJ, RJ)"),
        ) {
            OptionRow(
                options = listOf("Symbol", "Letter"),
                selected = if (settings.useJupiterSymbol) "Symbol" else "Letter",
                onSelect = { viewModel.setUseJupiterSymbol(it == "Symbol") },
            )
        }

        Spacer(Modifier.height(spacing.xxxl))

        // Naming section
        SectionHeader("NAMING")

        Spacer(Modifier.height(spacing.lg))

        SettingGroup(
            label = "Rocky worlds",
            description = "Terrestrial planet class name",
        ) {
            OptionRow(
                options = listOf("Earth", "Terrestrial"),
                selected = if (settings.useTerra) "Terrestrial" else "Earth",
                onSelect = { viewModel.setUseTerra(it == "Terrestrial") },
            )
        }

        Spacer(Modifier.height(spacing.xxl))

        SettingGroup(
            label = "Ice giants",
            description = "Ice giant class name",
        ) {
            OptionRow(
                options = listOf("Neptune", "Ice Giant"),
                selected = if (settings.useNeptune) "Neptune" else "Ice Giant",
                onSelect = { viewModel.setUseNeptune(it == "Neptune") },
            )
        }

        Spacer(Modifier.height(spacing.xxl))

        SettingGroup(
            label = "Gas giants",
            description = "Gas giant class name",
        ) {
            OptionRow(
                options = listOf("Jupiter", "Gas Giant"),
                selected = if (settings.useJupiter) "Jupiter" else "Gas Giant",
                onSelect = { viewModel.setUseJupiter(it == "Jupiter") },
            )
        }

        Spacer(Modifier.height(spacing.xxl))

        SettingGroup(
            label = "Planet names",
            description = "Use IAU proper names when available",
        ) {
            OptionRow(
                options = listOf("Proper", "Standard"),
                selected = if (settings.useProperNames) "Proper" else "Standard",
                onSelect = { viewModel.setUseProperNames(it == "Proper") },
            )
        }

        Spacer(Modifier.height(spacing.xxxl))

        // Data section
        SectionHeader("DATA")

        Spacer(Modifier.height(spacing.lg))

        ToggleSetting(
            label = "Estimated values",
            description = "Show values derived from star parameters when catalog data is missing. Estimated values are displayed in italics.",
            checked = settings.useEstimates,
            onCheckedChange = { viewModel.setUseEstimates(it) },
        )

        Spacer(Modifier.height(spacing.xxl))

        ToggleSetting(
            label = "Include unconfirmed candidates",
            description = "Show NASA-tracked exoplanet candidates and confirmed false positives in extra catalog tabs and on system pages",
            checked = settings.includeCandidates,
            onCheckedChange = { viewModel.setIncludeCandidates(it) },
        )

        Spacer(Modifier.height(spacing.xxl))

        ToggleSetting(
            label = "Auto-sync catalog",
            description = "Periodically fetch updates from NASA's Exoplanet Archive in the background (roughly once a day)",
            checked = settings.autoSyncEnabled,
            onCheckedChange = { viewModel.setAutoSyncEnabled(it) },
        )

        Spacer(Modifier.height(spacing.xxl))

        ToggleSetting(
            label = "Sync on Wi-Fi only",
            description = "Restrict auto-sync to unmetered networks. Has no effect when auto-sync is off.",
            checked = settings.autoSyncWifiOnly,
            onCheckedChange = { viewModel.setAutoSyncWifiOnly(it) },
        )

        Spacer(Modifier.height(spacing.xxxl))

        // ABOUT section — data attribution, procedural-visualization
        // disclaimer, and the science references the engine is built on.
        // Sits at the end of the settings page since it's the lowest-
        // frequency thing a user will look at; matches the "About" /
        // "Credits" convention every other science app uses.
        SectionHeader("ABOUT")

        Spacer(Modifier.height(spacing.lg))

        BasicText(
            text = "Tadmor",
            style = type.displayMedium.copy(color = colors.textPrimary),
        )
        BasicText(
            text = "Version ${BuildConfig.VERSION_NAME}",
            style = type.labelSmall.copy(color = colors.textTertiary),
        )

        Spacer(Modifier.height(spacing.xxl))

        AboutSection(
            label = "Data source",
            body = "All catalogued planetary and stellar parameters come " +
                "from NASA's Exoplanet Archive — the confirmed-planet " +
                "(pscomppars) and stellar-host tables, plus the TOI, KOI, " +
                "and K2 candidate archives. Auto-sync fetches updates " +
                "roughly once a day.",
        )

        Spacer(Modifier.height(spacing.xxl))

        AboutSection(
            label = "Visualizations",
            body = "Planet, star, and atmosphere views are procedurally " +
                "generated by a deterministic visual profile engine: " +
                "surface composition, atmospheric chemistry, optical " +
                "properties, ring systems, and weather are derived from " +
                "physical parameters (mass, radius, temperature, " +
                "insolation, density, stellar host) via published models, " +
                "then rendered through real-time OpenGL ES 3.0 shaders. " +
                "None of these are photographs — they represent one " +
                "plausible appearance consistent with the catalogued data.",
        )

        Spacer(Modifier.height(spacing.xxl))

        AboutSection(
            label = "Estimated values",
            body = "Values derived from stellar parameters when catalog " +
                "data is missing (orbital periods from Kepler's third law, " +
                "semi-major axes from period + stellar mass, equilibrium " +
                "temperatures from insolation) are shown in italics. " +
                "Toggle them off in the DATA section above to see only " +
                "direct observations.",
        )

        Spacer(Modifier.height(spacing.xxl))

        AboutSection(
            label = "Key references",
            body = "Habitable zone: Kopparapu et al. (2013)\n" +
                "Binary stability: Holman & Wiegert (1999)\n" +
                "Ring stability: Domingos et al. (2006)\n" +
                "Atmospheric escape: Lopez & Fortney (2013)\n" +
                "Silicate clouds: Kite et al. (2016)\n" +
                "Transit mean anomaly: Winn (2010)\n" +
                "Rocky radius–mass: Valencia et al. (2007)\n" +
                "Blackbody colour: Tanner Helland approximation",
        )

    }

    }
}

/**
 * About-section paragraph: a small bold title with a multi-line body
 * beneath. Body uses `bodyMedium` (a step up from the descriptive
 * `labelLarge` used elsewhere on the page) since these paragraphs are
 * read carefully, not skimmed for toggle context, and they're longer.
 */
@Composable
private fun AboutSection(label: String, body: String) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    Column {
        BasicText(
            text = label,
            style = type.titleMedium.copy(color = colors.textPrimary),
        )
        Spacer(Modifier.height(spacing.xs))
        BasicText(
            text = body,
            style = type.bodyMedium.copy(color = colors.textSecondary),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Column {
        BasicText(
            text = text,
            style = type.labelSmall.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )
    }
}

@Composable
private fun SettingGroup(
    label: String,
    description: String,
    content: @Composable () -> Unit,
) {
    SettingGroup(label, AnnotatedString(description), content)
}

@Composable
private fun SettingGroup(
    label: String,
    description: AnnotatedString,
    content: @Composable () -> Unit,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Column {
        BasicText(
            text = label,
            style = type.titleMedium.copy(color = colors.textPrimary),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = description,
            style = type.labelLarge.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.height(ExoTheme.spacing.md))
        content()
    }
}

@Composable
private fun OptionRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(20.dp)
    val isAccessible = ExoTheme.isAccessible
    val hPad = if (isAccessible) 18.dp else 14.dp
    val vPad = if (isAccessible) 10.dp else 6.dp
    val spacing = ExoTheme.spacing

    val selectedIdx = options.indexOf(selected)
    val prevIdxHolder = remember { mutableIntStateOf(selectedIdx) }
    val enterDirection = when {
        selectedIdx > prevIdxHolder.intValue -> 1
        selectedIdx < prevIdxHolder.intValue -> -1
        else -> 0
    }
    LaunchedEffect(selectedIdx) {
        prevIdxHolder.intValue = selectedIdx
    }

    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        options.forEach { option ->
            val isActive = option == selected
            val progress = remember(option) { Animatable(if (isActive) 1f else 0f) }
            var initialized by remember(option) { mutableStateOf(false) }
            // Direction is snapshotted when this option activates so the
            // wipe stays stable across the recomposition that fires when
            // prevIdxHolder updates.
            val activationDir = remember(option) { mutableIntStateOf(0) }
            LaunchedEffect(isActive) {
                if (!initialized) {
                    initialized = true
                    return@LaunchedEffect
                }
                if (isActive) {
                    activationDir.intValue = enterDirection
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                } else {
                    progress.snapTo(0f)
                }
            }

            val textColor = if (isActive) colors.accentGold else colors.textSecondary
            val borderColor = if (isActive) colors.accentGoldBorder else colors.divider

            val pressSource = remember(option) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .pushOnPress(pressSource)
                    .clip(shape)
                    .drawBehind {
                        val p = progress.value
                        if (!isActive || p <= 0f) {
                            drawRect(colors.surfaceRaised)
                        } else {
                            val fillW = size.width * p
                            val dir = activationDir.intValue
                            val fillStartX = if (dir >= 0) 0f else size.width - fillW
                            val baseStartX = if (dir >= 0) fillW else 0f
                            val baseW = size.width - fillW
                            // Un-wiped portion stays inactive (opaque surface).
                            if (baseW > 0f) {
                                drawRect(
                                    color = colors.surfaceRaised,
                                    topLeft = Offset(baseStartX, 0f),
                                    size = Size(baseW, size.height),
                                )
                            }
                            // Wiped portion: accentGoldSubtle is 7% alpha and
                            // sits directly on the parent bg (no opaque base)
                            // so it matches the original highlight tone.
                            drawRect(
                                color = colors.accentGoldSubtle,
                                topLeft = Offset(fillStartX, 0f),
                                size = Size(fillW, size.height),
                            )
                        }
                    }
                    .border(1.dp, borderColor, shape)
                    .clickable(
                        indication = null,
                        interactionSource = pressSource,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = hPad, vertical = vPad),
            ) {
                BasicText(
                    text = option,
                    style = type.labelLarge.copy(color = textColor),
                )
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = label,
                style = type.titleMedium.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = description,
                style = type.labelLarge.copy(color = colors.textTertiary),
            )
        }
        Spacer(Modifier.width(ExoTheme.spacing.lg))
        ExoToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ExoToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = ExoTheme.colors
    val isAccessible = ExoTheme.isAccessible
    val trackWidth = if (isAccessible) 50.dp else 40.dp
    val trackHeight = if (isAccessible) 28.dp else 22.dp
    val cornerRadius = trackHeight / 2

    val floatSpec = tween<Float>(durationMillis = 140, easing = FastOutSlowInEasing)
    val colorSpec = tween<Color>(durationMillis = 140, easing = FastOutSlowInEasing)
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = floatSpec,
        label = "togglePos",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accentGoldSubtle else colors.surfaceRaised,
        animationSpec = colorSpec,
        label = "toggleTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colors.accentGold else colors.textMuted,
        animationSpec = colorSpec,
        label = "toggleThumb",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) colors.accentGoldBorder else colors.divider,
        animationSpec = colorSpec,
        label = "toggleBorder",
    )

    Box(
        modifier = Modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(RoundedCornerShape(cornerRadius))
            .drawBehind {
                drawRoundRect(
                    color = trackColor,
                    cornerRadius = CornerRadius(size.height / 2f),
                )
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(size.height / 2f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                val thumbR = size.height * 0.34f
                val startX = size.height / 2f
                val endX = size.width - size.height / 2f
                val thumbX = startX + (endX - startX) * progress
                drawCircle(
                    color = thumbColor,
                    radius = thumbR,
                    center = Offset(thumbX, size.height / 2f),
                )
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onCheckedChange(!checked) },
            ),
    )
}
