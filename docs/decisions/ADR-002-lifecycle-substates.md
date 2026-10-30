# ADR-002: Item lifecycle as independent sub-states with a derived status

- Status: accepted
- Date: 2026-10-30

## Context
An item's situation has several independent parts: is the original on the phone, what is the
state of the cloud copy, is it in the trash, has its grace period ended. One big state enum
multiplies these into many states and makes transitions hard to test.

## Decision
- Store `LocalState` (PRESENT, TRASHED, EVICTED, MISSING), `CloudState` (NONE, RESERVED, UPLOADING,
  UPLOADED, VERIFIED, SUSPECT, TRASHED, DELETED), a trash entry and the upload-eligible time.
- A pure function `deriveStatus()` in `:domain` maps them to the one status the UI shows.
  Impossible combinations map to NEEDS_ATTENTION, never to a crash or a silent "fine".
- Safety rules are invariants over these states (for example: EVICTED implies a VERIFIED cloud
  copy with matching SHA-256 and size). Tests check them after every scenario; debug builds check
  them after every sync cycle.

## Consequences
Each transition is a small row update that is easy to test. Status is computed, not stored; add a
stored column only if the timeline query needs it.
