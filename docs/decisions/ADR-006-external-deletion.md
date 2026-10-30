# ADR-006: Deletions made outside Settle

- Status: accepted
- Date: 2026-10-30

## Context
Samsung Gallery stays installed. When the user deletes there, the file moves into Samsung's own
private trash folder and its MediaStore row disappears; Settle can't read that trash. Rows also
vanish for harmless reasons: SD card removed, permission revoked, MediaStore rebuild, folder moves.

## Decision
- Item still in its grace period: forget it. It never reaches the cloud.
- Item already backed up: default policy moves the Drive copy to Drive trash (recoverable 30
  days). Settings offer KEEP_IN_CLOUD and ASK.
- Circuit breaker: if more than max(200, 10 %) of on-phone items vanish in one scan, or the volume
  or permission is gone, mark them MISSING, change nothing in Drive, and ask the user.
- A new row with the same size, name and date taken in the same scan is treated as a move.

## Consequences
"Delete once, gone everywhere" works from any gallery, with a recovery window and protection
against accidental mass deletion.
