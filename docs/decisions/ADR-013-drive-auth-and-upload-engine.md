# ADR-013: Drive authorization and the upload engine

- Status: accepted
- Date: 2026-10-31

## Context
- `AuthorizationClient.authorize()` (play-services-auth 22.0.0) shows Google's account chooser and
  consent screen itself when nothing is granted, and later returns tokens silently. A Web client
  ID and a sign-in step are only needed for a backend (developer.android.com/identity/
  authorization). The result doesn't name the account.
- An External consent screen can only be published to production with an app home page and a
  privacy policy URL on an authorized domain; in Testing, grants expire after 7 days for any
  scope beyond name, email and profile (support.google.com/cloud/answer/15549049, /15549945).
- Resumable uploads: chunks in multiples of 256 KiB, "as large as possible"; the server's `Range`
  decides where to continue; a status query (`Content-Range: bytes */total`) recovers after a lost
  answer; sessions last a week. Creating a file with an ID from `files.generateIds` twice answers
  409, never a duplicate (developers.google.com/workspace/drive/api/guides/manage-uploads,
  reference/rest/v3/files/generateIds).
- Java's `MessageDigest` state can't be saved, so digests can't survive a killed process.
- MediaStore's `GENERATION_MODIFIED` also moves on metadata changes such as `IS_FAVORITE`.
- Since Android 15 a cached background process loses network access unless it runs a job or a
  foreground service; on the phone this showed as `blocked=APP_BACKGROUND` in `dumpsys netpolicy`
  and `UnknownHostException`s.

## Decision
- **Authorization.** `AuthorizationClient` only, scope `drive.file`, no Credential Manager and no
  Web client. After the first grant Settle reads the account from Drive's `about` and passes it
  with `setAccount` from then on. Tokens are cached in memory; a 401 clears the token and retries
  once. Disconnect calls `revokeAccess`. The consent screen is published to production; its home
  page and privacy policy live on the `gh-pages` branch (https://iltan987.github.io/settle/).
- **Upload engine** (`BackupEngine` in `:domain`, ports for Drive, originals and storage):
  - the Drive file ID is reserved before the first byte; a 409 means an earlier try finished, and
    that file is verified instead of creating another;
  - 8 MiB chunks read into one reused buffer; the server's offset always decides the next chunk;
  - digests cover exactly the bytes the server confirmed; after a restart they are rebuilt by
    reading the confirmed prefix from the phone again (this replaces ADR-004's "digest state is
    persisted");
  - "changed during upload" compares size and date modified only, not the MediaStore generation;
  - a missing SHA-256 leaves the copy UPLOADED and is asked for again; a mismatch makes it SUSPECT;
  - network and 5xx errors back off (2ⁿ s + jitter, max 32 s, 6 tries without progress); a full
    Drive or a withdrawn grant stops and keeps the record.
- **Running.** Phase 2 runs backups one at a time in the app process and resumes unfinished ones
  when the app starts. Background runs need a job (Phase 3), because the network is cut once the
  process is cached.

## Measured on the phone (2026-10-31, Wi-Fi 6 at a 2.4 Gbps link)
- 8 MiB and 32 MiB chunks were equally fast (4.8 and 4.6 vs 4.7 and 4.7 MiB/s for 120 MiB of test
  data), so 8 MiB stays: less memory and less to resend after an interruption.
- One upload stream reached about 5 MiB/s, far below the link and the owner's gigabit line.
- Drive takes about 3–4 s to answer the last chunk of every file, whatever its size; asking for
  fewer fields didn't remove it. A 124 KB photo took 5.4 s end to end.
- Resuming a 337 MiB video killed at 120 MiB: one status query, 124 MiB re-hashed in about 0.3 s,
  only the remaining 212.7 MiB sent.

## Consequences
- No tokens or client files in the repo; the privacy policy must stay true as Settle changes.
- Re-hashing on resume costs a local read of the confirmed bytes, never network.
- The per-file latency and the single-stream ceiling mean a large backlog of small photos needs
  several uploads at once to finish in reasonable time. Phase 3 adds concurrent uploads with a
  bandwidth limit (R23).
- A file edited after its backup gets a new upload with a new ID; the old Drive copy stays until
  superseding is built (ADR-001).
