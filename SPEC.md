__TADMOR__

Native Android Exoplanet Explorer

with Procedural Planet Visualisation

Project Specification Document

Version 3.0  —  March 2026  —  Android Native

# 1. Project Overview

Tadmor is a native Android application that serves as a live, visually rich catalog of all confirmed exoplanets. Named after the IAU designation of Gamma Cephei Ab — one of the first confirmed exoplanets — the app sources data directly from the NASA Exoplanet Archive TAP API (no backend proxy required). It provides four primary views: a searchable catalog, an interactive 3D star map, per-system orbital views, and procedurally generated planet visualisations including surface-level atmospheric rendering.

All planet visualisations are speculative, driven by known physical parameters combined with controlled randomness. A persistent disclaimer is displayed on all procedural views.

## 1.1 Design Principles

- Data-first: every visual decision traces back to a real parameter from the archive
- Honest uncertainty: speculative elements are clearly labelled and seeded with randomness
- Native performance: OpenGL ES 3.0 for all 3D rendering; 60fps on mid-range devices
- Offline-capable: local Room database caches all planet data for offline browsing
- Custom design system: no Material Design components; all UI follows DESIGN.md with Jost typeface, dark-only palette, and bespoke components
- Modularity: each view is a self-contained Compose screen backed by its own ViewModel

## 1.2 Target Devices

- Minimum SDK: API 26 (Android 8.0) — covers ~95% of active devices
- Target SDK: API 35 (latest stable)
- OpenGL ES 3.0 required (supported by virtually all devices API 26+)
- Primary test targets: mid-range devices (Pixel 6a tier) and up

# 2. Technology Stack

## 2.1 Application Layer

__Layer__

__Technology__

__Notes__

Language

Kotlin

100% Kotlin, no Java

UI Framework

Jetpack Compose

Custom design system, no Material components

Architecture

MVVM + Clean Architecture

ViewModel + StateFlow + Use Cases

Navigation

Compose Navigation

Type-safe routes with deep linking

DI

Hilt

Dagger-based dependency injection

## 2.2 Data Layer

__Layer__

__Technology__

__Notes__

HTTP Client

Ktor Client or Retrofit

Direct TAP API queries, no proxy needed

Local DB

Room

SQLite wrapper; offline cache for all planet data

Serialisation

Kotlinx Serialization

JSON parsing from TAP API responses

Background Sync

WorkManager

Periodic data refresh (default: daily)

## 2.3 3D Rendering

__Layer__

__Technology__

__Notes__

Graphics API

OpenGL ES 3.0

Broad device support; GLSL ES 3.00 shaders

GL Surface

GLSurfaceView / TextureView

Embedded in Compose via AndroidView

Shaders

Custom GLSL ES 3.00

Vertex + fragment for surfaces, atmospheres

Touch Input

GestureDetector / custom

Pinch-to-zoom, drag-to-rotate, fling, double-tap

## 2.4 Build System

- Gradle with Kotlin DSL (build.gradle.kts)
- Version catalog (libs.versions.toml) for dependency management
- GLSL shader files stored in assets/shaders/
- Jost font files (weights 200, 300, 400, 500) bundled in res/font/
- ProGuard/R8 for release builds

# 3. Application Architecture

## 3.1 Layer Overview

The app follows Clean Architecture with three layers, enforced by Gradle module boundaries:

__Presentation Layer (app module)__

- Jetpack Compose screens and UI components
- ViewModels exposing StateFlow for reactive UI
- OpenGL renderer classes for 3D views
- Navigation graph and deep link handling

__Domain Layer (domain module)__

- Use case classes (e.g. GetPlanetsUseCase, ClassifyPlanetUseCase, SearchCatalogUseCase)
- Domain models (Planet, Star, System, PlanetVisualProfile)
- Repository interfaces (implemented in data layer)
- The classification engine lives here — pure Kotlin, no Android dependencies

__Data Layer (data module)__

- Repository implementations
- Room database entities + DAOs
- TAP API service (Retrofit/Ktor interface)
- Data mappers: API response → Room entity → domain model
- WorkManager worker for background sync

## 3.2 Compose-to-OpenGL Bridge

The 3D views (star map, system view, planet globe, surface view) each run in a GLSurfaceView embedded into Compose via AndroidView. The bridge works as follows:

1. The Compose screen hosts an AndroidView wrapping a custom GLSurfaceView subclass
2. The ViewModel exposes the data (planet parameters, visual profile) as StateFlow
3. The Compose layer observes the state and passes updated parameters to the renderer via a thread-safe interface
4. The GLSurfaceView.Renderer runs on the GL thread; it reads the latest parameters each frame
5. Touch events are intercepted by the GLSurfaceView and forwarded to a camera controller (orbit, pan, zoom)

This keeps the GL rendering loop decoupled from Compose recomposition. The Compose UI (info panels, controls, disclaimers) is overlaid on top of the GL view using a Box layout.

## 3.3 Data Flow: TAP API to Screen

1. WorkManager triggers a periodic sync (or the user pulls to refresh)
2. The repository fetches from the TAP API: ADQL query over HTTP GET, format=json
3. Response is parsed via Kotlinx Serialization into API DTOs
4. DTOs are mapped to Room entities and upserted into the local database
5. Room DAOs expose Flow<List<PlanetEntity>> for reactive queries
6. The repository maps entities to domain models
7. Use cases apply business logic (filtering, sorting, classification)
8. ViewModels expose the final UI state as StateFlow
9. Compose screens collect the flow and render

