# ADR-007: Two Gradle modules and manual dependency injection

- Status: accepted
- Date: 2026-10-30

## Context
Sync, verification and deletion rules must be heavily tested and must not depend on Android.
This is a one-person project; more modules mean more Gradle time and ceremony.

## Decision
- `:domain` is a pure Kotlin JVM module: models, policies, state machines and ports (interfaces
  for Drive, MediaStore, storage, clock). Fakes for tests live with it. It must never depend on
  Android.
- `:app` is the Android application: Compose UI, Room, the Drive REST adapter, the MediaStore
  adapter and background jobs, organised by package.
- Dependencies are wired by hand in one app-level object. Hilt (or Metro) can be introduced if the
  graph outgrows that.

## Consequences
The compiler keeps Android out of the rules, and domain tests run in seconds. Adapters in `:app`
are tested with Robolectric and MockWebServer.
