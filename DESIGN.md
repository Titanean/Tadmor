\# Tadmor — Design Specification



This document is the single source of truth for all visual decisions. Every screen, component, and interaction in the app must conform to these rules. Do not hardcode colours, spacing, or font sizes — always reference the design system tokens defined here.



\---



\## 1. Design Philosophy



The app should feel like looking through glass into a dark room full of quiet data. Nothing shouts. The UI recedes; the content — planets, stars, orbits — dominates. Think planetarium, not cockpit.



\*\*Core principles:\*\*



\- \*\*Dark-first\*\*: The background is near-black with a cold blue undertone. The app never uses light mode.

\- \*\*Lightweight typography\*\*: Prefer thin and book weights. Bold is used sparingly and only for emphasis, never for structure.

\- \*\*Muted colour\*\*: Colour carries information (classification, spectral type, temperature). It is never decorative. All colours are desaturated and low-opacity against the dark background.

\- \*\*Minimal chrome\*\*: No visible toolbars, dividers, or borders unless structurally necessary. Cards are barely distinguishable from the background.

\- \*\*Generous negative space\*\*: Let elements breathe. Density comes from the data itself, not from packing the layout.

\- \*\*Straight edges\*\*: Corner radii are kept to a minimum (4dp default). The aesthetic is sharp and technical — rounded, bubbly shapes are avoided. Only pill-shaped elements (filter chips, toggle tracks) use fully rounded corners; all cards, sheets, inputs, badges, and containers use 4dp.

\- \*\*No Material Design components\*\*: Do not use Material 3 buttons, cards, chips, bottom sheets, or any other MDC component. All components are custom-built to this spec. The only exception is using MaterialTheme for wiring the colour/type system through Compose — but the actual visual appearance must match this document, not Material defaults.



\---



\## 2. Colour System



\### 2.1 Core Palette



All colours are defined as hex values. Create a Kotlin object `ExoColors` that exposes these as `Color` constants.



| Token                  | Hex       | Usage                                      |

|------------------------|-----------|--------------------------------------------|

| `background`           | `#06080F` | App background, root surfaces              |

| `surfaceCard`          | `#0C0F18` | Card backgrounds                           |

| `surfaceBorder`        | `#121622` | Card borders (use at 50% opacity: `#12162280`) |

| `surfaceRaised`        | `#151929` | Elevated surfaces: filter chips, bottom sheets, modals |

| `surfaceInput`         | `#0E1220` | Text input fields, search bar background   |

| `divider`              | `#1A2035` | Thin dividers, borders on interactive elements |

| `textPrimary`          | `#C8D0DC` | Headings, planet names, primary content    |

| `textSecondary`        | `#97A1B3` | Data values, stat numbers, body text       |

| `textTertiary`         | `#6B7A8F` | Labels, subtitles, spectral types, hints   |

| `textMuted`            | `#556275` | Stat labels (MASS, RADIUS, etc.), disabled text |

| `accentGold`           | `#B89660` | Active nav items, active filter chips, interactive highlights |

| `accentGoldDim`        | `#8A7048` | Gold at reduced prominence                 |

| `accentGoldSubtle`     | `#B8966012` | Gold backgrounds for chips/badges (with alpha) |

| `accentGoldBorder`     | `#2A1F35` | Border for gold-accented elements          |



\### 2.2 Classification Badge Colours

Colours are mapped to the SpaceEngine-based classification system. Each badge shows the **bulk composition class** colour. Temperature is communicated via the text label, not the badge colour.

__Bulk Composition Colours:__

| Composition | Text Colour | Background (7% alpha) | Border (12% alpha) |
|-------------|-------------|----------------------|---------------------|
| Terra       | `#7AB89E`   | `#7AB89E12`          | `#7AB89E20`         |
| Neptune     | `#5A82B8`   | `#5A82B812`          | `#5A82B820`         |
| Jupiter     | `#C4886A`   | `#C4886A12`          | `#C4886A20`         |

__Temperature Class Colours (used in temperature filter chips and secondary labels):__

| Temperature | Colour    |
|-------------|-----------|
| Frigid      | `#9EB8D4` |
| Cold        | `#7A98B0` |
| Cool        | `#B89660` |
| Temperate   | `#7AB89E` |
| Warm        | `#C4B078` |
| Hot         | `#D46A4A` |
| Torrid      | `#D44A4A` |



\### 2.3 Spectral Type Star Colours