# 4. Data Layer

## 4.1 Source: NASA Exoplanet Archive TAP API

The TAP service at exoplanetarchive.ipac.caltech.edu/TAP is queried directly from the Android device. No backend proxy is needed because Android HTTP clients are not subject to CORS. The relevant table is pscomppars (Planetary Systems Composite Parameters) which provides deduplicated, best-available values per planet.

## 4.2 ADQL Query

SELECT pl_name, hostname, pl_bmassj, pl_bmasse, pl_rade, pl_orbsmax, pl_orbper, pl_orbeccen, pl_orbincl, pl_eqt, pl_insol, pl_dens, discoverymethod, disc_year, st_spectype, st_teff, st_rad, st_mass, st_lum, ra, dec, sy_dist, sy_pnum FROM pscomppars

Request URL format: https://exoplanetarchive.ipac.caltech.edu/TAP/sync?query=<ADQL>&format=json

## 4.3 Key Columns

The following columns drive the entire application:

### 4.3.1 Planet Parameters

__TAP Column__

__Kotlin Field__

__Unit__

__Used For__

pl_name

name

—

Display, navigation, seed hash

pl_bmassj

massJupiter

Jupiter masses

Classification, density calc

pl_bmasse

massEarth

Earth masses

Classification fallback

pl_rade

radiusEarth

Earth radii

Classification, visual sizing

pl_orbsmax

semiMajorAxisAU

AU

Orbital view, HZ calc

pl_orbper

orbitalPeriodDays

days

Orbital animation speed

pl_orbeccen

eccentricity

0–1

Orbit shape, climate estimate

pl_orbincl

inclination

degrees

Orbital view tilt

pl_eqt

eqTempK

K

Surface state, atmosphere, colour

pl_insol

insolationFlux

Earth flux

HZ placement, atm. retention

pl_dens

densityGCm3

g/cm³

Composition class

discoverymethod

discoveryMethod

—

Catalog filtering, stats

disc_year

discoveryYear

—

Catalog filtering, stats

### 4.3.2 Stellar Parameters

__TAP Column__

__Kotlin Field__

__Unit__

__Used For__

hostname

starName

—

System grouping

st_spectype

spectralType

—

Star colour, scattering colour

st_teff

starTeffK

K

Star colour via black-body

st_rad

starRadiusSolar

Solar radii

Angular size from surface

st_mass

starMassSolar

Solar masses

HZ calculation

st_lum

starLogLuminosity

log(Solar)

HZ boundaries, insolation

ra

rightAscensionDeg

degrees

Star map positioning

dec

declinationDeg

degrees

Star map positioning

sy_dist

distancePc

parsecs

Star map depth, 3D position

sy_pnum

planetCount

—

System view, filtering

## 4.4 Room Database Schema

Two primary tables:

__planets__

- Primary key: pl_name (unique planet identifier)
- All columns from Section 4.3.1 as nullable Doubles/Strings (many planets have incomplete data)
- Foreign key: hostname references stars table
- Index on: hostname, discoveryMethod, discoveryYear, eqTempK

__stars__

- Primary key: hostname (unique star identifier)
- All columns from Section 4.3.2
- Index on: spectralType, distancePc

Room DAOs expose Flow-based queries for reactive updates. Key queries: all planets (paginated), planets by system, planets with filters applied, single planet by name, all unique star positions for the star map.

## 4.5 Sync Strategy

- First launch: full data fetch, display progress indicator (takes ~5–10 seconds on reasonable connection)
- Subsequent launches: serve from Room immediately, trigger background refresh via WorkManager
- WorkManager constraint: requires network; periodic interval 24 hours, flex 6 hours
- Manual refresh: pull-to-refresh on catalog triggers immediate sync
- Conflict resolution: upsert on pl_name; new data always overwrites cached data

# 5. Planet Classification Engine

The core inference module. Pure Kotlin, lives in the domain layer with no Android dependencies. Maps known physical parameters to a visual profile. Draws from SpaceEngine’s classification taxonomy, extended with additional exotic types. Operates as a pure function: parameters in, PlanetVisualProfile out, with a deterministic random seed derived from the planet name hash.

## 5.1 Primary Classification System

Based on SpaceEngine's compositional classification taxonomy. A planet's full classification is built from up to four independent axes: **temperature class**, **volatiles class** (when known), **mass prefix**, and **bulk composition class**. The display format is:

> `[Temperature] [Volatiles] [Mass Prefix][Bulk Composition]`

Example: "Temperate Oceanic Terra", "Hot Super-Jupiter", "Frigid Sub-Neptune"

When an axis is unknown (most commonly volatiles), it is omitted rather than labelled "Unknown".

### 5.1.1 Temperature Classes

Derived from equilibrium temperature (`pl_eqt`). This axis is always computable when Teq is known.

