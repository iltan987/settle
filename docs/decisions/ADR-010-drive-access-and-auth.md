# ADR-010: Drive access, auth and the upload client

- Status: accepted
- Date: 2026-10-30

## Context
- `drive.file` and `drive.appdata` are non-sensitive scopes; no Google verification is needed
  (developers.google.com/workspace/drive/api/guides/api-specific-auth).
- An OAuth consent screen left in "Testing" expires refresh tokens after 7 days
  (support.google.com/cloud/answer/15549945).
- Google Sign-In was removed from `play-services-auth` 22.0.0; authorization uses
  `AuthorizationClient` (developer.android.com/identity/authorization).
- The official Java Drive client is heavy and its uploader buffers chunks in memory with weak
  resume across process death.

## Decision
- Scopes: `drive.file` only (add `drive.appdata` only if a use appears). Consent screen External,
  published to production without verification.
- Drive tokens from `AuthorizationClient`, which renews them silently; a background job that
  needs user interaction posts a notification instead. Amended 2026-10-31 (ADR-013): no
  Credential Manager sign-in and no Web client; `authorize()` shows the account chooser itself.
- A small hand-written Drive v3 REST client over OkHttp: resumable uploads in chunks that are
  multiples of 256 KiB, session URI and offset persisted in Room, `files.generateIds` for
  idempotent creation.

## Consequences
No refresh tokens stored by Settle. Re-sign-in is needed only after revoking access or six months
unused. The REST client is ours to test, against MockWebServer.
