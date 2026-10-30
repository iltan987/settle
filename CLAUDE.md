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
./gradlew installDebug                       # build and install com.iltan.settle.debug over adb
```

Full check before calling anything done:
`./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug assembleDebug ktfmtCheck`

The phone is usually attached over USB and reachable with `adb` from WSL. Screenshots:
`adb exec-out screencap -p > shot.png`. Samsung's Secure Folder makes some `pm` commands print a
harmless "user 150" SecurityException.

## Architecture

- `:domain` is pure Kotlin JVM and must never depend on Android (ADR-007). All sync, verification,
  eviction and deletion rules live here as pure functions and policies behind ports (interfaces for
  Drive, MediaStore, storage, clock), so they are tested with fakes on the JVM.
- `:app` is the Android app: Compose UI under `ui/`, later Room, the Drive REST client, the
  MediaStore adapter and background jobs. Dependencies are wired by hand, no DI framework.
- An item's state is independent sub-states (`LocalState`, `CloudState`, trash, grace timer);
  `deriveStatus()` turns them into the one status the UI shows (ADR-002). Safety rules are
  invariants over these states and are checked in tests.
- Item identity is a ULID (`MediaItemId`) stored in Room and in Drive `appProperties`; SHA-256 is
  the content identity (ADR-001). Drive is the only restore source; app data is excluded from
  Android backup and Smart Switch (ADR-003, `res/xml/data_extraction_rules.xml`).
- Debug builds (`.debug` app ID suffix) use their own OAuth client and the `Settle (debug)/` Drive
  folder (ADR-008).

Build specifics that aren't obvious:
- AGP 9 built-in Kotlin: no `kotlin-android` plugin. The Kotlin version comes from declaring the
  Kotlin plugins in the root `build.gradle.kts`, which lifts AGP's bundled KGP 2.2.10 to the
  catalog version.
- `material3` is pinned to a 1.5 beta over the Compose BOM for Material 3 Expressive (ADR-009).
- Local Compose tests need the Robolectric JDK `--add-opens` list in `app/build.gradle.kts` and
  Espresso 3.7.0 pinned (the 3.5.0 from `ui-test-junit4` breaks on API 37).
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
