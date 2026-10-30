# ADR-009: Use Material 3 Expressive from the 1.5 beta

- Status: accepted
- Date: 2026-10-30

## Context
Polished, modern UX is a first-class requirement. Material 3 Expressive (expressive motion,
shapes, flexible app bars, button groups, new list items) ships only in
`androidx.compose.material3:material3` 1.5.x. Compose BOM 2026.09.00 maps to the stable 1.4.0,
which has none of it. 1.5.0-beta02 (2026-10-21) is the newest
(developer.android.com/jetpack/androidx/releases/compose-material3).

## Decision
Pin `material3` to 1.5.0-beta02 in the version catalog, overriding the BOM. Use
`MaterialExpressiveTheme` with dynamic colour and `MotionScheme.expressive()`.

## Consequences
Beta APIs may be renamed before stable; usages stay in UI code where renames are cheap. Move to the
stable 1.5.0 as soon as it ships.
