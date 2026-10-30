# Phase log

Each phase ends with something that runs on the phone, a build, tests, and a check-in before the
next phase. Newest first.

## Phase 1 — Local gallery (2026-10-30, on-device verification incomplete)

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
  - motion-photo playback;
  - favourite (mirrored to MediaStore), share, details with EXIF location.
- Folders tab with Favourites, per-folder "Show in Photos", folder and favourite grids.
- Settings: library count, last scan, rescan.
- 36 domain tests and 26 Robolectric tests (DAO, sync, MediaStore mapping, onboarding, timeline,
  folders).

Verified on the S24 FE:
- **Onboarding** renders in Turkish. The system dialog offers limited / all / deny. "Allow all"
  grants images, video and media location in one step.
- **Scanning.** The first scan indexed 595 items (502 photos, 91 videos, 2 motion photos) in
  265 ms; that matches the adb count. Incremental rescans take ~230 ms.
- **Timeline** with Today / Yesterday / dated headers, video durations and the motion-photo badge.
- **Video** playback with controls.
- **Motion photos** play: Samsung's files carry Google Motion Photo v1 XMP next to the SEF
  trailer. The first try took ~5 s to start, a replay ~1.7 s. Passing the MIME type to Media3
  should cut this; that fix is not yet measured on the phone.

Not yet verified (the phone was locked; these need the owner present):
- Motion-photo start time after the MIME fix, and the still staying visible until the first
  frame.
- Zoom on a large photo, the info sheet location, sharing.
- Favourites: the MediaStore mirror, and whether Samsung Gallery shows them.
- Folders tab and hiding WhatsApp from Photos.
- A new screenshot appearing live.
- Deletion of a test file pushed over adb.
- The partial-access path.
- English screenshots.

Not done or deviations from the plan:
- The grid-to-viewer shared-element transition is deferred to a polish pass: it couldn't be
  checked on the phone and risks fighting Telephoto's gestures.
- Scans trigger from the activity's `onResume` instead of `ProcessLifecycleOwner` (no extra
  dependency).
- `coil-video` isn't used: MediaStore thumbnails cover videos.
- The bottom bar landed with Folders instead of with the timeline, to avoid a placeholder tab.

Known issues:
- Installing over adb hung twice while the phone was locked and dozing (once through
  `installDebug`, once through `adb install -r`); a third install in the same state succeeded.
  The cause is unknown; wrap installs in `timeout` and retry with the phone unlocked.
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
