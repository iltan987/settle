# Settle — product spec

Living document. It states what Settle must do, as testable statements. Why a rule exists lives in
the decision records (`docs/decisions/`). Change this file in the same commit as the behaviour.

## Purpose

Settle is an Android gallery that treats the phone and the user's own Google Drive as one photo and
video library. It replaces the combination of Samsung Gallery and Google Photos backup, which
cannot do delayed backup, delete-once or keep-only-a-preview on the phone.

Target: Samsung Galaxy S24 FE, Android 17 (API 37), One UI 9. Sideloaded for personal use first; a
Play Store release later must stay possible, so Play-policy-forbidden permissions are avoided.

## Defaults

| Setting | Default |
|---|---|
| Grace period before backup | 24 hours |
| Keep original on phone after verified backup | 30 days |
| Trash retention (phone and Drive) | 30 days |
| Backup network / power | Wi-Fi and charging for large backlogs; Wi-Fi for daily items |
| Folders backed up | Camera; others opt-in (Screenshots off) |

## Requirements

### Backup
- R1. Originals are uploaded byte-for-byte. The SHA-256 of the bytes in Drive equals the SHA-256 of the file on the phone.
- R2. A new item is not uploaded before its grace period ends. An item deleted during the grace period is never uploaded.
- R3. The user can back up one item, a selection, or everything immediately.
- R4. An interrupted upload resumes without re-sending confirmed bytes and without creating duplicate Drive files.
- R5. Backup continues in the background without the app open, after a one-time setup.
- R6. When Drive is full or sign-in needs the user, backup pauses and says why; nothing is lost.

### Phone storage
- R7. An original is removed from the phone only when its cloud copy is verified (ADR-004) and every eviction precondition holds (ADR-005).
- R8. Favourites, pinned items, pinned albums and pinned folders always keep their originals on the phone.
- R9. A cloud-only item shows a preview; opening it downloads the original, verifies its checksum and shows it.

### Timeline and status
- R10. One timeline shows every item, on the phone or only in the cloud, ordered by date taken.
- R11. Every item shows exactly one status: waiting, queued, uploading, backed up, cloud only, in trash, or needs attention.

### Deletion and trash
- R12. Deleting in Settle moves the item to the trash on the phone and in Drive. It can be restored until retention ends.
- R13. A backed-up item deleted in another app is handled by the external-deletion policy (ADR-006). Default: move the cloud copy to Drive trash.
- R14. A mass disappearance of files (circuit breaker, ADR-006) never deletes anything in Drive without confirmation.

### Library survival
- R15. After a phone reset or reinstall, signing in restores items, albums, folders, favourites and pins from Drive.
- R16. Settle's own data is excluded from Android backup and device transfer; Drive is the only source for restore.

### Experience
- R17. UI in English and Turkish, switchable in the app and in system settings.
- R18. Material 3 Expressive look with the wallpaper's colours; few prompts; sensible defaults.

## Later (not scheduled)
Storage overview, duplicates and similar shots, a "temporary" tag with auto-delete, exposing
cloud-only items to other apps through the system file picker, a home-screen widget, importing an
existing Google Photos library.
