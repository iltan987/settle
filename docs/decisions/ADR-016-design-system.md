# ADR-016: Tidepool, Settle's design system

- Status: accepted
- Date: 2026-11-01

## Context
- **Origin.** Claude Design offered three directions from `docs/design/brief.md`. The owner chose
  Tidepool, with the borrowings and corrections listed in the brief. The finished project is in
  `docs/design/turn4/`: every screen, the app icon, and a handoff sheet (`Settle Handoff.dc.html`)
  with the colour tokens, their light and dark values and nearest Material 3 roles, type, corners,
  spacing, layering, motion and shared strings.
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
- **The app icon.** A dome settling on two layers: an adaptive icon with a monochrome layer for
  themed icons, and a tonal brand mark for use inside the app. The welcome screen's art, built
  from the same shapes, is drawn in Compose (`BrandArt`) rather than bundled: its fills are theme
  tokens, so it follows the wallpaper, and its tiles drift into place.
- **Status in the grid.** Backed up shows nothing. Not backed up, uploading, needs attention and cloud
  only have a badge; Not backed up only once Drive is connected, Uploading only while an upload is
  live. Waiting and queued show only while selecting and on the Backup tab.
- **One status, one message host.** The chip, notice cards, Backup tab header and navigation dot
  read one backup headline, which never says "All in Drive" before the first verified upload.
  Snackbars come from one host above navigation, so Undo outlives the screen that started it.

## Consequences
- The app no longer looks like stock One UI. It keeps the wallpaper's hue through dynamic colour.
- Figtree adds 62 KB to the APK. A missing glyph would fall back to the system font.
- New icons have to be fetched one by one. The fetch is a short `curl` per symbol.
- The handoff sheet is the reference for tokens, type and motion. Where it and a screen frame
  disagree, the sheet wins.