| Class        | Range        | Visual Influence                                        |
|--------------|-------------|---------------------------------------------------------|
| Frigid       | < 90 K      | Ice-dominated surfaces, nitrogen frost                  |
| Cold         | 90–170 K    | Methane/ammonia ices, pale blues and whites              |
| Cool         | 170–250 K   | CO₂ frost possible, muted earthy tones                  |
| Temperate    | 250–330 K   | Liquid water possible, blue-green earth-like palettes   |
| Warm         | 330–500 K   | Greenhouse effects, Venus-like cloud decks              |
| Hot          | 500–1000 K  | Alkali metal absorption, dark browns and deep oranges   |
| Torrid       | > 1000 K    | Silicate clouds, magma oceans, glowing surfaces         |

### 5.1.2 Volatiles Classes

Describes atmosphere and surface liquids. Rarely determinable from current exoplanet data — only included when atmospheric observations (e.g. JWST transmission spectra) provide evidence. Omitted from the label for the vast majority of planets.

| Class         | Description                                              |
|---------------|----------------------------------------------------------|
| Airless       | Atmospheric pressure < 1 nanobar                         |
| Desertic      | Atmosphere present, no surface liquids                   |
| Lacustrine    | Partial liquid coverage (lakes)                          |
| Marine        | Seas covering partial surface                            |
| Oceanic       | Global liquid ocean                                      |
| Superoceanic  | Extremely deep oceans with exotic high-pressure ices     |

### 5.1.3 Bulk Composition Classes

The primary classification axis. Determined from mass, radius, and density.

__Solid Planets__

| Class    | Criteria                    | Palette / Visual                                         |
|----------|----------------------------|----------------------------------------------------------|
| Terra    | Solid rocky/terrestrial (default for non-giant planets) | Grey/brown cratered (airless) to blue-green continents (temperate). Earth/Mars/Mercury analogues depending on temperature |

__Gas and Ice Giants__

| Class    | Criteria                    | Palette / Visual                                         |
|----------|----------------------------|----------------------------------------------------------|
| Neptune  | Mixed H/He (< 25%) with water/ammonia/methane ices. 2–6 R⊕, 5–50 M⊕ | Azure to pale blue-green (cold). Hazy cream-yellow (warm). Bloated muted (hot) |
| Jupiter  | > 25% hydrogen/helium. R > 6 R⊕ or M > 50 M⊕ | Banded ochre/cream (cold). White/blue clouds (cool). Deep blue (warm). Dark brown (hot). Dull red-grey (torrid) |

### 5.1.4 Mass Prefixes

Applied before the bulk composition class to indicate relative size.

__Solid planets (Earth masses, M⊕):__

| Prefix | Range           | Notes                            |
|--------|-----------------|----------------------------------|
| Micro  | < 0.002 M⊕     | Asteroid-scale bodies            |
| Mini   | 0.002–0.02 M⊕  | Ceres to Moon-scale              |
| Sub    | 0.02–0.2 M⊕    | Moon to Mars-scale               |
| —      | 0.2–2 M⊕       | Earth-scale (no prefix)          |
| Super  | 2–10 M⊕        | Super-Earths                     |
| Mega   | > 10 M⊕        | Massive rocky/water worlds       |

__Ice giants / Neptunes (Earth masses, M⊕):__

| Prefix | Range           |
|--------|-----------------|
| Mini   | < 4 M⊕         |
| Sub    | 4–10 M⊕        |
| —      | 10–25 M⊕       |
| Super  | 25–62.5 M⊕     |
| Mega   | > 62.5 M⊕      |

__Gas giants / Jupiters (Jupiter masses, M♃):__

| Prefix | Range           |
|--------|-----------------|
| Sub    | < 0.2 M♃       |
| —      | 0.2–2 M♃       |
| Super  | 2–10 M♃        |
| Mega   | > 10 M♃        |

### 5.1.5 Visual Profiles by Composition × Temperature

The combination of bulk composition and temperature class drives the procedural visual:

__Terra × Temperature:__

- Frigid/Cold: Ice-covered rocky surface, white-blue, fracture patterns (Europa-like)
- Cool: Mars analogue — rust-red, ochre, iron oxide desert, polar frost caps
- Temperate: Earth analogue — blue-green oceans, brown-green land, white clouds, Rayleigh blue sky
- Warm: Venus analogue — opaque pale yellow-white cloud deck, no visible surface
- Hot/Torrid: Lava world — fractured dark crust with glowing orange-red magma fissures

__Jupiter × Temperature:__

- Frigid/Cold: Class I Jovian — ammonia clouds, ochre/tan/cream/rust banding, storm features
- Cool: Class II — water vapour cloud decks, white/pale blue-grey, smoother banding
- Warm: Class III — cloudless, deep blue Rayleigh scattering, featureless
- Hot: Class IV — alkali metal absorption, dark brown/deep orange, patchy clouds
- Torrid: Class V — silicate/iron clouds, dull red-grey, bright dayside spots, extreme day-night contrast

__Neptune × Temperature:__

- Frigid/Cold: Neptune analogue — azure/deep blue methane absorption, cloud streaks, storm spots
- Cool/Temperate: Sub-Neptune — pale blue-green to grey-blue, hazy, lightly banded
- Warm: Warm sub-Neptune — cream to pale yellow-orange, photochemical haze
- Hot/Torrid: Hot sub-Neptune — bloated, muted colours, possible evaporative tail

## 5.2 Classification Decision Logic

