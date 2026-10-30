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
3. The local file still matches its snapshot (size, modified time, MediaStore generation) after
   the last chunk.
4. If Drive hasn't reported `sha256Checksum` yet, the copy stays UPLOADED and is re-checked with
   backoff. MD5 alone never makes a copy evictable.

Downloads are verified the same way against the stored SHA-256. A weekly audit re-lists Drive and
marks mismatched, missing or trashed copies SUSPECT; the audit never deletes anything.

## Consequences
Hashing costs one read of each file, done while streaming the upload. The digest state is
persisted so a resumed upload doesn't re-read the whole file.
