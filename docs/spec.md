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
| Grace period before backup | 24 hours, from when the file appeared on the phone |
| Keep original on phone after verified backup | 30 days |
| Trash retention (phone and Drive) | 30 days |
| Automatic backup | Wi-Fi and charging (in practice overnight), no notification |
| "Back up now" | At once, Wi-Fi only; mobile data is a setting |
| Network use | Polite: leaves room for others (R23) |
| Folders backed up | Camera; others opt-in (Screenshots off) |
| Folders shown in the timeline | All |

## Requirements

### Backup
- R1. Originals are uploaded byte-for-byte. The SHA-256 of the bytes in Drive equals the SHA-256 of the file on the phone.
- R2. A new item is not uploaded before its grace period ends. An item deleted during the grace period is never uploaded. The grace period counts from when the file appeared on the phone.
- R3. The user can back up one item, a selection, or everything immediately.
- R4. An interrupted upload resumes without re-sending confirmed bytes and without creating duplicate Drive files.
- R5. Backup continues in the background without the app open, after a one-time setup. New media
  are noticed without opening the app. Settings shows each setup step (notifications, unrestricted
  battery use, Samsung's Never sleeping apps) only while it is still needed.
- R6. When Drive is full or sign-in needs the user, backup pauses and says why; nothing is lost.
- R22. Google Drive is connected once in Settings by picking an account; Settings shows the account
  and its storage. Disconnecting withdraws access and never deletes anything in Drive or on the
  phone.
- R23. Transfers adapt to the network: they leave bandwidth for other devices on the same network,
  run several uploads at once when the connection allows, and slow down when it is poor. Polite
  mode stays below three quarters of the measured speed and backs off when the network's delay
  rises; a fixed limit or no limit can be chosen instead.
- R24. Each folder has a backup switch; only the camera folder is on by default. Automatic backup
  can be turned off, and backup can be paused; "Back up everything now" skips the grace period
  and the charger.

### Phone storage
- R7. An original is removed from the phone only when its cloud copy is verified (ADR-004) and every eviction precondition holds (ADR-005).
- R8. Favourites, pinned items, pinned albums and pinned folders always keep their originals on the phone.
- R9. A cloud-only item shows a preview; opening it downloads the original, verifies its checksum and shows it.

### Timeline and status
- R10. One timeline shows every item, on the phone or only in the cloud, ordered by date taken.
- R10a. Every folder shows in the timeline by default. A folder can be hidden from the timeline and
  stays browsable under Folders.
- R11. Every item shows exactly one status: not backed up (its folder isn't chosen), waiting, queued, uploading, backed up, cloud only, in trash, or needs attention.

### Deletion and trash
- R12. Deleting in Settle, one item or a selection, moves the item to the trash on the phone and
  in Drive, with Undo. It can be restored until retention ends. Delete forever and Empty trash
  remove items from the phone and Drive at once, only after a confirmation that names the count.
- R13. A backed-up item deleted in another app is handled by the external-deletion policy (ADR-006,
  ADR-015). Default: it enters Settle's trash and its Drive copy moves to Drive trash an hour after
  it vanished, unless it comes back first; "Ask me first" waits for the user. Restoring it
  downloads the Drive copy into its folder and publishes it only if its SHA-256 matches.
- R14. A mass disappearance of files (circuit breaker, ADR-006) never moves or deletes anything in
  Drive without confirmation, also when it reaches Settle spread over several scans. The question
  stays until it is answered.

### Library survival
- R15. After a phone reset or reinstall, signing in restores items, albums, folders, favourites and pins from Drive.
- R16. Settle's own data is excluded from Android backup and device transfer; Drive is the only source for restore.

### Local library
- R19. Settle needs full photo and video access. With a partial selection it explains why and offers
  "Allow all"; it never treats unseen files as deleted (ADR-012).
- R20. While Settle is open, new, changed and deleted media appear without user action.
- R21. A favourite set in Settle is visible to other apps through MediaStore, and one set elsewhere
  shows in Settle.

### Experience
- R17. UI in English and Turkish, switchable in the app and in system settings.
- R18. Material 3 Expressive look in Settle's Tidepool design (ADR-016): the wallpaper's colours,
  Figtree, photos first with light chrome; few prompts; sensible defaults.

## Later (not scheduled)
Storage overview, duplicates and similar shots, a "temporary" tag with auto-delete, exposing
cloud-only items to other apps through the system file picker, a home-screen widget, importing an
existing Google Photos library.

Owner's ideas from 2026-10-31, to research when scheduled:
- **Galaxy AI from Settle.** Samsung's photo editor accepts no standard `ACTION_EDIT`; Samsung
  Gallery does accept `ACTION_VIEW` for a photo, and opened that way it shows its AI edit button
  (seen 2026-10-31). An "Open in Samsung Gallery" action is the way in; "Edit in Google Photos"
  works through `ACTION_EDIT`.
  Circle to Search is system-wide and already works on Settle's screens.
- **Places.** In the details sheet, an address instead of raw coordinates and a small map; a map
  of the library with photos placed where they were taken.
- **People.** Group photos by the faces in them, on the phone, and browse one person's photos.