For the star map and system views. Derived from black-body approximation but capped for readability against the dark background.



| Spectral Class | Colour    |

|----------------|-----------|

| O              | `#9BB0FF` |

| B              | `#AABFFF` |

| A              | `#CAD7FF` |

| F              | `#F8F7FF` |

| G              | `#FFF4EA` |

| K              | `#FFD2A1` |

| M              | `#FFB56C` |

| L/T/Y          | `#C45030` |



\### 2.4 Colour Rules



\- Never use pure white (`#FFFFFF`) for text. The brightest text colour is `textPrimary` (`#C8D0DC`).

\- Never use pure black (`#000000`) for backgrounds. The darkest surface is `background` (`#06080F`).

\- Card borders are always `surfaceBorder` at 50% opacity. This keeps them barely visible.

\- Classification badge colours are the only saturated colours in the UI. Everything else is desaturated blue-grey.

\- The gold accent (`accentGold`) is reserved for active/selected interactive elements only: the current nav tab, an active filter, a toggle in its on state. Do not use it for text emphasis, headings, or decoration.



\---



\## 3. Typography



\### 3.1 Typeface



\*\*Jost\*\* — a geometric sans-serif in the Futura tradition. Bundle it in the app via `res/font/`.



Weights to include:

\- \*\*200 (ExtraLight)\*\*: Display headings only — the large "5,799 worlds" counter and similar hero text. Never used below 24sp.

\- \*\*300 (Light)\*\*: Body text, data values, planet names. The primary reading weight.

\- \*\*400 (Regular)\*\*: Labels, filter chips, badges, small text that needs clarity. Also used for uppercase stat labels.

\- \*\*500 (Medium)\*\*: Used very sparingly for emphasis within text. Never for headings.



\*\*Do not use weights 600, 700, 800, or 900.\*\* The app has no bold text in the conventional sense.



\### 3.2 Type Scale



All sizes in `sp` (scale-independent pixels). These are the only permitted sizes.



| Token            | Size | Weight | Letter Spacing | Usage                                        |

|------------------|------|--------|----------------|----------------------------------------------|

| `displayLarge`   | 28sp | 200    | -0.3sp         | Screen titles: "5,799 worlds"                |

| `displayMedium`  | 22sp | 200    | -0.2sp         | Section headers, planet name in detail view  |

| `titleMedium`    | 15sp | 300    | 0.3sp          | Planet name in card row                      |

| `bodyLarge`      | 14sp | 300    | 0.2sp          | General body text, descriptions              |

| `bodyMedium`     | 13sp | 300    | 0.0sp          | Data values: "0.69 M⊕", "251 K"             |

| `labelLarge`     | 11sp | 400    | 0.8sp          | Filter chips, subtitle text, spectral types  |

| `labelMedium`    | 10sp | 400    | 1.0sp          | Classification badges                        |

| `labelSmall`     | 9.5sp| 400    | 1.5sp          | Stat column headers: "MASS", "RADIUS"        |

| `navLabel`       | 9sp  | 400    | 2.0sp          | Bottom navigation labels                     |



\### 3.3 Typography Rules



\- `labelSmall` and `navLabel` are always uppercase with the specified letter spacing. This gives the engraved-instrument-panel feel.

\- `labelMedium` (badges) is also uppercase.

\- `displayLarge` and `displayMedium` are never uppercase.

\- Planet names use `titleMedium` in lists and `displayMedium` in detail views.

\- Numeric data values always use `bodyMedium` with the unit symbol at 9sp (slightly smaller subscript-style).

\- When a data value is missing (null), display an em dash `—` in `textTertiary` colour at `bodyMedium` size, italic.

\- Never use italic except for the null-data em dash.



\---



\## 4. Spacing System



All spacing values in `dp`. Use only these values — no arbitrary numbers.



| Token   | Value | Usage                                                    |

|---------|-------|----------------------------------------------------------|

| `xs`    | 3dp   | Gap between stacked cards                                |

| `sm`    | 8dp   | Gap between filter chips, inner padding micro-elements   |

| `md`    | 12dp  | Stat column gap, inner component spacing                 |

| `lg`    | 16dp  | Card internal padding (horizontal), horizontal screen padding |

| `xl`    | 18dp  | Card internal padding (combined with lg for asymmetric)  |

| `2xl`   | 20dp  | Gap between stat row and content above; filter-to-card gap |

| `3xl`   | 24dp  | Screen horizontal padding, header section padding        |