1. Compute bulk density if both mass and radius known: ρ = M / ((4/3)πR³)
2. Determine **bulk composition class**:
   a. Jupiter: R > 6 R⊕ or M > 50 M⊕, or M > 0.2 M♃ with low density
   b. Neptune: 2–6 R⊕ and 5–50 M⊕, or density 1–3 g/cm³ in that size range
   c. Terra: All remaining solid planets (default for non-giant planets)
   d. Fallback: if only mass known, use mass thresholds; if neither mass nor radius, classify as Terra
3. Determine **temperature class** from `pl_eqt` using the 7-band table
4. Determine **mass prefix** using the appropriate mass table for the composition class
5. Determine **volatiles class** only if atmospheric data is available (future JWST integration); omit otherwise
6. Compose full label: `[Temperature] [Volatiles] [Mass Prefix][Composition]`
7. Select visual profile from the composition × temperature matrix (Section 5.1.5)
8. Apply deterministic random seed (planet name hash) for stochastic choices: continent shapes, cloud coverage, banding intensity, crater density, colour variation within palette

## 5.3 PlanetVisualProfile Data Class

Kotlin data class output from the classifier:

- compositionClass: CompositionClass — enum: TERRA, NEPTUNE, JUPITER
- temperatureClass: TemperatureClass — enum: FRIGID, COLD, COOL, TEMPERATE, WARM, HOT, TORRID
- massPrefix: MassPrefix — enum: MICRO, MINI, SUB, STANDARD, SUPER, MEGA
- volatilesClass: VolatilesClass? — nullable enum: AIRLESS, DESERTIC, LACUSTRINE, MARINE, OCEANIC, SUPEROCEANIC
- fullLabel: String — composed display label, e.g. "Temperate Super-Terra"
- surfaceType: SurfaceType — ROCKY, ICY, OCEAN, LAVA, GASEOUS, CLOUDED
- colorPalette: List<Color> — up to 8 colours for noise ramp
- noiseParams: NoiseParams — octaves, frequency, amplitude, lacunarity, bandingStrength, craterDensity
- atmosphereParams: AtmosphereParams — present, opacity, scatteringColor, cloudCoverage, cloudColor
- ringParams: RingParams? — nullable; innerRadius, outerRadius, color, opacity
- specialFeatures: Set<SpecialFeature> — LAVA_CRACKS, POLAR_CAPS, STORM_SPOT, VOLCANIC_PLUMES, etc.
- albedo: Float — estimated geometric albedo
- axialTilt: Float — degrees, random within plausible range
- rotationPeriod: Float — hours; tidally locked = synchronous
- seed: Long — deterministic from planet name hash; same planet always looks the same

# 6. Application Modules

## 6.1 Catalog Screen

The main landing screen. A searchable, filterable, sortable list of all confirmed exoplanets, built entirely in Jetpack Compose. All data from the NASA Exoplanet Archive `pscomppars` table (confirmed planets only — no candidates).

### Default Sort

Discovery date descending — most recently confirmed exoplanets appear first. Uses `disc_year` (and `disc_pubdate` if available for sub-year ordering). User can change sort to: name (A-Z), mass, radius, temperature, distance, orbital period, or planet count in system.

### Search

Full-text search by planet name, host star name, or constellation. Filters results in real time as the user types.

### Filter System

Replaces the flat chip row with a hierarchical filter menu. A single "Filters" button opens a bottom sheet with expandable filter groups. Active filters are summarised as compact chips below the search bar.

__Filter groups:__

| Group | Options | UI |
|-------|---------|-----|
| **Planet Class** | Bulk composition: Terra, Neptune, Jupiter | Multi-select chips per composition |
| **Temperature** | Frigid, Cold, Cool, Temperate, Warm, Hot, Torrid | Multi-select chips, colour-coded to temperature band |
| **Host Star** | Spectral class: O, B, A, F, G, K, M. Luminosity range slider. Distance range slider (pc) | Spectral class as multi-select chips. Luminosity and distance as dual-thumb range sliders |
| **Discovery** | Method: multi-select (Transit, Radial Velocity, Direct Imaging, Microlensing, etc.). Year range: dual-thumb slider | Method chips, year slider |
| **Physical** | Mass range (M⊕), radius range (R⊕), temperature range (K), density range (g/cm³), orbital period range | Dual-thumb range sliders for each |
| **System** | Planet count in system (1, 2, 3, 4, 5+) | Chip select |

__Active filter chips:__ Each applied filter appears as a removable chip below the search bar (e.g. "Terra", "Temperate", "M-dwarf", "2020–2025"). Tapping the chip removes that filter. A "Clear all" button appears when any filter is active.

### Card Layout

- Each row: planet name, key stats, classification badge (showing composed SpaceEngine label), data completeness indicator
- Tap row to expand inline detail card with all known parameters
- Quick-nav chips on each row: star map, system view, planet view

### Top Bar

- Summary stats: total planets count (updates with filters), compact discovery method breakdown
- Pull-to-refresh triggers immediate TAP API sync

### Data Completeness Indicator

A simple 5-pip visual showing how many of the key parameters (mass, radius, temperature, density, orbital elements) are known vs. null. Sets expectations for procedural visualisation quality.

## 6.2 Star Map Screen

An interactive 3D view plotting all host stars with confirmed exoplanets. Rendered via OpenGL ES 3.0.

### Coordinate System

RA (degrees), Dec (degrees), and distance (parsecs) converted to Cartesian: x = d·cos(dec)·cos(ra), y = d·cos(dec)·sin(ra), z = d·sin(dec). Sun at origin.

