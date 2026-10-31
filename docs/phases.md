# Phase log

Each phase ends with something that runs on the phone, a build, tests, and a check-in before the
next phase. Newest first.

## Phase 3 — Automatic, polite background backup (2026-10-31)

Delivered:
- `:domain` rules (ADR-014):
  - **`planBackup`**: folder defaults (camera only), the grace period from `DATE_ADDED`, retry
    waits per problem, requests first and then the newest, run conditions, and pause reasons.
  - **`UploadPacer`**: LEDBAT-style delay backoff, measured speed with a cap between 25% and 75% of
    it, and slots that are kept only while they add throughput.
  - **Engine**: several engines upload at once and share a folder lock.
  - 98 domain tests.
- Room schema v3:
  - date added, the per-folder backup switch, retry waits, saved "Back up now" requests, and
    backup settings;
  - folder choices change one column at a time;
  - migration test from the v2 schema.
- Jobs, all JobScheduler and no WorkManager:
  - **automatic**: Wi-Fi, charger, battery not low; no notification;
  - **requested**: user-initiated, with a silent progress notification and Pause;
  - **media**: a content-URI trigger that scans new media while Settle is closed;
  - **maintenance**: every 6 hours.
  - A boot receiver re-arms the jobs.
- `BackupRunner`:
  - slots paced by the pacer through one shared Okio throttle;
  - its own HTTP/1.1 upload connections;
  - a TCP-handshake delay probe;
  - Drive full or "auth needed" pauses backup and notifies the user.
- UI:
  - **Settings → Backup**: summary, everything now, automatic, grace, charging, mobile data,
    network use, folders, pause, and the reliability setup (notifications, battery, Samsung Never
    sleeping apps).
  - **Settings → Drive**: Reconnect.
  - **Folders**: a Back up switch in each folder's menu.
  - **Viewer and grid**: waiting (with time), queued, not chosen, uploading, needs attention.
  - English and Turkish.
- A debug tool for measuring parallel uploads.
- 81 Robolectric/JVM tests in `:app`, including the runner against the in-memory Drive on a virtual
  clock.

Verified on the S24 FE (debug build, `Settle (debug)/`):
- **Measured first.**
  - One upload reaches about 42 Mbps, which is the owner's line. HTTP/1.1 and HTTP/2 are equal.
  - Small files go from 1.5 to 4.4 MiB/s with 6 uploads at once.
  - A PC on the same network kept a 70 ms ping in every run.
- **Grace (R2).** New test files waited out a 1-minute debug grace. A file deleted inside it was
  forgotten and never uploaded.
- **Background, app closed (R5).** When the charger appeared (simulated with `dumpsys battery`),
  the automatic job ran with Settle closed and uploaded with no network block.
- **New photo while closed.** The media job started Settle about 10 s after the file appeared and
  planned the upload. The automatic job uploaded it about 1.5 min after the grace ended.
- **Killed mid-upload** (`kill -9`). The next run asked Drive for each session's offset, finished
  both 64 MiB files without a new session, and their SHA-256 values equal the local files.
- **"Back up everything now"** with the phone "unplugged" and Settle in the background. It skipped
  grace and charger, showed "3 öge yedekleniyor" with progress, and the notification was gone
  afterwards.
- **Pause** stopped a running upload job within a second; Resume continued from Drive's offsets.
- **Real backlog** (owner approved): 256 Camera items, 2.19 GB.
  - All verified in about 13 minutes on the charger, Polite mode settling at 2.9–3.5 MB/s with 2–4
    uploads.
  - Three random photos' SHA-256 values equal Drive's.
  - A PC ping during the whole backlog: median 70 ms, p90 73, max 93, the same as idle.