| `4xl`   | 32dp  | Bottom nav bottom padding (accounts for gesture bar)     |



\### 4.1 Screen Layout



\- Horizontal screen padding: `3xl` (24dp) for header content, `lg` (16dp) for the card list area (cards extend closer to edges).

\- Card list: vertical gap of `xs` (3dp) between cards. This near-zero gap makes the list feel like a continuous column with hairline breaks, not a pile of separate cards.

\- Header to card list gap: `2xl` (20dp).

\- Bottom of scrollable content: add `80dp` padding to clear the bottom navigation gradient.



\### 4.2 Card Internal Layout



\- Card padding: `lg` (16dp) vertical, `xl` (18dp) horizontal.

\- Planet thumbnail to text content gap: `14dp`.

\- Planet name to subtitle gap: `3dp`.

\- Subtitle to stat row gap: `md` (12dp).

\- Stat column internal gap (label to value): `2dp`.

\- Stat column horizontal gap: `2xl` (20dp).



\---



\## 5. Component Specifications



\### 5.1 Planet Card (Catalog Row)



The primary UI element. A horizontal card showing a planet thumbnail, name, star info, classification badge, and key stats.



\*\*Container:\*\*

\- Background: `surfaceCard`

\- Border: 1dp, `surfaceBorder` at 50% opacity

\- Corner radius: `4dp` (sharp-edged, per design reference)

\- Padding: 16dp vertical, 18dp horizontal



\*\*Planet Thumbnail:\*\*

\- Size: 52dp × 52dp circle

\- Eventually replaced by a downscaled procedural render. During development, use a radial gradient placeholder coloured by classification type.

\- 1dp inner border at `#FFFFFF10` (white at 6% opacity) for subtle definition against the dark card.



\*\*Text Layout:\*\*

\- Top row: planet name (`titleMedium`, `textPrimary`) left-aligned, classification badge right-aligned.

\- Second row: spectral type + distance (`labelLarge`, `textTertiary`), format: "M8V · 12.4 pc"

\- Stat row: 4 columns showing MASS, RADIUS, TEMP, PERIOD. Each column has the label above (`labelSmall`, `textMuted`, uppercase) and value below (`bodyMedium`, `textSecondary`).



\*\*Classification Badge:\*\*

\- Background: classification colour at 12% opacity (see Section 2.2)

\- Border: 1dp, classification colour at 20% opacity

\- Corner radius: `4dp`

\- Padding: 3dp vertical, 10dp horizontal

\- Text: `labelMedium`, classification colour at full opacity, uppercase



\*\*Tap behaviour:\*\* Expand to show detail panel (not a new screen). The card grows downward to reveal additional parameters. Detail section appears below the stat row, separated by a 1dp `divider` line with 12dp vertical padding above and below.



\### 5.2 Filter System

__Filter button:__ Replaces the old flat chip row. A single pill-shaped button labelled "Filters" sits to the right of the search bar.

\- Background: `surfaceRaised`
\- Border: 1dp, `divider`
\- Corner radius: `20dp` (pill shape)
\- Padding: 6dp vertical, 14dp horizontal
\- Text: `labelLarge`, `textSecondary`
\- Active state (when any filter is applied): border `accentGoldBorder`, text `accentGold`, small count badge

__Active filter chips:__ Displayed in a horizontally scrollable row below the search bar when filters are active. Each chip shows the filter value and an × dismiss icon.

\- Background: `accentGoldSubtle`
\- Border: 1dp, `accentGoldBorder`
\- Corner radius: `20dp` (pill shape)
\- Padding: 6dp vertical, 10dp horizontal
\- Text: `labelLarge`, `accentGold`
\- Dismiss icon: 10dp × glyph, `accentGoldDim`, right side of chip
\- "Clear all" chip: `surfaceRaised` background, `textTertiary` text, appears last in row
\- Horizontal scroll if chips exceed screen width, no wrapping

__Filter bottom sheet:__ Opens from the filter button. Full-width, slides up from bottom.

\- Background: `surfaceCard`
\- Corner radius: 4dp top corners
\- Drag handle: 32dp × 4dp, `divider`, centred, 8dp top margin
\- Section headers: `labelSmall`, `textTertiary`, uppercase, 0.8sp tracking
\- Filter group rows: `bodyLarge`, `textPrimary`, chevron icon right-aligned
\- Expandable: tapping a group expands it inline to show its sub-options
\- Sub-option chips: same style as active filter chips but toggle on/off
\- Range sliders: dual-thumb, track `divider`, active range `accentGold`, thumbs 16dp circles `accentGold`
\- Apply button: full-width, 44dp height, `accentGold` background, `background` text, `bodyLarge` weight 500



