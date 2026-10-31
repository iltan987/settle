# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Settle is a personal Android gallery that uses the owner's Google Drive as the cloud half of one
photo/video library: delayed backup, verified byte-for-byte uploads, previews-only on the phone,
delete-once with a recycle bin. Target device: Galaxy S24 FE, Android 17 (API 37), One UI 9.
Read `docs/spec.md` for what it must do and `docs/decisions/` for why things are the way they are.

## Commands

```bash
./gradlew :domain:test                       # pure-Kotlin rules (JUnit 6), seconds
./gradlew :app:testDebugUnitTest             # Android code on Robolectric (JUnit 4)
./gradlew :domain:test --tests 'com.iltan.settle.domain.MediaItemIdTest'
./gradlew :app:testDebugUnitTest --tests 'com.iltan.settle.SettleAppTest.startsOnEmptyTimeline'
./gradlew :app:lintDebug                     # report: app/build/reports/lint-results-debug.txt
./gradlew ktfmtFormat                        # format; ktfmtCheck fails the build on drift
./gradlew assembleDebug && adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/s.apk \
  && adb shell pm install -r /data/local/tmp/s.apk && adb shell rm /data/local/tmp/s.apk
                                             # install com.iltan.settle.debug (add -s <device>);
                                             # `installDebug` and `adb install` stalled over USB
```

Debug-build helpers on the phone:
- `adb shell am broadcast -a com.iltan.settle.debug.BACK_UP -p com.iltan.settle.debug --el
  media_store_id <id>` queues a backup of that MediaStore item (Settle must be in front: a cached
  background process has no network on Android 15+).
- `adb shell am broadcast -a com.iltan.settle.debug.MEASURE -p com.iltan.settle.debug --es ids
  <id,id,...> --ei parallel <n> --es protocol h1|h2` uploads test items N at a time and logs
  throughput and delay (`DebugMeasure`). It refuses anything outside `Pictures/SettleTest/`.
- `adb logcat -s DriveHttp BackupRunner BackupScheduler BackupJobs BackupEngine LibrarySync` shows
  each Drive request, the plan, every run with its pace (slots, cap, delay), and failures.
- Jobs: `adb shell dumpsys jobscheduler com.iltan.settle.debug` (IDs 1 automatic, 2 requested,
  3 media trigger, 4 maintenance). `adb shell dumpsys battery unplug` / `reset` simulates the
  charger for the automatic job; always `reset` afterwards. Settings has a 1-minute grace period in
  debug builds.
- Test media: push generated files to `/sdcard/Pictures/SettleTest/`, then send
  `MEDIA_SCANNER_SCAN_FILE` for each so MediaStore fills in their size. Select them only with
  `relative_path = 'Pictures/SettleTest/' AND _display_name GLOB '<prefix>_[0-9]*'`; in SQL `LIKE`,
  `_` matches any character and once picked real screenshots.
- Settle's database: `adb exec-out run-as com.iltan.settle.debug cat databases/settle.db` (copy
  `-wal` and `-shm` too).

Full check before calling anything done:
`./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug assembleDebug ktfmtCheck`

