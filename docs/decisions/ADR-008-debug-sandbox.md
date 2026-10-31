# ADR-008: Debug builds use a separate app ID and Drive sandbox

- Status: accepted
- Date: 2026-10-30

## Context
Upload, verification and deletion logic must be tried against test data before it touches the
real photo library.

## Decision
- The debug build type uses the application ID suffix `.debug`, so it installs next to the release
  app with its own data.
- It gets its own OAuth client (debug keystore SHA-1) and writes only under `Settle (debug)/` in
  Drive. A debug-only menu can wipe that folder.
- Amended 2026-10-31: a debug build on an emulator (hardware `ranchu`) writes under
  `Settle (emulator)/` instead. The test emulator is signed in to the owner's own account, and
  `drive.file` grants per app, so without this it would share `Settle (debug)/` with the phone,
  including the debug copies of real photos.
- Destructive behaviour runs on the debug build with test media first. The release build touches
  the real library only after the user agrees.

## Consequences
Two OAuth clients to register. Debug and release libraries never mix.
