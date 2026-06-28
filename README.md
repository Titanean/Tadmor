# Tadmor

An exoplanet database and tracker with procedural visualisations.

Tadmor pulls the latest data from the NASA Exoplanet Archive — currently 6,000+ confirmed planets plus TESS / Kepler / K2 candidates — and renders each one as a 3D globe built procedurally from its measured parameters. The same physical inputs that drive the classifier (mass, radius, temperature, host spectral type, atmospheric composition, orbital geometry) drive the look of the planet, its host star, and the orbital diagram. Nothing is a photograph.

## Features

- **Catalog** — browse the full planet list with sort, search, filter, and bookmark; per-planet 2D icons sketch the body at a glance.
- **System detail** — see all planets around a host star on a single page, with the host's spectral / luminosity context.
- **Star map** — a 3D point cloud of every catalogued host star, sized and tinted by spectral type and luminosity.
- **Planet detail** — a full-screen procedural globe with atmosphere ray-marching, ring photometry, cloud overlays, and per-planet surface bakes. Hapke / opposition surge for airless rocky bodies; Chandrasekhar single-scattering for rings; chevron jets for Venus-class and small sub-Neptune cloud decks; brown / L dwarf surface variants; circumbinary lighting for the ~30 catalogued circumbinary planets.

## Built with

- Android (minSdk 26, targetSdk 35) + Kotlin
- Jetpack Compose for UI, with a custom design system (Jost typeface, no Material Design components)
- OpenGL ES 3.0 for all 3D rendering
- Room (local catalog cache) + OkHttp (TAP API client) + WorkManager (background sync)
- Hilt for DI

Architecture is MVVM + Clean across three Gradle modules: `app` (presentation), `domain` (pure Kotlin), `data`.

## Building

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio Iguana or later. No additional setup beyond a standard Android SDK install.

## Data

All exoplanet, star, and candidate data come from the [NASA Exoplanet Archive](https://exoplanetarchive.ipac.caltech.edu/). Tables used: `pscomppars`, `stellarhosts`, `toi`, `koi`, `k2pandc`.

## Project documentation

- [`SPEC.md`](SPEC.md) — feature specification
- [`DESIGN.md`](DESIGN.md) — design system (colours, typography, spacing, components)
- [`DECISIONS.md`](DECISIONS.md) — phase-by-phase log of architecture and rendering decisions
- [`CLAUDE.md`](CLAUDE.md) — coding rules and contributing notes

## License

Apache 2.0 — see [`LICENSE`](LICENSE).
