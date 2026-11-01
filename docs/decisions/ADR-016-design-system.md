# ADR-016: Tidepool, Settle's design system

- Status: accepted
- Date: 2026-11-01

## Context
- **Origin.** Claude Design offered three directions from `docs/design/brief.md`. The owner chose
  Tidepool, with the borrowings and corrections listed in the brief. Its full screen set, rounds
  2–3, is in `docs/design/turn2/` and `turn3/`.
- **What Tidepool asks for:**
  - The Figtree typeface.
  - Corners of 4 dp (grid cells) to 28 dp (header, sheets).
  - Named colour tokens, of which only some follow the wallpaper.
  - A grid with no mark on backed-up items.
- **Fonts.** Figtree is OFL-licensed, and Google Fonts serves it as one variable TTF (weights
  300–900, Latin and Latin Extended, so Turkish is covered). Compose reads variable fonts from
  `res/font` with `FontVariation`, and minSdk 35 is above the API 26 that needs. Downloadable fonts
  can't be variable (developer.android.com/develop/ui/compose/text/fonts).
- **Icons.** The Material Icons library is "no longer maintained or recommended". Android's guidance
  is vector drawables of Material Symbols, downloaded per icon
  (developer.android.com/develop/ui/compose/graphics/images/material).
- **Colour.** Material 3 has no warning role. The error roles of a dynamic scheme are fixed already.

## Decision
- **Colour roles.**
  - Tidepool's tokens map onto Material 3 roles (table in `ui/theme/Color.kt`).
  - On the phone the wallpaper's dynamic scheme fills them.
  - Warnings (`warn`, `wc`, `onwc`) live in `SettleColors`, outside the scheme. They stay amber, so
    they never take on the wallpaper's hue.
  - The viewer's stage, its toolbar and the markers drawn over photos are fixed too.
  - The fixed Tidepool palette is the scheme for tests and previews.
- **Fonts.** Figtree is bundled as `res/font/figtree.ttf`, with one `Font` entry per weight used. Its
  licence ships in `assets/licenses/`.
- **Type, shapes and motion.** The type scale, corners and springs come from the design's
  components, set on the Material 3 slots (`Type.kt`, `Shape.kt`, `Motion.kt`).
- **Icons.** Material Symbols Rounded, as drawables fetched from google/material-design-icons. The
  filled variant marks a selected or "on" state.
- **Layering.** The top bar, navigation bar and selection bar float over the grid and have the same
  height. Entering selection therefore moves nothing under the finger. Notice cards scroll with the
  grid.
- **Status in the grid.** Backed up shows nothing. Not backed up, uploading, needs attention and cloud
  only have a badge. Waiting and queued show only while selecting and on the Backup tab.

## Consequences
- The app no longer looks like stock One UI. It keeps the wallpaper's hue through dynamic colour.
- Figtree adds 62 KB to the APK. A missing glyph would fall back to the system font.
- New icons have to be fetched one by one. The fetch is a short `curl` per symbol.
- When Claude Design's consolidated token table arrives, these values are checked against it.
