# Phase log

Each phase ends with something that runs on the phone, a build, tests, and a check-in before the
next phase. Newest first.

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
