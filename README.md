<p align="center">
<img src="icon no background.svg" width="22%" /> 
</p>

# Tadmor

An exoplanet database and tracker with procedural visualisations.

Tadmor pulls the latest data from the NASA Exoplanet Archive — currently 6,000+ confirmed planets plus TESS / Kepler / K2 candidates — and renders each one as a 3D globe built procedurally from its measured parameters. The same physical inputs that drive the classifier (mass, radius, temperature, host spectral type, atmospheric composition, orbital geometry) drive the look of the planet, its host star, and the orbital diagram. Nothing is a photograph.

<p align="center">
  <img src="docs/screenshots/Screenshot 18.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 23.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 21.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 19.jpg" width="22%" />
</p>

## Features

- **Catalog** — browse the full planet list with search, multi-axis filters (composition, temperature, mass class, host spectral type, discovery method, data completeness), and sort. Per-planet 2D icons sketch the body at a glance.
- **Candidates** — separate tabs for confirmed planets, TESS / Kepler / K2 candidates, and false positives, with dispositions kept up to date by the sync.
- **Bookmarks** — save any planet; the catalog row gets a gold-edge highlight and a saved-only filter is one tap away. The app snapshots the planet's parameters at bookmark time.
- **Update tracking** — when a bookmarked planet's catalog parameters change after a sync (a refined mass measurement, a new temperature, a disposition flip), the card shows an "_n_ updated" badge and the updated fields are diffed against the snapshot.
- **Background sync** — WorkManager periodically pulls the latest data from the NASA Exoplanet Archive so the local catalog stays current without manual refresh.
- **System view** — every planet around a host star on a single page, with the host's spectral / luminosity context, an orbital diagram, and a system planet strip.
- **Star map** — a 3D point cloud of every catalogued host star, sized and tinted by spectral type and luminosity; tap a star to enter its system.
- **Planet detail** — a full-screen procedural 3D globe. Terrestrial worlds get composition-driven surface bakes with terrain, craters, polar caps, oceans, and lava; gas and ice giants get banded cloud decks with storm vortices and ring systems; everything is lit through a ray-marched atmosphere with per-planet Rayleigh / Mie / ozone / fog scattering. Brown dwarf and white dwarf host stars get their own surface and limb treatments distinct from main-sequence stars.

<p align="center">
  <img src="docs/screenshots/Screenshot 24.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 20.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 22.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 23.jpg" width="22%" />
</p>

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

## Notice

Tadmor was 100% vibe coded — every line of source was written by [Claude](https://www.anthropic.com/claude) (Anthropic's AI assistant) under direction and iteration from me. I designed the app, drove every architectural and visual decision, and tested every change on-device, but I did not hand-write the code myself.

## License

Apache 2.0 — see [`LICENSE`](LICENSE).

## Gallery

<p align="center">
  <img src="docs/screenshots/Screenshot 17.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 12.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 2.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 13.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 6.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 7.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 16.jpg" width="22%" />
  <img src="docs/screenshots/Screenshot 26.jpg" width="22%" />
</p>