- Settings, statuses and folder switches render in Turkish.
- **Reconnect** (the owner revoked Settle's access at myaccount.google.com).
  - A backup run got 401, Google answered "needs consent", and backup paused.
  - The "Google Drive'ı yeniden bağla" notification appeared, and Settings showed Reconnect.
  - Tapping the notification opened Google's account picker. After the owner allowed access, the
    pause and the notification cleared, and the waiting request was verified 3 s later.
- **Reboot.** The boot receiver ran 46 s after boot and re-armed the media and maintenance jobs.
  A file added afterwards, with Settle closed, was scanned and planned for 24 h later.

Findings:
- **Drive's `about` is a poor delay signal.** It took 200–500 ms on Google's side, while a round
  trip to the host varied by about 5 ms. The first pacer read that noise as congestion, and it
  also re-measured on every `onNetworkChanged` (Android sends it for capability changes). Fixed
  before the backlog test.
- **A dropped result hung a run.** `withTimeoutOrNull { receive() }` dropped an upload's result once,
  so the run never ended and kept probing. Fixed with `select`.
- **A killed job waited for a change.** After `kill -9`, the automatic job didn't run again until
  the charger state changed. Maintenance now runs every 6 hours and plans again.
- **Battery "Optimized" stopped a long run.** After 12 minutes Android stopped the automatic job
  with `STOP_REASON_BACKGROUND_RESTRICTION` because Settle's battery use isn't "Unrestricted"; it
  restarted 6 s later. The Settings item for unrestricted battery use is there for this.
- **This router doesn't bloat.** Even full-speed uploads didn't raise other devices' ping; Polite's
  75% cap is what leaves room here.
- **adb from WSL is still unreliable.** `adb push` stalled twice on 64 MiB files.
- **Reconnect test, first try.**
  - A request made while access was revoked stayed queued after reconnecting. The automatic job
    now also covers requests, and reconnecting restarts them.
  - The notification only opened Settings; it now opens the account picker.
  - The storage figure wasn't read again after reconnecting.
- **Second try.** The notification never appeared. Work done after `jobFinished()` was cancelled:
  Android destroys the job service, and its scope with it. Notifying and planning now happen
  before `jobFinished()`. The media trigger re-arms itself explicitly.

Not done or deviations from the plan:
- **Measurement scare.** A measurement script picked IDs with SQL `LIKE 'sc_%'`, which matched the
  owner's screenshots. 3 screenshots were copied into `Settle (debug)/media/2026/06/` and 840 MiB of
  a screen recording went into an unfinished session (no Drive file). I stopped it and removed that
  one upload record; nothing was deleted. The debug tool now refuses anything outside the test
  folder.
- **Not tested on the phone:**
  - a full Drive;
  - the Samsung Never sleeping intent opening the list.
- **The full Drive audit** moves to the eviction phase.
- **Live Updates / Now Bar** are not used.

Left on the phone:
- Camera backup is off again in the debug build (owner's choice); a 24 h grace.
- The debug app has notification permission (`pm grant`).
- Test files and `Pictures/SettleTest/` are deleted; their Drive copies stay in `Settle (debug)/`.

## Phase 2 — Connect Google Drive, back up one item (2026-10-31)

Delivered:
- `:domain` backup rules (`BackupEngine`, ADR-013): reserved Drive file IDs, 8 MiB resumable
  chunks that follow the server's offset, digests of confirmed bytes rebuilt from the phone on
  resume, verification of size + SHA-256 + MD5 (ADR-004), backoff, 409 and session-expiry
  recovery. 23 scenario tests against an in-memory Drive, each ending with the invariant "VERIFIED
  means Drive holds the phone's exact bytes"; 66 domain tests in total.
- `DriveRest`, a Drive v3 REST client over OkHttp 5.5.1, tested against MockWebServer (12 tests).
- Room schema v2 (upload records, Drive folder IDs, account) by auto-migration, with a migration
  test built from the committed v1 schema.
- `MediaStoreOriginals`: unredacted originals (`setRequireOriginal`), positional reads.
- `DriveAuth` on `AuthorizationClient` (play-services-auth 22.0.0): no sign-in step, account read
  from Drive's `about`, silent tokens, revoke on disconnect.
- Settings → Google Drive: connect, account, storage, folder, disconnect; debug-only reset of
  `Settle (debug)/` to the Drive trash.
- Viewer: cloud button (back up now, progress, backed up), backup row with "Open in Google Drive",
  outcome snackbars; grid badge for backed-up items.
- Debug-only tools: per-request timing in logcat (`DriveHttp`) and an adb hook that backs up an item
  by MediaStore ID.
- OAuth consent screen published to production; home page and privacy policy on `gh-pages`.
- 59 Robolectric/JVM tests in `:app`.

Verified on the S24 FE (debug build, `Settle (debug)/` in the owner's Drive):
- Connect: account chooser and consent, then Settings showed the account and "50 GB of 5.5 TB".
- Test PNG, a GPS photo, both motion photos and a 337 MiB camera video were backed up and verified.
  For each, the SHA-256 Settle recorded equals `sha256sum` of the file pulled with adb. The GPS
  photo's location EXIF is present in that file, so the location survives byte for byte.
- Interrupt: the video upload was force-stopped after 120 MiB. On restart Settle resumed on its
  own: one status query (Drive held 124.3 MiB), 124 MiB re-hashed in about 0.3 s, only the
  remaining 212.7 MiB sent, same Drive file ID, verified.
- Lost answer, seen live: an upload started in the background lost its network mid-chunk. Once
  Settle was in front, the status query showed Drive already had the whole file; Settle verified
  it without sending it again.
- Asking to back up a verified item again makes no Drive request.
- Chunk size: 8 MiB 4.8 / 4.6 MiB/s, 32 MiB 4.7 / 4.7 MiB/s on 120 MiB test files; 8 MiB kept.
- Disconnect and reconnect (done by the owner): access was revoked and granted again; the account
  and all 11 verified upload records survived, and backed-up items still show as backed up.
- Test files in `Pictures/SettleTest/` were deleted afterwards; their Drive copies stay in
  `Settle (debug)/` until the owner resets it.

Findings:
- One upload stream reaches about 5 MiB/s although the phone's Wi-Fi link is 2.4 Gbps and the
  owner's line is gigabit.
- Drive needs about 3–4 s to answer the last chunk of every file, so a 124 KB photo takes ~5 s.
  A backlog of small photos needs several uploads at once (R23, Phase 3).
- A cached background process has no network on Android 15+ (`blocked=APP_BACKGROUND`), so Phase 3
  backups must run as jobs.
- The first upload also created the four folders, taking 7.9 s in total.

Not done or deviations from the plan:
- Credential Manager sign-in was dropped: `AuthorizationClient` shows the account chooser itself.
- `st.sha` left out of `appProperties`; the change snapshot ignores the MediaStore generation;
  digests are re-hashed on resume instead of persisted (ADR-003/004 amended, ADR-013).
- `MigrationTestHelper` reads schemas from app assets, which local tests lack; the migration test
  builds the old database from the exported JSON instead.
- Release OAuth client: not created until release signing exists.

Known issues:
- An abandoned empty pending MediaStore row (id 38635) from a failed test insert; Android removes
  such rows after 7 days.

## Phase 1 — Local gallery (2026-10-30)

Delivered:
- `:domain`:
  - `reconcileScan` keeps item IDs across moves and MediaStore rebuilds, forgets files deleted
    before backup, marks backed-up ones missing, and has the ADR-006 circuit breaker;
  - `groupByDay` for the timeline.
- Room 3 index (`media_item`, `local_file`, `folder_setting`, `scan_cursor`), schema v1 exported.
- `LibrarySync`: per-volume generation scans with ID-diff deletions, on start, on resume and on
  MediaStore changes, only with full access (ADR-012).
- Onboarding for full / partial / blocked media access.
- Photos timeline with sticky day headers, video, motion-photo and favourite badges, MediaStore
  thumbnails.
- Viewer:
  - Telephoto zoom;
  - Media3 video with Material 3 controls;
  - muted motion-photo playback;
  - favourite (mirrored to MediaStore), share, details with EXIF location.
- Folders tab with Favourites, per-folder "Show in Photos", folder and favourite grids.
- Settings: library count, last scan, rescan.
- 36 domain tests and 26 Robolectric tests (DAO, sync, MediaStore mapping, onboarding, timeline,
  folders).

Verified on the S24 FE:
- **Onboarding.**
  - Renders in Turkish. The system dialog offers limited / all / deny; "Allow all" grants images,
    video and media location in one step.
  - Partial access (full permissions revoked for the debug app) shows the "only some photos"
    screen and runs no scan. "Allow all" in the system re-prompt restores access; the next scan
    forgot nothing (595 items).
- **Scanning.** The first scan indexed 595 items (502 photos, 91 videos, 2 motion photos) in
  265 ms; that matches the adb count. Incremental rescans take 55–230 ms.
- **Live updates and deletion.** A test PNG inserted through MediaStore into
  `Pictures/SettleTest/` appeared in Photos within ~1 s ("1 new"). Deleting that row was noticed
  within ~1 s ("1 forgotten"), and the count went back to 595. The empty test folder was then
  removed; nothing else was touched.
- **Timeline** with Today / Yesterday / full-weekday headers, video durations, motion-photo and
  favourite badges, and the Photos / Folders bar.
- **Folders.** Favourites and folders with covers and counts. Hiding Movies dropped Photos from
  595 to 591 and marked the folder hidden; showing it again restored 595.
- **Viewer.**
  - Double-tap zoom stays sharp.
  - Video plays with controls (time text readable after giving the viewer a dark theme).
  - The details sheet shows name, date, size, resolution, folder and EXIF location (read from
    the original); the geo link resolves to the system app chooser.
  - Share opens the system sheet with a preview, so the read grant works.
- **Motion photos.**
  - Samsung's files carry Google Motion Photo v1 XMP next to the SEF trailer.
  - Letting Media3 open the whole JPEG took ~1.8 s before the clip started; its event log shows
    the time goes into reaching the embedded MP4.
  - Settle now does what the Motion Photo spec suggests and other galleries (e.g. Aves) do. It
    reads `Item:Length` from the XMP, checks for an MP4 `ftyp` box at file size minus that length,
    and plays only that byte range.
  - Tap to first decoded frame: 221 ms the first time, ~117 ms after. Nothing is prepared in the
    background.
  - The clip is muted and drawn on a TextureView that shows only from its first frame, so the
    still never turns black.
- **Favourites** sync both ways with Samsung Gallery. A favourite set in Settle (written as
  `IS_FAVORITE=1`) showed in Samsung Gallery. The owner removed it there, and Settle updated.
- **English.** Photos, Folders, Viewer and details render correctly; app language reset to system
  afterwards.

Findings:
- About 7,200 WhatsApp files (private chats, sent copies, stickers) sit under folders with a
  `.nomedia` marker. MediaStore lists them with `media_type=0`, so Settle, like Samsung Gallery,
  doesn't show them. Whether Settle should ever include such folders is a later decision.
- The adb snapshot taken while planning reported no favourites and no rows without a date taken.
  Both counts were wrong (a shell quoting error); one WhatsApp image in a `.nomedia` folder was
  already a favourite.

Not done or deviations from the plan:
- The grid-to-viewer shared-element transition is deferred to a polish pass: it couldn't be
  checked on the phone and risks fighting Telephoto's gestures.
- Scans trigger from the activity's `onResume` instead of `ProcessLifecycleOwner` (no extra
  dependency).
- `coil-video` isn't used: MediaStore thumbnails cover videos.
- The bottom bar landed with Folders instead of with the timeline, to avoid a placeholder tab.

Known issues:
- `adb install` (streamed) stalls at random from WSL. Once it sat in Google's package
  verification for 183 s, and other times it never reached the phone's installer. The owner
  turned off "Verify apps over USB". Copying the APK with `adb push` and running `pm install`
  takes ~1 s and has worked every time (see CLAUDE.md).
- On API 35 devices favourites live only in Room (ADR-012).

## Phase 0 — Foundation (2026-10-30)

Delivered:
- Repository on `main`; Gradle 9.8.2, AGP 9.5.0 with built-in Kotlin 2.4.21, compileSdk and
  targetSdk 37, minSdk 35.
- `:domain` (pure Kotlin): `MediaItemId` (ULID) and `deriveStatus()` with 14 JUnit 6 tests.
- `:app`: Compose shell with Material 3 Expressive and dynamic colour, Navigation 3, an empty
  timeline, a settings screen with English / Turkish / system language, adaptive launcher icon with
  a monochrome layer (placeholder glyph), data extraction rules excluding all app data from
  backup and device transfer. 2 Robolectric Compose tests.
- Docs: spec, ADR-001 to ADR-011, this log, CLAUDE.md.

Verified:
- `./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug assembleDebug ktfmtCheck`: all
  green, lint "No issues found".
- Installed on the Galaxy S24 FE (Android 17). Timeline and settings render in Turkish and English;
  the language switch works and shows in system per-app language settings. Reset to system
  default afterwards.

Not done or deviations from the plan:
- No release signing or R8 config yet; added when the first release build is needed.
- Installing uses `./gradlew installDebug` instead of a custom script.
- Material 3's new `LargeFlexibleTopAppBar` not used yet; the timeline gets its real layout in
  Phase 1.

Known issues:
- The ktfmt Gradle plugin 0.27.0 uses `Configuration.setVisible`, deprecated for Gradle 11.
  Harmless until then; watch for a plugin update.
- `ui-test-junit4` pulls Espresso 3.5.0, which fails on API 37; Espresso 3.7.0 is pinned for
  local tests.