### Rendering

- Stars rendered as GL_POINTS with point size or as instanced quads for better control
- Colour from T_eff via black-body RGB lookup
- Point size scales with planet count (1 = small, 7+ = large)
- Distance-based alpha fade for depth cueing
- Optional: constellation wireframe overlay rendered as GL_LINES
- Distance rings at 10, 50, 100, 500, 1000 pc as faint dashed circles

### Interaction

- Touch-drag: orbit camera around origin
- Pinch: zoom
- Tap star: highlight + info tooltip overlay (Compose overlay on GL view)
- Long-press or double-tap: navigate to system orbital view
- Search integration: matching stars from catalog filter pulse or change colour

## 6.3 System Orbital View Screen

Dedicated view for a single stellar system showing all confirmed planets in orbit.

### Orbital Mechanics

- Keplerian ellipses from semi-major axis and eccentricity, rendered as line loops
- Orbital plane tilt from inclination
- Animated orbital motion with correct relative periods (time-accelerated)
- Planet spheres sized by log(radius)

### Habitable Zone

Calculated from stellar luminosity (Kopparapu et al. 2013). Rendered as a semi-transparent green annular region between inner (recent Venus) and outer (early Mars) boundaries.

### Scale

Toggle between logarithmic radial scale (default) and linear. Log scale clearly labelled. For systems with only close-in planets, linear may work well; for wide-separation systems, log is necessary.

### Additional Elements

- Host star at centre, sized and coloured correctly
- Planet labels on tap
- Tap planet: navigate to planet visualisation screen
- Time controls: play/pause, speed slider

## 6.4 Planet Visualisation Screen

3D rendered globe using the PlanetVisualProfile from the classification engine.

### OpenGL Rendering Pipeline

1. Generate UV sphere mesh (64+ segments)
2. Bind planet surface shader program; pass visual profile as uniforms
3. Layered 3D simplex noise in fragment shader, seeded by planet hash
4. Map noise through colour palette ramp
5. Apply surface type modifiers (banding, craters, fractures, lava glow)
6. Render atmosphere shell: slightly larger sphere, scattering shader, alpha blending
7. Render cloud layer: separate noise on elevated sphere, animated UV offset for drift
8. Render ring system if present (textured quad ring with alpha)
9. Directional light from host star colour

### Gas Giant Specifics

Latitude-dominant noise for banding. Storm spots as localised high-detail noise patches. Per-band colour from palette array. Turbulence modulated by seed.

### Rocky World Specifics

fBm terrain noise (6–8 octaves). Height mapped to colour via palette. Craters as radial SDFs subtracted from height. Ice worlds: Voronoi edge distance for fracture networks.

### Interaction

- Drag to rotate globe
- Pinch to zoom
- Compose overlay panel: all known parameters, classification type, classification reasoning
- Toggle buttons: atmosphere on/off, clouds on/off
- __Regenerate: __re-rolls random seed for a new variation within same classification
- Navigate to surface view button

## 6.5 Surface View Screen (Stretch Goal)

Camera on the planet’s surface looking at the sky.

### Terrain

Same noise function as globe, resampled at high local resolution. Camera at ~2m height. Terrain to horizon (distance from planet radius and curvature).

### Sky / Atmospheric Scattering

Simplified Rayleigh scattering model in fragment shader. Inputs: stellar T_eff, atmospheric density estimate, composition guess, sun angle.

- G/K star: blue sky
- M-dwarf: amber/orange sky
- F/A star: deeper blue / violet-tinged
- No atmosphere: black sky with star field

### Host Star

Rendered at correct angular diameter: θ = 2 × arctan(R_star / (2 × d_orbit)). Colour from black-body. Bloom post-process. For close-in planets, the star dominates the sky.

### Lighting

Directional light from host star. Tidally locked: star fixed in sky. Non-locked: time-of-day slider for day/night cycle.

# 7. GLSL ES 3.00 Shader Reference

All shaders target OpenGL ES 3.0 (GLSL ES 3.00). Stored in assets/shaders/ and loaded at runtime. The shader programs are compiled and cached on first use.

## 7.1 Planet Surface Fragment Shader

#version 300 es

precision highp float;

uniform vec3 uColorPalette[8];

uniform float uNoiseFrequency;

uniform int uNoiseOctaves;

uniform float uBandingStrength;      // 0 rocky, 0.5-1.0 gas

uniform float uCraterDensity;         // 0-1

uniform float uSeed;

uniform vec3 uStarPosition;

uniform vec3 uStarColor;

in vec3 vPosition;

in vec3 vNormal;

out vec4 fragColor;

// 1. Base terrain: fBm simplex noise

float h = fBm(vPosition * uNoiseFrequency + uSeed, uNoiseOctaves);

// 2. Gas giant banding

float lat = asin(vPosition.y / length(vPosition));

float band = sin(lat*20.0 + snoise(vec2(lat*5.0, uSeed))*2.0);

h = mix(h, band, uBandingStrength);

// 3. Crater overlay (rocky worlds)

h = min(h, voronoiCraters(vPosition, uCraterDensity, uSeed));

// 4. Palette lookup

vec3 col = samplePalette(uColorPalette, h);

// 5. Lighting

