# ADR-004: What "verified in the cloud" means

- Status: accepted
- Date: 2026-10-30

## Context
Removing an original from the phone is only safe if the cloud copy is provably identical. Drive
returns `size`, `md5Checksum` and `sha256Checksum` for binary files when requested with `fields=`
(developers.google.com/workspace/drive/api/reference/rest/v3/files), so verification needs no
re-download.

## Decision
A cloud original is VERIFIED only when all hold:
1. Drive `size` equals the file size recorded when the upload session started.
2. Drive `sha256Checksum` and `md5Checksum` equal the digests computed over the bytes sent.
3. The local file still matches its snapshot (size and modified time) after the last chunk.
   Amended 2026-10-31 (ADR-013): the MediaStore generation is left out, because it also moves
   when only metadata such as a favourite changes.
4. If Drive hasn't reported `sha256Checksum` yet, the copy stays UPLOADED and is re-checked with
   backoff. MD5 alone never makes a copy evictable.

Downloads are verified the same way against the stored SHA-256. A weekly audit re-lists Drive and
marks mismatched, missing or trashed copies SUSPECT; the audit never deletes anything.

## Consequences
Hashing costs one read of each file, done while streaming the upload. Amended 2026-10-31
(ADR-013): digest state can't be saved, so a resumed upload re-reads the confirmed part from the
phone (about 0.3 s for 124 MiB) instead of persisting it; nothing is sent twice.