\### 5.3 Search Bar



\- Background: `surfaceInput`

\- Border: 1dp, `divider`; focus state: 1dp, `accentGold` at 40% opacity

\- Corner radius: `4dp`

\- Height: 44dp

\- Icon: 16dp magnifying glass SVG, `textTertiary` colour

\- Placeholder text: `bodyLarge`, `textMuted`, "Search planets, stars..."

\- Input text: `bodyLarge`, `textPrimary`



\### 5.4 Bottom Navigation



\- Solid panel bar: `surfaceCard` background with 1dp `divider` border on top edge

\- 4 items: Catalog, System, Star Map, Settings

\- Each item: icon (20dp SVG) above label (`navLabel`, uppercase)

\- Active: icon stroke and label colour = `accentGold`

\- Inactive: icon stroke and label colour = `textMuted`

\- System tab: disabled (icon and label at `textMuted` with 50% opacity) until a system is selected

\- Horizontal distribution: evenly spaced with 40dp padding from screen edges

\- Padding: `md` (12dp) top, `xxl` (20dp) bottom to clear the system gesture bar



\### 5.5 Section Header (used in detail views and overlays)



\- Label: `labelSmall`, `textMuted`, uppercase

\- Value or title: `displayMedium`, `textPrimary`

\- Spacing: 4dp from label to value



\### 5.6 Disclaimer Label



\- Anchored: bottom-left corner of GL views, 16dp from edges

\- Background: `background` at 60% opacity

\- Corner radius: `4dp`

\- Padding: 6dp vertical, 12dp horizontal

\- Text: `labelLarge`, `textTertiary`

\- Content: "Speculative visualisation — not based on direct observation"

\- Always visible on planet globe and surface view screens. Never on catalog or star map.



\### 5.7 Info Panel Overlay (on GL views)



For showing planet data alongside the 3D render:



\- Anchored to the right edge or bottom of screen depending on orientation

\- Background: `background` at 80% opacity with 16dp blur (if performant; otherwise solid at 90% opacity)

\- Corner radius: `4dp` on the side facing the GL view

\- Padding: `3xl` (24dp)

\- Width: 280dp on landscape; full-width sheet on portrait (slides up from bottom)

\- Drag handle on portrait: 40dp × 4dp pill, `divider` colour, centred, 8dp from top



\### 5.8 Toggle Controls (atmosphere on/off, clouds on/off)



\- Track: 40dp × 22dp rounded rectangle, `surfaceRaised`, 1dp border `divider`

\- Thumb: 18dp circle

\- Off state: thumb colour `textMuted`, positioned left

\- On state: thumb colour `accentGold`, track background `accentGoldSubtle`, positioned right

\- Label: `labelLarge`, `textSecondary`, positioned to the left of the track



\### 5.9 Regenerate Button



\- Circular button, 44dp diameter

\- Background: `surfaceRaised`

\- Border: 1dp, `divider`

\- Icon: 20dp refresh/dice SVG, `textSecondary`

\- Tap: icon briefly rotates 360° as the seed re-rolls

\- Active (pressed): border `accentGold` at 40%



\---



\## 6. GL View Integration



\### 6.1 Layout Structure



Every screen with a 3D view uses this structure:



```

Box(fillMaxSize) {

&#x20;   // Layer 1: GLSurfaceView fills the entire screen

&#x20;   AndroidView(GLSurfaceView)

&#x20;   

&#x20;   // Layer 2: Compose UI overlaid on top

&#x20;   // Top: back button + breadcrumb (if not catalog/star map)

&#x20;   // Bottom-left: disclaimer (if planet/surface view)

&#x20;   // Right or bottom: info panel overlay

&#x20;   // Bottom-centre: toggle controls

}

```



\### 6.2 Background Continuity



The GL view clears to `background` colour (`#06080F` = RGB 6, 8, 15). This ensures the GL canvas and the Compose overlay backgrounds are identical — no visible seam between 3D content and UI.



\### 6.3 Touch Priority



\- Dragging/pinching on the GL view: always handled by the GL camera controller.

\- Tapping on a Compose overlay element: handled by Compose (it sits above in the Z order).

