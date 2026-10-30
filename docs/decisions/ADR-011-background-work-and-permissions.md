# ADR-011: Background transfers and permissions

- Status: accepted
- Date: 2026-10-30

## Context
- `dataSync` foreground services are capped at 6 h per 24 h for apps targeting Android 15+
  (developer.android.com/develop/background-work/services/fg-service-timeout).
- WorkManager 2.12 has no support for user-initiated data transfer (UIDT) jobs; JobScheduler UIDT
  jobs are exempt from job quotas but can only be scheduled while the app is visible
  (developer.android.com/develop/background-work/background-tasks/uidt).
- `MANAGE_MEDIA` lets trash and delete requests run without a confirmation each time.
  `MANAGE_EXTERNAL_STORAGE` is barred by Play policy for media use cases
  (support.google.com/googleplay/android-developer/answer/10467955).
- One UI puts unused apps to sleep unless the user exempts them.

## Decision
- Large transfers run as a persisted JobScheduler UIDT job (Wi-Fi, charging, battery not low),
  started when the user opens the app. Daily items and audits run as WorkManager work.
- Every transfer is resumable and idempotent; Samsung may kill any of them at any time.
- Permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`,
  `ACCESS_MEDIA_LOCATION`, `MANAGE_MEDIA`. Not `MANAGE_EXTERNAL_STORAGE`.
- A one-time setup screen links to battery "Unrestricted" and Samsung's "Never sleeping apps".

## Consequences
Hands-off daily backup after setup. A very large backlog starts when the app is opened, then runs
unattended across nights.
