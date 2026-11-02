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
- R11. Every item shows exactly one status: not backed up (its folder isn't chosen), waiting,
  queued, uploading, backed up, cloud only, in trash, or needs attention. In grids it follows the
  backup plan's decision for the item, the same one the app's backup status counts. Grid cells
  badge it only where it helps (ADR-016): backed up never; uploading (only while it is live),
  needs attention and cloud only always; not backed up once Drive is connected; waiting and
  queued only while selecting and on the Backup tab. The viewer always tells it.

### Deletion and trash
- R12. Deleting in Settle, one item or a selection, moves the item to the trash on the phone and
  in Drive, with Undo. It can be restored until retention ends. Delete forever and Empty trash
  remove items from the phone and Drive at once, only after a confirmation that names the count.
  The first delete explains Android's own confirmation once, while Settle can't manage media; the
  message after deleting says plainly when items weren't backed up. The trash groups items by
  when and how they were deleted and shows the days left; restoring says where items went, and
  Delete forever's confirmation says when an item exists nowhere else.
- R13. A backed-up item deleted in another app is handled by the external-deletion policy (ADR-006,
  ADR-015). Default: it enters Settle's trash and its Drive copy moves to Drive trash an hour after
  it vanished, unless it comes back first; "Ask me first" waits for the user. Restoring it
  downloads the Drive copy into its folder and publishes it only if its SHA-256 matches; the trash
  shows its progress and, if it stops, why. A restore stopped by being offline or signed out
  continues the next time Settle is open. With "Ask me first", such items wait in the trash without
  a countdown until the user keeps their Drive copies or moves them to the trash.
- R14. A mass disappearance of files (circuit breaker, ADR-006) never moves or deletes anything in
  Drive without confirmation, also when it reaches Settle spread over several scans. The question
  stays until it is answered, on one screen reached from the timeline: keep the Drive copies, check
  the phone again (which says how many came back), or move the Drive copies to the trash after a
  confirmation. Files that come back answer it by themselves.

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
- R25. The viewer names where a photo was taken when it has a location, and its details show the
  address and open it in a maps app. Only the coordinates of the photo on screen go to the
  platform geocoder for this (ADR-017); without a name, the folder shows instead.

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
- **Places.** A map of the library with photos placed where they were taken (the details sheet's
  address is R25).
- **People.** Group photos by the faces in them, on the phone, and browse one person's photos.

Owner's ideas from 2026-11-02, in the suggested order (to research when scheduled):
- **Memories.** "On this day": the same day in earlier years, as a row on the Photos tab, with an
  optional notification (off by default; Settle stays quiet otherwise).
- **Trips and places.** Settle learns where the user usually is from the photos themselves, on the
  phone; a run of photos far from there over a few days becomes a trip ("İzmir, July 2025") with
  a cover and dates. A places view groups photos by city. "Then and now" pairs photos taken at
  the same spot in different years. Photos are grouped by coordinates on the phone first and only
  each group is named, so a trip costs one geocoder request (ADR-017 allows only the photo on
  screen today; this needs its own decision).
- **Search by text and things.** Find photos by the text in them (a Wi-Fi password, a receipt, a
  booking) and by what they show ("cat", "beach", "document"), with on-device recognition such as
  ML Kit's text recognition and image labelling.
- **Documents and screenshots apart.** Receipts, IDs and screenshots kept out of the timeline in
  their own place, with a suggestion to clean up old screenshots; pairs with the "temporary" tag.
- **People and pets.** Beyond grouping faces: name a group or link it to a contact, then browse
  that person's photos, selfies included; pets too. Face detection exists on the phone; telling
  who is who needs a bundled recognition model (check what is current).
- **Search by description.** "Sunset at the sea", "kids at the birthday", with an on-device model;
  check what the S24 FE supports. The heaviest of these.

Rules for all of them:
- Recognition and indexing run on the phone only, while charging, like automatic backup. Nothing
  new leaves the phone except what an ADR allows.
- What the user sets (a person's name, a trip's title, a hidden match) is kept apart from what
  Settle computes, so recomputing never overwrites it, and it is saved in Drive like albums and
  favourites (R15), so a new phone doesn't need the work again.