\- The info panel and controls must consume touch events so they don't pass through to the GL view.



\---



\## 7. Animation



\### 7.1 Screen Transitions



\- Catalog → System: crossfade over 300ms. The selected star/system remains visually anchored if possible (shared element transition on the planet thumbnail or star name).

\- System → Planet: zoom-in transition on the selected planet. The planet orb scales up from its position in the orbital view to fill the globe view. 400ms, ease-out.

\- Any back navigation: reverse of the forward transition, 250ms.

\- If shared element transitions prove too complex, fall back to a simple crossfade.



\### 7.2 Micro-interactions



\- Card expand/collapse: height animation 200ms, ease-in-out. Content fades in at 150ms delay.

\- Filter chip activate/deactivate: background and border colour crossfade 150ms.

\- Filter bottom sheet: slide up 300ms, ease-out. Dismiss: slide down 200ms, ease-in.

\- Active filter chip appear/dismiss: scale + fade 150ms.

\- Tab switch: icon and label colour crossfade 150ms. No indicator bar, no slide animation.

\- Regenerate button: icon rotates 360° over 400ms, ease-in-out.

\- Pull to refresh: subtle gold arc indicator at top of screen. Not a Material pull-to-refresh.



\### 7.3 GL View Animations



\- Star map fly-to: camera interpolates position and target over 800ms with ease-in-out.

\- Orbital view: planets orbit continuously. Time scale adjustable via a minimal slider.

\- Planet globe: slow continuous rotation (1 revolution per 30 seconds default). User drag overrides.

\- Surface view: if not tidally locked, slow day/night cycle. Time-of-day slider available.



\---



\## 8. Iconography



\- All icons are custom SVG, not from any icon library.

\- Stroke-based, not filled. Stroke width: 1.2dp for 20dp icons.

\- Colour follows the element state (active = `accentGold`, inactive = `textMuted`).

\- Icon sizes: 16dp for inline/search, 20dp for navigation and controls, 24dp for prominent actions.

\- No emoji anywhere in the app.



\### 8.1 Navigation Icons



\*\*Catalog\*\*: Simplified grid/table — a rounded rectangle with a horizontal divider line.



\*\*Stars\*\*: Concentric circles (representing distance rings) with 2–3 small dots (stars).



\*\*System\*\*: Central circle (star) with an angled elliptical orbit and a small dot on the orbit.



\*\*Planet\*\*: Globe with a meridian line and an equator line.



\### 8.2 Action Icons



\*\*Search\*\*: Circle + diagonal line (magnifying glass). 16dp, `textTertiary`.



\*\*Filter\*\*: Three horizontal lines of decreasing width, or a funnel shape. 16dp.



\*\*Regenerate\*\*: Circular arrow (refresh) or a die face. 20dp.



\*\*Back\*\*: Simple chevron left. 20dp.



\*\*Expand/collapse\*\*: Chevron down/up. 16dp.



\---



\## 9. Specific Screen Layouts



\### 9.1 Catalog Screen



\*\*Top section (fixed):\*\*

\- Status bar clear zone

\- "Catalog" label: `labelSmall`, `textTertiary`, uppercase, 3px letter spacing

\- World count: `displayLarge`, `textPrimary` (e.g., "5,799 worlds"). Updates to reflect active filter count (e.g., "1,204 worlds"). Refresh/sync button (RegenerateButton) right-aligned on same row

\- Search bar row: search bar fills available width, "Filters" button to the right (8dp gap)

\- Active filter chip row: horizontally scrollable, 8dp gap between chips. Hidden when no filters active. Includes "Clear all" chip at end



\*\*Sort control:\*\*

\- Small sort label below filter chips: `labelSmall`, `textTertiary`, e.g., "Sorted by: Discovery date ↓"

\- Tapping opens a dropdown with sort options: Discovery date, Name, Mass, Radius, Temperature, Distance, Period, Planets in system

\- Each option toggleable ascending/descending



\*\*Scrollable content:\*\*

\- LazyColumn of planet cards, default sorted by discovery date descending

\- 3dp gap between cards

\- 16dp horizontal padding



\*\*Bottom:\*\*

\- Navigation bar (solid `surfaceCard` panel with `divider` top border)



\### 9.2 Star Map Screen



\- Full-screen GL view

\- Top-left: back arrow (if navigated from elsewhere) or screen label "Stars" in `labelSmall`