vec3 L = normalize(uStarPosition - vPosition);

float diff = max(dot(vNormal, L), 0.0);

fragColor = vec4(col * (0.05 + diff * uStarColor), 1.0);

## 7.2 Atmosphere Fragment Shader

Simplified single-scattering Rayleigh model applied to a shell sphere with alpha blending.

uniform vec3 uScatteringCoeff;        // From star T_eff

uniform float uAtmDensity;

uniform float uPlanetRadius;

uniform float uAtmRadius;

uniform vec3 uStarDir;

// Ray-sphere intersection for atm entry/exit

// Integrate optical depth along view ray

// Inscattering at each sample from star direction

// Phase: (3/16π)(1 + cos²θ)

// Output: vec4(scatteredColor, opacity)

## 7.3 Lava Crack Feature Shader

float cracks = 1.0 - voronoiEdge(vPosition * crackFreq, uSeed);

cracks = smoothstep(0.02, 0.0, cracks);

vec3 glow = vec3(1.0, 0.3, 0.05) * cracks * 3.0;

fragColor.rgb += glow;

## 7.4 Noise Utility (noise3d.glsl)

A standalone GLSL include file implementing 3D simplex noise and fBm. Shared across all surface and cloud shaders. This should be a well-tested implementation — the Ashima/webgl-noise simplex3D is a solid starting point, ported to GLSL ES 3.00 syntax.

## 7.5 Shader Loading Strategy

Shaders are stored as text files in the assets/shaders/ directory. At app startup or first use, each shader is compiled and linked into a program, then cached. Uniform locations are queried once and stored. The renderer sets uniforms each frame from the current PlanetVisualProfile.

# 8. UI / UX Design

__All visual design decisions are defined in DESIGN.md, which is the authoritative source for colours, typography, spacing, component specifications, and layout rules. __This section summarises the key points; in any conflict, DESIGN.md takes precedence.

## 8.1 Design Philosophy

The app should feel like looking through glass into a dark room full of quiet data. The UI recedes; the content — planets, stars, orbits — dominates. Planetarium mood, not cockpit. No Material Design components are used anywhere in the app. All UI is custom-built from primitive Compose elements (Box, Row, Column, Text, Canvas).

## 8.2 Visual Identity

