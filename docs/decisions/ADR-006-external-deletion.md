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

Amended 2026-10-31 (ADR-015):
- The Drive copy goes to Drive trash an hour after the file vanished, and only if it hasn't come
  back. The item is in Settle's trash meanwhile, and Restore downloads the Drive copy.
- The settings are "Move the Drive copy to trash" (default) and "Ask me first". KEEP_IN_CLOUD
  waits for cloud-only items in the timeline.
- The hour is also the breaker's window: live scans took one deletion of 205 files in 11 slices,
  so a scan's disappearances count together with earlier ones whose Drive trash hasn't run.
- Files that vanish from the phone's own trash have expired. They don't count toward the breaker.

## Consequences
"Delete once, gone everywhere" works from any gallery, with a recovery window and protection
against accidental mass deletion.