The phone is reached over Wi-Fi with wireless debugging, as
`adb-R5CY649XR5B-2dXbI4._adb-tls-connect._tcp` (adb finds it by itself while wireless debugging is
on; `adb connect <ip:port>` from the phone's Wireless debugging screen otherwise). Since WSL uses
mirrored networking, pushes over the USB link (usbipd) hang; the cable is only for charging.
An emulator `settle_api37` (Android 17 / API 37, Google Play image, Pixel 8 profile) runs on
Windows (Hypervisor Platform; nested KVM inside WSL was too slow) and holds no real photos. It is
signed in to the owner's own account; there the debug build uses `Settle (emulator)/` in Drive,
apart from the phone's `Settle (debug)/` (ADR-008). Use it for anything that deletes files or needs real
device tests, and the phone for Samsung behaviour and final checks. Start it from WSL with
`powershell.exe -NoProfile -Command "\$env:ANDROID_SDK_ROOT='C:\Users\icaner\AppData\Local\Android\Sdk'; Start-Process 'C:\Users\icaner\AppData\Local\Android\Sdk\emulator\emulator.exe' -ArgumentList '-avd','settle_api37','-gpu','host','-no-boot-anim'"`.
WSL runs in mirrored networking (`networkingMode=mirrored` in the Windows `.wslconfig`), so the
emulator registers with WSL's adb on its own as `emulator-5554`. With both attached, every adb
command needs `-s <phone>` or `-s emulator-5554`. Screenshots:
`adb exec-out screencap -p > shot.png`. Samsung's Secure Folder makes some `pm` commands print a
harmless "user 150" SecurityException.

## Architecture

- `:domain` is pure Kotlin JVM and must never depend on Android (ADR-007). All sync, verification,
  eviction and deletion rules live here as pure functions and policies behind ports (interfaces for
  Drive, MediaStore, storage, clock), so they are tested with fakes on the JVM.
- `:app` is the Android app: Compose UI under `ui/`, Room 3 under `data/db/`, the MediaStore
  adapter and `LibrarySync` under `data/media/`, and later the Drive REST client and background
  jobs. Dependencies are wired by hand in `AppGraph`, reached through `Context.appGraph`.
- The local index follows MediaStore by generation with ID-diff deletions, only with full media
  access (ADR-012). `reconcileScan()` in `:domain` decides inserts, updates, moves and forgets.
- An item's state is independent sub-states (`LocalState`, `CloudState`, trash, grace timer);
  `deriveStatus()` turns them into the one status the UI shows (ADR-002). Safety rules are
  invariants over these states and are checked in tests.
- Item identity is a ULID (`MediaItemId`) stored in Room and in Drive `appProperties`; SHA-256 is
  the content identity (ADR-001). Drive is the only restore source; app data is excluded from
  Android backup and Smart Switch (ADR-003, `res/xml/data_extraction_rules.xml`).
- Debug builds (`.debug` app ID suffix) use their own OAuth client and the `Settle (debug)/` Drive
  folder (ADR-008).
- Drive: `DriveAuth` (AuthorizationClient, `drive.file` only, no tokens stored), `DriveRest` (OkHttp
  REST client), `BackupEngine` in `:domain` (ADR-013).
- Background backup (ADR-014): the pure `planBackup` (`:domain`) decides what and when, and
  `BackupScheduler` turns that into JobScheduler jobs run by `BackupJobService`. `BackupRunner`
  runs one engine per upload slot, paced by `UploadPacer` (`:domain`) through `UploadMeter`'s shared
  throttle. Uploads use their own HTTP/1.1 connections. No WorkManager. The
  Google Cloud project "Settle" holds the Android OAuth client (package + debug SHA-1); nothing
  from it lives in the repo. Its consent screen is published, with the home page and privacy
  policy on the `gh-pages` branch: keep `privacy.html` true when Settle's data use changes.

Build specifics that aren't obvious:
- AGP 9 built-in Kotlin: no `kotlin-android` plugin. The Kotlin version comes from declaring the
  Kotlin plugins in the root `build.gradle.kts`, which lifts AGP's bundled KGP 2.2.10 to the
  catalog version.
- `material3` is pinned to a 1.5 beta over the Compose BOM for Material 3 Expressive (ADR-009).
- Local Compose tests need the Robolectric JDK `--add-opens` list in `app/build.gradle.kts` and
  Espresso 3.7.0 pinned (the 3.5.0 from `ui-test-junit4` breaks on API 37).
- Room 3 runs on the framework `AndroidSQLiteDriver`, which also works under Robolectric for DAO
  tests. Exported schemas in `app/schemas/` are committed.
- Media3's Compose UI is unstable/experimental API; keep its use inside `ui/viewer/MediaPlayer.kt`.
- ktfmt is applied per module (kotlinlang style), as the plugin requires.

## Working rules (from the owner)

- **Safety of photos first.** Never delete or trash files on the phone or in Drive, change phone
  settings, or install system-wide software without asking. Destructive logic must have automated
  tests and is tried on the debug build with test media and the debug Drive folder first.
- **Phases.** Work in the phases in `docs/phases.md`; each ends with something running on the
  phone. Build and run the full check before saying done, report failures honestly, update
  `docs/phases.md`, and check in before starting the next phase. Plan each phase before coding.
- **Research, don't recall.** Check current official docs (developer.android.com, Context7,
  official repos) before choosing an API, library or pattern. Do not use the owner's other
  projects (e.g. `~/projects/avc-bike`) as a reference.
- **CLI first.** Generate with tools and then edit, rather than hand-writing from scratch. Read
  library versions from Maven metadata (`dl.google.com/dl/android/maven2`, `repo1.maven.org`)
  before pinning them in `gradle/libs.versions.toml`; never type versions from memory.
- **Docs move with code.** Change `docs/spec.md` with behaviour. Record significant decisions as a
  short ADR (copy `docs/decisions/ADR-000-template.md`). English and Turkish strings land in the
  same commit.
- **Git.** Work on `main`; branch only for risky experiments. Small focused commits, imperative
  subject, a body explaining why. **No `Co-Authored-By` or any other attribution trailer.** The
  repo-local identity is `İltan Caner <iltancanerr@gmail.com>`; don't change it or the global
  config. Never commit secrets (keystores, `keystore.properties`, OAuth client files, tokens).
