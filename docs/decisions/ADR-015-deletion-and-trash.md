# ADR-015: Deletion, the trash and restoring from it

- Status: accepted
- Date: 2026-10-31

## Context
- **Phone trash.**
  - `MediaStore.createTrashRequest(resolver, uris, value)` returns a PendingIntent that asks the
    user, then sets `IS_TRASHED` to `value`. `false` takes items out of the trash, through the same
    prompt.
  - The operation has finished before the activity result arrives.
  - Apps targeting API 36 or higher may send at most 2000 URIs per request.
  - `createDeleteRequest` works the same way for permanent deletion.
  - Source: the API 37 framework sources, `android/provider/MediaStore.java`.
- **Expiry.**
  - `DATE_EXPIRES` is read-only and set by the system when `IS_TRASHED` or `IS_PENDING` changes:
    "typically 30 days" for trashed items and 7 days for pending ones.
  - Expired items are deleted during the next idle period.
  - An app can't choose a different period.
- **Media management.**
  - `MANAGE_MEDIA` only removes the prompt; the request is still needed. It is an app-op, granted
    on the screen that `Settings.ACTION_REQUEST_MANAGE_MEDIA` opens, with `package:` as data.
    `MediaStore.canManageMedia` checks it.
  - In AOSP's `PermissionUtils.checkPermissionManageMedia`, the check is the app-op alone. The
    guide names `READ_EXTERNAL_STORAGE` too. On the API 37 emulator, granting the app-op was
    enough: no prompt for trash, untrash or delete.
- **Drive.**
  - `files.update {trashed: true|false}` moves a file into or out of the trash. Drive deletes
    trashed files after 30 days.
  - `files.delete` deletes permanently and skips the trash. Only the owner can trash or delete.
    Settle created every file it touches, so the owner is the user.
  - `drive.file` covers creating and modifying the files Settle created, and the debug reset
    already trashes Settle's folder that way.
  - `alt=media` with `Range: bytes=…` downloads part of a file.
  - Source: developers.google.com/workspace/drive/api/guides/delete, manage-downloads and
    api-specific-auth.
- **Deletions in other apps.**
  - Samsung Gallery moves deleted files into its own private trash, and their MediaStore rows
    disappear (ADR-006).
  - Apps that use MediaStore's trash instead leave the row with `IS_TRASHED = 1`.

## Decision
- **Settle's trash is the phone's trash plus Drive's trash.** Settle keeps a `trash_entry` per
  item: origin (Settle or another app), when it was deleted, and an expiry 30 days later. Nothing
  is copied anywhere.
- **Deleting in Settle.**
  - The original goes to the system trash through `createTrashRequest`, one request per batch of
    at most 2000.
  - Every Drive file of the item, the current copy and the copies it superseded, is queued for
    the Drive trash.
  - The upload record stays as it is, so restoring needs no new upload.
  - A trashed item is never uploaded. If its upload finishes anyway, its file is queued for the
    trash too.
- **Queued Drive changes.**
  - The `drive_op` table holds trash, untrash and delete operations, keyed by Drive file ID. The
    latest intent wins, so restoring before a queued trash has run cancels it.
  - All three operations are idempotent. A file that is already gone counts as done for trash and
    delete.
  - They run in a JobScheduler job on any network (ID 5), and in maintenance.
- **The phone decides.** After each scan, in the scan's own transaction:
  - an item that is out of the trash again (restored here or in another app) leaves Settle's trash,
    and its Drive copy is untrashed;
  - an item trashed through MediaStore by another app enters Settle's trash with origin "another
    app".
- **Deleted in another app** (refines ADR-006).
  - A backed-up item whose row vanishes, or that another app trashes, follows the user's policy.
    - **Move the Drive copy to trash** is the default. The item enters Settle's trash at once, but
      its Drive copy is trashed only an hour after it vanished (a minute in debug builds). A file
      that comes back first cancels the operation.
    - **Ask** keeps the item in a `vanished` list until the user decides, and touches nothing in
      Drive.
  - Keep in cloud waits for cloud-only items in the timeline.
  - A circuit-breaker batch always goes to `vanished`, and nothing happens in Drive until the user
    chooses "Move Drive copies to trash" or "Keep Drive copies".
  - Live scans can take a large deletion in slices; on the emulator, 205 files arrived as 11 scans.
    So the hour's wait is also the breaker's window. A scan's disappearances count together with
    the deletions elsewhere whose Drive trash hasn't run. Past the limit, all of them become one
    batch and their queued Drive changes are dropped.
  - Rows that vanish from the phone's trash have expired. They don't count toward the breaker.
- **Restoring.**
  - When the original is still in the phone's trash, Settle untrashes it with `createTrashRequest`
    and untrashes the Drive copy.
  - When it isn't (deleted in Samsung Gallery, or already expired), Settle untrashes the Drive copy
    and downloads it into the original's folder:
    - The download goes into a pending MediaStore item, with a `Range` resume and the written
      prefix hashed again on resume.
    - It is published only when size and SHA-256 equal the upload record.
    - Then the item is linked to the new row, and the record takes the new file's modification
      time. The bytes are proven equal, so no upload follows.
  - Downloads run as a user-initiated job (ID 6).
- **Delete forever.** Only for items in Settle's trash, after a confirmation that names the count:
  `createDeleteRequest` on the phone and `files.delete` in Drive. The item then leaves the library.
- **Expiry.** 30 days after deletion Settle drops its own records, once the phone no longer has
  the file. Drive and the phone delete the bytes themselves.
- **Media management** is offered once in Settings, and deletion works without it.

## Consequences
- Delete and Undo work offline. The Drive side follows when a network is there.
- Deleting in another app reaches Drive no sooner than an hour later, and can be undone in either
  app.
- The prompt from Android's trash dialog stays unless the user grants Media management; Undo then
  shows one too.
- Restoring something deleted elsewhere costs a download. A file restored both from Samsung's
  trash and by Settle exists twice until duplicates are handled.
