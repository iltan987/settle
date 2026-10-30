# ADR-012: Local media index from MediaStore

- Status: accepted
- Date: 2026-10-30

## Context
- MediaStore offers per-volume generations (`getGeneration`, `GENERATION_ADDED`,
  `GENERATION_MODIFIED`) for incremental sync. `getVersion` changes when generations are reset,
  and then a full pass is needed (developer.android.com/reference/android/provider/MediaStore).
- Deleted rows can be queried only from Android 17.1 (`queryDeletedFiles`); the phone runs 37.0.
- Trashed and pending rows are hidden from queries by default (`QUERY_ARG_MATCH_TRASHED`).
- The motion-photo column `_special_format` exists only on `MediaStore.Files`, from API 37.
- With partial access (`READ_MEDIA_VISUAL_USER_SELECTED` only) a query returns just the
  selection (developer.android.com/about/versions/14/changes/partial-photo-video-access).
- Coil 3.6.3 decodes `content://` images in full; MediaStore thumbnails come from
  `ContentResolver.loadThumbnail`.
- Room 3 needs an explicit `SQLiteDriver`. Google advises against Robolectric for Room tests
  (developer.android.com/training/data-storage/room/testing-db).
- `MediaStore.markIsFavoriteStatus` (API 36) sets `IS_FAVORITE` without a prompt for apps with
  media read access.

## Decision
- **Scan.** One query per volume on `MediaStore.Files` (images and videos, trashed included):
  - first scan, a version change, or missing files on the volume: every row;
  - otherwise only rows with a generation above the stored cursor.
  - The cursor's generation is read before querying.
- **Deletions.** Found by diffing all `_ID`s. The scan rules (`reconcileScan` in `:domain`) keep
  item IDs across moves and MediaStore rebuilds by matching name, size and date taken. They
  forget files that vanish before backup, and the ADR-006 circuit breaker applies. Missing files
  drop their MediaStore ID.
- **When scans run.** On app start, on resume, and on MediaStore change notifications (debounced
  500 ms) while the process lives.
- **Access.** Scans run only with full access. Partial access shows onboarding asking for "Allow
  all". Access is never stored.
- **Storage.** Room 3 with the framework `AndroidSQLiteDriver`, in production and in Robolectric
  DAO tests. No Paging: a slim `Flow<List>` serves the grid.
- **Thumbnails.** A small Coil `Fetcher` calls `loadThumbnail`. The viewer loads full images
  through Telephoto (sub-sampled).
- **Favourites.** For files on the phone, MediaStore's `IS_FAVORITE` is the truth: Settle writes
  it and scans copy it back. Room is updated at once for the UI.

## Consequences
- **Reliability.** Revoked or partial access can't make the library look deleted. A rescan is
  cheap: 595 items in 265 ms on the S24 FE.
- **Deletion detection.** Costs one ID-only query per scan. Switch to `queryDeletedFiles` once
  the phone runs 37.1, keeping the diff as a fallback.
- **Background.** Changes made while Settle isn't running are picked up on the next start. Phase
  3 adds background triggers.
- **Older Android.** On API 35 favourites live only in Room, and a scan can overwrite them when
  the file changes. The target phone is unaffected.
- **Scale.** Revisit Paging if the timeline grows well past tens of thousands of items.
