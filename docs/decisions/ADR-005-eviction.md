# ADR-005: Evicting originals from the phone

- Status: accepted
- Date: 2026-10-30

## Context
"Previews only on the phone" means permanently deleting originals. MediaStore trash doesn't free
space, so eviction must be a real delete. This is the most dangerous operation in the app.

## Decision
Evict an original only when every precondition holds, checked by a pure `EvictionPolicy`:
1. Cloud copy VERIFIED (ADR-004) and a verified preview exists locally and in Drive.
2. Local file unchanged since verification.
3. Older than the keep-on-phone threshold (default 30 days).
4. Not a favourite, not pinned, not in a pinned album or folder, not in trash.
5. A fresh `files.get` immediately before deletion confirms the Drive copy still matches.
6. Not referenced by an open upload or download.

Before deleting, an `eviction_intent` row records that the disappearance is Settle's own, so the
scanner doesn't treat it as an external deletion. The first eviction run shows a dry-run list
("N items, X GB") and needs confirmation. Batches are capped at 500. Deletion uses
`MediaStore.createDeleteRequest`, silent with the "media management" permission.

## Consequences
Every rule is unit-testable with fakes. Risky behaviour is exercised on the debug sandbox with
test media before it touches the real library.