\- Bottom: navigation bar

\- On star tap: small tooltip card appears near the tapped position showing star name, spectral type, distance, planet count. Card style matches `surfaceCard` with same border treatment. Auto-dismisses on tap elsewhere. Contains a "View system →" text link in `accentGold`.



\### 9.3 System Screen



\- Full-screen GL view (orbital diagram)

\- Top: breadcrumb "← TRAPPIST-1" in `labelLarge`, `textTertiary`, with the star name in `textSecondary`

\- Bottom-right: time controls — play/pause button + speed label

\- On planet tap: small info card appears (same style as star tooltip). Contains "View planet →" link.

\- Bottom: navigation bar



\### 9.4 Planet Screen



\- Full-screen GL view (rotating globe)

\- Disclaimer: bottom-left

\- Info panel: right side (landscape) or bottom sheet (portrait)

\- Toggle controls (atmosphere, clouds): above the bottom nav, horizontally centred

\- Regenerate button: positioned near the toggle controls

\- Panel content: planet name, classification badge, all known parameters in a two-column label-value layout using `labelSmall` for labels and `bodyMedium` for values. Classification reasoning shown as a short paragraph in `bodyLarge`, `textTertiary`.



\### 9.5 Surface Screen



\- Full-screen GL view (surface perspective)

\- Disclaimer: bottom-left

\- Host star info: top-right corner, small label showing star angular diameter and type

\- Time-of-day slider (if applicable): bottom-centre, minimal track style matching the toggle track design

\- Back to globe: floating circular button, top-left, same style as regenerate button



\---



\## 10. Implementation Notes for Claude Code



\### 10.1 Mandatory Rules



1\. \*\*Never use Material Design components.\*\* No `Button()`, `Card()`, `Chip()`, `BottomNavigation()`, `TopAppBar()`, `ModalBottomSheet()`, `FloatingActionButton()`, or any other MDC composable. Build everything from `Box`, `Row`, `Column`, `Text`, `Canvas`, and `Modifier`.

2\. \*\*Never hardcode a colour.\*\* Always reference `ExoColors.tokenName`. If a colour isn't in the system, propose adding it — don't invent one inline.

3\. \*\*Never hardcode a text style.\*\* Always reference `ExoType.tokenName`. If a size/weight combination isn't in the system, propose adding it.

4\. \*\*Never hardcode a spacing value.\*\* Always reference `ExoSpacing.tokenName`. If a spacing isn't in the system, propose adding it.

5\. \*\*Never use `fontWeight = FontWeight.Bold`\*\* or any weight above Medium (500).

6\. \*\*The app has no light mode.\*\* Do not implement dynamic theming or light/dark switching.

7\. \*\*Jost is the only typeface.\*\* Do not fall back to Roboto or system default for any element.

8\. \*\*Icons are custom SVG.\*\* Do not import Material Icons, Lucide, or any icon library.



\### 10.2 File Organisation



```

ui/

├── theme/

│   ├── ExoColors.kt        — All colour constants

│   ├── ExoType.kt           — All text style definitions (using Jost)  

│   ├── ExoSpacing.kt        — All spacing constants

│   ├── ExoTheme.kt          — CompositionLocal provider wrapping all of the above

│   └── JostFontFamily.kt    — Font family definition loading from res/font/

├── components/

│   ├── PlanetCard.kt        — The catalog row card

│   ├── FilterChip.kt        — Custom filter chip

│   ├── SearchBar.kt         — Custom search bar

│   ├── ClassificationBadge.kt

│   ├── BottomNav.kt         — Custom bottom navigation

│   ├── InfoPanel.kt         — Overlay info panel for GL views

│   ├── ToggleSwitch.kt      — Custom toggle

│   ├── DisclaimerLabel.kt   — Speculative visualisation label

│   ├── StarTooltip.kt       — Tap tooltip for star map

│   └── RegenerateButton.kt

```



\### 10.3 Theme Wiring



Use `CompositionLocalProvider` to make `ExoColors`, `ExoType`, and `ExoSpacing` available throughout the tree:



```kotlin

// Usage in any composable:

val colors = ExoTheme.colors

val type = ExoTheme.type

val spacing = ExoTheme.spacing



Text(

&#x20;   text = "5,799 worlds",

&#x20;   style = type.displayLarge,

&#x20;   color = colors.textPrimary

)

```



This pattern ensures all visual decisions flow from the design system, and any future changes propagate everywhere.

