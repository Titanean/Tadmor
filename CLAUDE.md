# Tadmor — Claude Code Rules

## Standing Rules

1. **No Material Design components anywhere.** No `Button()`, `Card()`, `Chip()`, `BottomNavigation()`, `TopAppBar()`, `ModalBottomSheet()`, `FloatingActionButton()`, or any other MDC composable. All UI built from `Box`, `Row`, `Column`, `Text`, `Canvas`, and `Modifier`.

2. **No hardcoded colours, text styles, or spacing.** Always reference `ExoColors`, `ExoType`, `ExoSpacing` via `ExoTheme`. If a token doesn't exist, propose adding it — never invent one inline.

3. **Jost is the only typeface.** Weights 200 (ExtraLight), 300 (Light), 400 (Regular), 500 (Medium) only. No bold. Never use `FontWeight.Bold` or any weight above 500. Never fall back to Roboto or system default.

4. **Dark theme only.** No light mode. No dynamic theming or light/dark switching.

5. **No icon libraries.** No Material Icons, Lucide, or any third-party icon library. All icons are custom SVG/vector drawables, stroke-based (1.2dp stroke for 20dp icons).

6. **Package structure:**
   - `com.tadmor.app` — Presentation layer (app module)
   - `com.tadmor.domain` — Domain layer (domain module, pure Kotlin, no Android dependencies)
   - `com.tadmor.data` — Data layer (data module)

## Architecture

- MVVM + Clean Architecture with three Gradle modules: `app`, `domain`, `data`
- ViewModel + StateFlow + Use Cases
- Hilt for dependency injection
- Room for local persistence
- OkHttp for HTTP (replaced Ktor — see decisions log)
- Kotlinx Serialization for JSON
- WorkManager for background sync
- OpenGL ES 3.0 for all 3D rendering, embedded in Compose via `AndroidView`

## Design System References

- Colours: DESIGN.md Section 2 → `ExoColors.kt`
- Typography: DESIGN.md Section 3 → `ExoType.kt` (Jost font family)
- Spacing: DESIGN.md Section 4 → `ExoSpacing.kt`
- Components: DESIGN.md Section 5
- Theme wiring: `CompositionLocalProvider` via `ExoTheme.kt`

## Touch Feedback Conventions

All interactive elements use one of three built-in touch feedback patterns. **Do not** add Compose's default ripple (`indication = rememberRipple()` or the `indication` default), invent new feedback shapes, or stack multiple patterns on the same element.

1. **Ripple** — `Modifier.touchRipple(...)` / `Modifier.touchRippleDecoration(...)` in [TouchRipple.kt](app/src/main/java/com/tadmor/app/ui/components/TouchRipple.kt). Use for primary action buttons (refresh, filter-menu, close), search bars, and tappable cards inside a scrollable list. The ripple originates at the touch point, expands fast-then-slow (`LinearOutSlowInEasing`, 420 ms) to the far corner, and fades (`LinearEasing`, 260 ms).
   - **`touchRipple`** is consuming and takes an `onClick`. Use for standalone buttons and tappable cards.
   - **`touchRippleDecoration`** watches events at the Initial pointer pass *without consuming*, so child pointer handlers (text-field focus, inline dismiss buttons) still fire. Use for containers whose children own the interaction — e.g. `ExoSearchBar` wrapping its `BasicTextField`.
   - Colour: `Color.White` at 18–22% alpha on dark buttons; `Color.White` at ~10% alpha on large card surfaces (a brighter wash on a 100 dp+ card reads as washed-out); `colors.background` at 28% on light accent-gold surfaces.
   - **Scroll-aware spawn** — both modifiers wait for `awaitTouchSlopOrCancellation` to confirm the gesture is a tap (released without crossing touch slop) before spawning the ripple. A scroll/swipe never produces a ripple, even on the first touch frame. The `onTouchSlopReached` lambda is left empty so the parent scrollable can claim the drag normally. Trade-off: the ripple appears at release time of a confirmed tap, so quick taps have a slight delay (no ripple-grows-during-hold feedback).
   - `touchRipple` yields one frame (`withFrameNanos { }`) before firing `onClick` so the ripple's first paint lands before any heavy recomposition. For child-handled clicks inside a `touchRippleDecoration` container, wrap the handler in `scope.launch { deferredClick { ... } }` to get the same guarantee.
   - `touchRipple` accepts `enabled = false` — the modifier chain shape stays constant across the toggle (`pointerInput(enabled)` is always present, just no-ops when disabled), so an in-flight ripple's `Animatable` state and animation coroutines survive the transition (e.g. refresh button flipping to spinning state on click, or the search-X clearing the query and disabling its own hitbox).

