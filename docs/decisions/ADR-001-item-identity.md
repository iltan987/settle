# ADR-001: Item identity is an app-generated ULID

- Status: accepted
- Date: 2026-10-30

## Context
An item must keep one identity while it moves between states: on the phone during the grace
period (no Drive file yet), uploaded, evicted from the phone, re-downloaded, and restored on a new
phone. MediaStore IDs change on a new phone or a re-index. Drive file IDs don't exist during the
grace period and change on re-upload. A content hash needs the whole file read, which is too slow
at scan time for multi-GB videos.

## Decision
- Each item gets a ULID (`MediaItemId`) when first seen. It is stored in Room and in the Drive
  file's `appProperties` (`st.id`).
- SHA-256 of the content is the content identity: used for verification, duplicate detection, and
  re-matching local files to cloud items after a reinstall. Computed while uploading.
- MediaStore ID and Drive file ID are replaceable attachments of the item.
- Editing a file in place keeps the ULID; the new content is uploaded and the old cloud file is
  superseded (moved to Drive trash only after the new one is verified).

## Consequences
The timeline can show an item immediately. Restore needs both: ULIDs from Drive metadata, hashes
to match files still on the phone.