- Dark-only: near-black background (#06080F) with cold blue undertone. No light mode.
- Typeface: Jost (geometric sans-serif, Futura family). Weights 200–500 only; no bold.
- Accent colour: warm gold (#B89660) for active/interactive elements only
- Classification colours are the only saturated elements — everything else is desaturated blue-grey
- Lightweight typography: thin and book weights dominate. Wide letter-spacing on uppercase labels for an engraved-instrument feel.
- Minimal chrome: cards barely distinguish from background, no visible toolbars or dividers

## 8.3 Navigation

Custom bottom navigation bar with four destinations: Catalog, System, Star Map, Settings. Solid panel bar with top border. System tab is disabled until a system is selected. Breadcrumb navigation in top bar for drill-down screens.

## 8.4 Component Library

All components are specified in detail in DESIGN.md Section 5. Key components: PlanetCard (catalog rows), FilterChip, SearchBar, ClassificationBadge, BottomNav, InfoPanel (overlay on GL views), ToggleSwitch, DisclaimerLabel, RegenerateButton. Each has exact dimensions, colours, radii, and interaction states defined.

## 8.5 GL View Integration

Each 3D screen uses a Box layout: GLSurfaceView fills the container via AndroidView, Compose UI overlays on top. The GL clear colour matches the app background (#06080F) for seamless integration. Touch events go to GL by default; Compose overlay elements consume their own touches. See DESIGN.md Section 6 for full details.

## 8.6 Disclaimer

Every procedural view (planet globe, surface) displays a persistent semi-transparent label anchored bottom-left: “Speculative visualisation — not based on direct observation.” Does not appear on catalog or star map.

## 8.7 Performance Targets

- Catalog: < 100ms filter/sort on Room queries over 5,700 rows
- Star map: 60fps with ~4,000 star points on Pixel 6a class hardware
- Planet globe: 60fps at device resolution on mid-range GPU (Adreno 619 tier)
- Surface scattering: 30fps acceptable given shader complexity
- Cold start to catalog visible: < 3 seconds (data from Room cache)

## 8.8 Offline Experience

Fully usable offline after first sync. All planet data in Room. Procedural visuals generated locally. Only data refresh requires network.

# 9. Project Structure

tadmor/

├── app/

│   ├── src/main/

│   │   ├── java/com/tadmor/app/

│   │   │   ├── di/                       ─ Hilt modules

│   │   │   ├── ui/

│   │   │   │   ├── theme/                ─ ExoColors, ExoType, ExoSpacing, ExoTheme, Jost font

│   │   │   │   ├── components/           ─ PlanetCard, FilterChip, SearchBar, BottomNav, etc.

│   │   │   │   ├── navigation/           ─ NavGraph, routes

│   │   │   │   ├── catalog/

│   │   │   │   │   ├── CatalogScreen.kt

│   │   │   │   │   ├── CatalogViewModel.kt

│   │   │   │   │   ├── FilterSheet.kt

│   │   │   │   │   └── PlanetListItem.kt

│   │   │   │   ├── starmap/

│   │   │   │   │   ├── StarMapScreen.kt

│   │   │   │   │   ├── StarMapViewModel.kt

│   │   │   │   │   └── StarMapRenderer.kt    ─ GLSurfaceView.Renderer

│   │   │   │   ├── system/

│   │   │   │   │   ├── SystemScreen.kt

│   │   │   │   │   ├── SystemViewModel.kt

│   │   │   │   │   └── SystemRenderer.kt

│   │   │   │   ├── planet/

│   │   │   │   │   ├── PlanetScreen.kt

│   │   │   │   │   ├── PlanetViewModel.kt

│   │   │   │   │   └── PlanetRenderer.kt

│   │   │   │   └── surface/

│   │   │   │       ├── SurfaceScreen.kt

│   │   │   │       ├── SurfaceViewModel.kt

│   │   │   │       └── SurfaceRenderer.kt

│   │   │   ├── gl/

│   │   │   │   ├── ShaderProgram.kt      ─ Compile, link, cache

│   │   │   │   ├── MeshBuilder.kt        ─ UV sphere, ring quad, terrain

│   │   │   │   ├── CameraController.kt   ─ Orbit, pan, zoom from touch

│   │   │   │   └── GLBridge.kt           ─ Compose ↔ GL thread comm

│   │   │   └── MainActivity.kt

│   │   ├── assets/

│   │   │   └── shaders/

│   │   │       ├── planet_surface.vert

│   │   │       ├── planet_surface.frag

│   │   │       ├── gas_giant_banding.frag

│   │   │       ├── atmosphere.vert

│   │   │       ├── atmosphere.frag

│   │   │       ├── noise3d.glsl

│   │   │       └── scattering.glsl

│   │   └── res/

│   │       └── font/                     ─ Jost ExtraLight, Light, Regular, Medium .ttf

├── domain/

│   └── src/main/java/com/tadmor/domain/

│       ├── model/

│       │   ├── Planet.kt

│       │   ├── Star.kt

│       │   ├── System.kt

│       │   ├── PlanetVisualProfile.kt

│       │   ├── ClassificationType.kt ─ Sealed class hierarchy

│       │   └── NoiseParams.kt

│       ├── classification/

│       │   ├── PlanetClassifier.kt   ─ Decision tree engine

│       │   └── ColorPalettes.kt      ─ Palettes per classification

│       ├── usecase/

│       │   ├── GetPlanetsUseCase.kt

│       │   ├── GetSystemUseCase.kt

│       │   ├── ClassifyPlanetUseCase.kt

│       │   └── SearchCatalogUseCase.kt

│       ├── repository/

│       │   └── PlanetRepository.kt   ─ Interface

│       └── util/

│           ├── HabitableZone.kt

│           ├── BlackBodyColor.kt

│           └── CoordinateConversion.kt

├── data/

│   └── src/main/java/com/tadmor/data/

│       ├── remote/

│       │   ├── TapApiService.kt      ─ Retrofit/Ktor interface

│       │   └── TapApiDto.kt          ─ API response data classes

│       ├── local/

│       │   ├── TadmorDatabase.kt     ─ Room database

│       │   ├── PlanetDao.kt

│       │   ├── StarDao.kt

│       │   ├── PlanetEntity.kt

│       │   └── StarEntity.kt

│       ├── repository/

│       │   └── PlanetRepositoryImpl.kt

│       ├── mapper/

│       │   ├── DtoToEntityMapper.kt

│       │   └── EntityToDomainMapper.kt

│       └── sync/

│           └── DataSyncWorker.kt     ─ WorkManager worker

├── build.gradle.kts

├── settings.gradle.kts

├── gradle/libs.versions.toml

├── SPEC.md                       ─ This document (converted to markdown)

├── DESIGN.md                     ─ Authoritative visual design specification

└── CLAUDE.md                     ─ Accumulated decisions and conventions log

# 10. Development Sequence

Build order, each phase producing a working increment:

## Phase 1: Project Scaffold + Data Layer

- Create multi-module Gradle project (app, domain, data)
- Configure Hilt, Room, Retrofit/Ktor, Kotlinx Serialization
- Define Room entities, DAOs, and database
- Implement TAP API service and DTOs
- Implement data mappers and repository
- Implement DataSyncWorker with WorkManager
- Verify: log all ~5,700 planets to Logcat with clean field names

*Deliverable: app that fetches, caches, and serves live exoplanet data from Room.*

## Phase 1.5: Design System + Reference Screen

- Implement ExoColors.kt, ExoType.kt, ExoSpacing.kt, ExoTheme.kt as defined in DESIGN.md
- Add Jost font files (weights 200, 300, 400, 500) to res/font/ and define JostFontFamily.kt
- Build all reusable components: PlanetCard, FilterChip, SearchBar, ClassificationBadge, BottomNav, ToggleSwitch, DisclaimerLabel, RegenerateButton
- Build a test screen rendering three planet cards with sample data to validate visual fidelity against the mockup
- Review test screen on device; iterate until it matches the design spec exactly
- This test screen becomes the visual reference for all subsequent phases

*Deliverable: complete design system and validated reference screen. No subsequent screen may deviate from this foundation.*

## Phase 2: Catalog UI

- Build CatalogScreen with LazyColumn
- Implement search bar and filter bottom sheet
- Implement sorting by multiple columns
- Build expandable detail cards for each planet
- Add data completeness indicator
- Add summary stats bar
- Pull-to-refresh

*Deliverable: fully browsable exoplanet catalog on device.*

## Phase 3: OpenGL Foundation

- Build ShaderProgram utility (compile, link, cache, uniform management)
- Build MeshBuilder (UV sphere, line loops, quads, point arrays)
- Build CameraController (orbit, zoom, pan from touch events)
- Build GLBridge for Compose-to-GL thread communication
- Test: render a simple coloured sphere in a GLSurfaceView embedded in Compose

*Deliverable: GL infrastructure ready for all 3D views.*

## Phase 4: Star Map

- Implement coordinate conversion (RA/Dec/dist → Cartesian)
- Implement black-body colour lookup
- Build StarMapRenderer: instanced star points, colour, size
- Add touch interaction: orbit, zoom, tap-to-select
- Add Compose overlay: tooltip on selected star, navigate to system
- Add distance rings and optional grid

*Deliverable: interactive 3D star map of all exoplanet host stars.*

## Phase 5: System Orbital View

- Build orbit ellipse geometry from Keplerian elements
- Animate orbital positions (time-scaled)
- Implement habitable zone annulus (Kopparapu boundaries)
- Log/linear scale toggle
- Host star rendering, planet sizing, labels
- Time controls (play/pause/speed)

*Deliverable: animated orbital diagram for any system.*

## Phase 6: Classification Engine

- Implement full decision tree in domain module (pure Kotlin)
- Define all ClassificationType sealed classes
- Define colour palettes for all types
- Unit tests against known planets: Earth, Jupiter, Mercury, Venus, TRAPPIST-1 system, 55 Cancri e, HD 209458 b
- Generate PlanetVisualProfile for every planet in the database

*Deliverable: every planet classified with a visual profile.*

## Phase 7: Planet Visualisation

- Implement noise3d.glsl (3D simplex noise + fBm)
- Implement planet surface shader with palette mapping
- Gas giant variant: latitude banding, storms
- Rocky world variant: terrain + craters
- Ice world variant: Voronoi fractures
- Lava world: crack glow overlay
- Atmosphere shell with Rayleigh scattering
- Cloud layer with animated drift
- Ring system rendering
- Regenerate button (seed re-roll)

*Deliverable: every planet renders as a unique procedural globe.*

## Phase 8: Surface View (Stretch)

- Local terrain from globe noise at high resolution
- Sky dome with Rayleigh scattering
- Host star at correct angular size and colour
- Day/night or fixed position for tidal lock
- Horizon distance from planet radius

*Deliverable: stand on any planet and see an alien sky.*

## Phase 9: Polish

- Shared element transitions between screens
- Loading states, error handling, empty states
- Deep link routing (share a link to a specific planet)
- Performance profiling: GPU overdraw, shader complexity, Room query time
- ProGuard/R8 optimisation for release
- Accessibility: TalkBack labels, contrast ratios
- Disclaimer placement verification

# 11. Notes on LLM-Assisted Development

This project is intended to be built with significant LLM assistance. Some practical considerations:

## 11.1 Where LLMs Are Strong

- Kotlin boilerplate: Room entities, DAOs, Hilt modules, Retrofit interfaces, data mappers — this is rote work an LLM handles well
- Compose UI: LazyColumn layouts, navigation, custom components from primitives — well-represented in training data. However, the custom design system means you must always verify output against DESIGN.md rather than accepting Material defaults
- The classification engine: pure logic, well-specified decision tree, easy to test and iterate
- Coordinate conversion, black-body calculations, habitable zone math — well-documented algorithms

## 11.2 Where LLMs Are Weak

- __Custom design system: __LLMs default to Material Design. Every Compose screen must be reviewed against DESIGN.md. Watch for: Material component imports, hardcoded colours, Roboto font references, default elevation/shadow, and Material-style shape/corner treatments. Reject any of these immediately.
- __GLSL shaders: __LLMs frequently produce shaders that compile but have subtle visual errors — wrong coordinate spaces, inverted normals, incorrect noise scaling. Plan to debug shaders visually, not just by reading code.
- __OpenGL ES state management: __Forgetting to unbind buffers, wrong blend modes, incorrect depth test configuration — these produce hard-to-diagnose visual artefacts. Be very explicit in prompts about GL state.
- __Compose + GL interop: __The AndroidView bridge for GLSurfaceView is not well-covered in LLM training data. Expect to iterate on touch handling and lifecycle management.
- __Performance optimisation: __LLMs tend to produce functionally correct but unoptimised GL code. Instancing, buffer reuse, and draw call batching will likely need manual tuning.

## 11.3 Recommended Workflow

1. Feed the relevant section of this spec AND DESIGN.md as context for each phase
2. For any UI phase: always include DESIGN.md Sections 1–5 and Section 10 (implementation rules) in the prompt context
3. For shader work: start with the pseudocode from Section 7, ask the LLM to produce complete GLSL, then test visually immediately — do not batch multiple shaders before testing
4. Keep the classification engine in a separate pure Kotlin module so it can be unit tested independently of Android
5. For each GL view: get a minimal working render first (solid colour sphere, static points), then add complexity incrementally
6. Commit frequently — GL bugs can cascade, and you’ll want clean rollback points
7. Maintain CLAUDE.md in the project root as a living log of decisions, conventions, and corrections