2. **Push** — `Modifier.pushOnPress(interactionSource)` in [PushOnPress.kt](app/src/main/java/com/tadmor/app/ui/components/PushOnPress.kt). Use for selection toggles and segmented pill rows (filter-sheet `ToggleChip`, settings `OptionRow`). Scales 1.0 → 0.94 → 1.0 across the press/release (90 ms in, 160 ms out, `FastOutSlowInEasing`). The release always awaits the press-in's completion, so even sub-frame taps show the full animation. Pair with `clickable(indication = null, interactionSource = <same source>)`.

3. **Radial fill** — state-on-state transition for toggle pills crossing between selected and unselected (filter-sheet `ToggleChip`). A `accentGoldSubtle` circle grows from the pill centre to the far corner over 260 ms and reverses on deselect. Must be rendered inside a `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }` with `drawCircle(..., blendMode = BlendMode.Src)` so the 7%-alpha fill composes directly against the parent bg rather than stacking on top of the `surfaceRaised` base.

**Pattern by element type:**

| Element | Feedback |
|---|---|
| Primary action button (refresh, filter, close) | Ripple (consuming) |
| Tappable card in a scrollable list (catalog `PlanetCard`, system `StarRow`, star-detail `SystemPlanetCard`) | Ripple (consuming), `Color.White` at `startAlpha = 0.10f` |
| Search bar (wraps a text field) | Ripple decoration (non-consuming) |
| Toggle pill (filter sheet) | Push + radial fill together |
| Segmented row pill (settings) | Push (wipe animation handles the state change) |

Disclosure indicators (dropdown chevrons, sort arrow) follow one shared motion: `Animatable` rotation, target `0°` / `-180°`, 260 ms `FastOutSlowInEasing`. When adding a new disclosure indicator, reuse `ChevronIcon` or match `SortArrow`'s pattern — do not hand-roll.

## Decisions Log

The full phase-by-phase record of architecture and implementation decisions lives in [DECISIONS.md](DECISIONS.md). Read it when you need to understand *why* something is the way it is — especially before reworking anything in the data layer, GL pipeline, visual profile engine, or navigation model. Standing rules and live contracts stay in this file; historical decisions stay there.

## System Tab Page Flow & Back Behavior

The System tab has three pages: SEARCH → DETAIL (star) → PLANET. Entry can come from the System tab's own search, the Catalog tab ("View in System"), or the Star Map tab ("View planet" from orbital view).

The Star Map tab has two modes: MAP (3D star field) → ORBITAL (3D orbital view for a selected star). "View system →" on a star tooltip enters ORBITAL mode. "View planet →" from ORBITAL cross-tab navigates to the System tab's PLANET page.

### System tab entry paths

| Origin | Entry point | `fromExternalTab` | `skippedStarDetail` | Enters at |
|---|---|---|---|---|
| System search | User taps a star result | `false` | `false` | DETAIL |
| Catalog | "View in System" on a planet card | `true` | `true` | PLANET |
| Star Map | "View planet" from orbital tooltip | `true` | `true` | PLANET |

### System tab hardware back button behavior

| Current page | Condition | Action | Destination |
|---|---|---|---|
| PLANET | `skippedStarDetail = true` | Reset + leave tab | Origin tab (Catalog or Star Map) |
| PLANET | `skippedStarDetail = false` | `onBackFromPlanet()` | DETAIL (star page) |
| DETAIL | `fromExternalTab = true` | Reset + leave tab | Origin tab |
| DETAIL | `fromExternalTab = false` | Reset to search | SEARCH |
| SEARCH | — | Not handled | System default |

### System tab UI back button behavior (the "< Star Name" button)

| Current page | Action | Destination |
|---|---|---|
| PLANET | `onBackFromPlanet()` | Always goes to DETAIL |
| DETAIL | `onBackFromDetail()` + leave if external | Origin tab or SEARCH |

### Star Map tab navigation

| Current mode | Action | Destination |
|---|---|---|
| MAP | Tap star → "View system →" | ORBITAL (within star map tab) |
| ORBITAL | Back / hardware back | MAP |
| ORBITAL | Tap planet → "View planet →" | System tab PLANET (cross-tab) |

### Implementation details

- `systemOriginTab` in `MainActivity` tracks which tab initiated navigation (CATALOG or STAR_MAP). The `onNavigateToCatalog` callback switches back to this tab.
- `fromExternalTab` (SystemViewModel): true when the user arrived from Catalog or Star Map. Controls whether DETAIL back leaves the System tab.
- `skippedStarDetail` (SystemViewModel): true when the user jumped directly to a planet (from Catalog "View in System" or Star Map "View planet"). Exposed as `fromCatalog` in `SystemUiState`. Controls whether PLANET hardware back skips the star page.
- `StarMapMode` (StarMapViewModel): `MAP` or `ORBITAL`. Orbital state is loaded via `ObserveSystemDetailUseCase` when entering ORBITAL mode.
- Navigation flags are reset in `resetToSearch()` and `onStarSelected()`.
