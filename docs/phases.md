# Phase log

Each phase ends with something that runs on the phone, a build, tests, and a check-in before the
next phase. Newest first.

## Phase 5 — Design pass: Tidepool (2026-11-01 to 2026-11-02)

The owner chose Claude Design's Tidepool direction (`docs/design/turn4/`, ADR-016). Every screen
was rebuilt to it in six milestones, with the behaviour the designs assume.

Delivered:
- **M1, foundations.**
  - Tidepool's theme: tokens on Material 3 roles, filled by the wallpaper's dynamic scheme on the
    phone, and fixed amber warnings in `SettleColors`. Also bundled Figtree, the handoff sheet's
    type, corners and springs, and Material Symbols Rounded drawables.
  - The designed launcher icon, with a monochrome layer.
  - One backup status for the whole app. `backupPause` and `backupHeadline` live in `:domain`,
    each case tested. `BackupStatusSource` combines the pause, the plan's per-item decisions, run
    progress (n of m, speed, time left), restore progress and live permissions (`DeviceSetup`).
    The chip, the notices, the Backup tab and the navigation dot all read it, and it never says
    "All in Drive" before the first verified upload.
  - App-level messages. One snackbar host sits above navigation, and the item and trash actions
    are remembered at the root, so Undo outlives the viewer. This fixes the Phase 4 deviation.
  - Room schema v6 adds `ui_prefs` (grid density, the delete explainer, onboarding) and dismissed
    notices, which return after a day while their problem lasts.
- **M2, timeline and selection (B, C).** Three tabs on a custom navigation bar, and overlay bars
  of equal height, so selecting moves nothing. Also:
  - notice cards as the grid's first item;
  - cells with the plan's real statuses: Waiting and Queued only while selecting, Not backed up
    only once Drive is connected;
  - pinch for 3–6 columns, a fast scroller with a month bubble, and select-day headers;
  - favouriting a selection in one MediaStore request.
- **M3, viewer and delete (D, E).**
  - The viewer: a black stage, a 56 dp toolbar pill and a one-action status chip. Swipe down
    closes it into its cell (shared element), and swipe up opens Details.
  - Place names from the platform geocoder, for the photo on screen only (ADR-017).
  - Deleting: the one-time E1 explainer, a "Restored" message after Undo, and the viewer reopening
    on the restored photo.
- **M4, trash and mass disappearance (F, G).**
  - The trash grouped by how items left. It has days-left chips, restore rings, the reasons
    sheet, and destructive dialogs.
  - Answered disappearances kept apart as "Disappeared from this phone", and stopped restores that
    resume when Settle comes to the front.
  - G1–G4 on their own screens, with "Check again".
- **M5, Settings, Backup tab, Folders (I, J, H).**
  - Settings as a summary of pages, built from the shared `SettingsList` and `ChoiceSheet`.
  - The Backup tab: state and actions only, with a status header and Uploading, Waiting and Queued
    sections from a pure `backupSections()`.
  - Folders: tiles, folder pages with selection, and an options sheet. Stopping backup for a
    folder with copies in Drive asks first.
- **M6, onboarding (A).**
  - A flow that runs once: welcome with the brand art; the access screens (partial, blocked,
    revoked); Connect Drive with its three promises, saying when Drive access was refused; then
    only the setup steps still needed, with the Samsung Never sleeping guide, which Settings
    shares.
  - "First backup starts tonight" on the timeline until the first verified upload.
- English and Turkish for every new string. The Turkish follows the design's "siz".
- 165 domain tests and 227 app tests.

Verified on the emulator (test account, `Settle (emulator)/`, generated files in
`Pictures/SettleTest/` only), at each milestone, in light and dark against the design frames:
- **Timeline and selection.** Tried on 600 test images: the grid, selecting partial days, the
  count on Back up, the fast scroller and its month bubble, and the chip.
- **Viewer.** Checked the viewer and its chip, and Details in light and dark. Both swipes worked,
  and the photo flew out of and back into its cell (frames captured with animations slowed). The
  delete flow ran explainer → Android's dialog → message → Undo → "Restored". A second delete
  skipped the explainer, and Deny changed nothing.
- **Trash.** A restore from Drive showed its ring and "Restoring 1 of 1", then said it was restored
  and checked. A restore from the phone's trash offered Undo, which worked. A stopped restore
  listed only the reasons that apply.
- **Mass disappearance.** 250 test files were moved out of MediaStore's view while Settle was
  stopped. The breaker tripped, and G1–G4 behaved as designed. Moving the files back resolved the
  question by itself, with every item keeping its ID.
- **Backup tab.** With the test folder's backup on, it showed Waiting 1 and Queued 599. The H4
  confirmation came up when turning that backup off.
- **Onboarding.** The welcome screen appeared. A denial led to A4, with no second system dialog.
  Turning access on and returning led to the setup steps. Allowing notifications left that step
  checked in place, Done opened the timeline, and onboarding stayed finished after a restart.

Found on the emulator, fixed:
- **Dark bars.** In dark mode the text on the bars was black on black.
- **Notice off-screen.** A notice arriving while the grid sat at the top was laid out above the
  screen; the grid now scrolls to show it.
- **Misleading counts.** "0 backed up" with no subtitle and "First backup tonight" with nothing to
  send now read "Nothing to back up yet". "Storage unknown" while Drive was still being read now
  reads "Checking…".
- **Tap targets.** A clickable wrapper on grid cells merged their semantics; the cells now handle
  taps themselves.

Not done or deviations from the plan:
- **Partial access.** It gets a full screen asking for all photos, not A3's partial timeline, because
  the library follows MediaStore only with full access (ADR-012).
- **Welcome art.** It is drawn in Compose, not bundled as a VectorDrawable: its fills are theme
  tokens, and its tiles drift.
- **Viewer, left for later:**
  - the mini-map when zoomed in;
  - frame previews while scrubbing a video;
  - press-and-hold to play a motion photo;
  - Move and Copy to album (they come with albums).
- **Viewer, differs from the design:**
  - While dragging down, the background stays black instead of showing the grid.
  - On the emulator the status bar stays visible with the chrome hidden; to check on the phone.
  - Deleting while an upload runs still finishes the upload and trashes its Drive copy, so "Upload
    stopped" isn't used; cancelling an upload mid-way is planned for later.
- **Brand mark.** The tonal brand mark (`ic_brand_mark`) is not used yet; lint reports it.
- **Small gaps:**
  - The RestoreCard has no 4 s "restored and checked" line.
  - Backup paused shows no "since" time.
  - There is no licences row.
  - Some queued meta and folder messages are worded slightly differently from the design.
  - "From November 3 at 7:26" is used where the design says "tomorrow".
- **Not seen on a device; covered by tests:**
  - place names, since the test images have no GPS;
  - the problem cards and their popover, since nothing was wrong on the emulator during M2;
  - Connect Drive (A5), since the emulator and the phone are already connected;
  - the "First backup starts tonight" card (A9), since both already have verified uploads.
- **Phone check still to do:**
  - Turkish at 130% font scale;
  - place names on a real photo with a location.

Checked on the phone (2026-11-02; test media `undo_1`, `undo_2` in `Pictures/SettleTest/` only):
- **Status bar.** It hides with the viewer's chrome, smoothly.
- **Wallpaper colours.** The owner saw no change, because One UI's Colour palette is off on the phone
  (`wallpapertheme_state=0`, no theme overlay). Android then gives every app the same default
  palette. Settle follows the palette once it's on; the launcher icon keeps its fixed colours by
  design.
- **Delete and Undo in the viewer.**
  - Deleting the folder's only photo closed the viewer, and Undo reopened it on that photo.
  - With two photos, the viewer moved to the next one, and Undo went back to the restored one.
  - Through Settle, Samsung keeps the MediaStore ID, so the item keeps its identity.
- **Restored outside Settle.** A backed-up test image was deleted in Settle. Then its trashed row was
  removed and the identical file put back, which is what Samsung Gallery's restore does to
  MediaStore. Settle first marked it missing, keeping its trash record and its Drive copy. When the
  file came back under a new ID, it matched it to the same item, took the Drive copy out of Drive's
  trash, and uploaded nothing again. A photo never backed up that is restored outside Settle comes
  back as a new item: nothing of it is in Drive, but the viewer can't find it again. That is what
  the owner saw with a camera photo restored while Samsung Gallery was in front.
- **Scrolling, measured while the owner scrolled** (Perfetto and gfxinfo):
  - The debug build had 14.4% late frames; the 99th percentile was 250 ms and the worst 217 ms.
    The worst frames were fast-scroller drags: each move composed a screen of new cells twice (the
    shared-element lookahead), and each cell registered its own shared-element state.
  - The same build made non-debuggable and compiled ahead of time, with only the photo in flight
    taking part in the shared element, had 6.8% late frames, a 99th percentile of 36 ms and a
    worst of 52 ms. The owner felt no lag. Frames over 33 ms went from 84 to 3.
  - The non-debuggable build was a local, uncommitted change for the measurement; the debug build
    is back on the phone. The daily build stays slower until a release build exists.

Found on the phone, fixed:
- **Pinch ran through densities.** A small pinch went from 3 to 4 to 5 columns. `gridGestures`
  repeated the whole modifier chain, so the grid had two pinch detectors and two zoom layers. The
  detector also sat inside the zoom layer and could step again within one pinch. One pinch now
  makes one step.
- **Scroll cost.** Only the photo in flight takes part in the shared element.
- **Never sleeping apps.** The owner opened Never sleeping apps from the setup step and couldn't
  find Settle in Samsung's picker. One UI leaves out every app whose battery use is Unrestricted,
  and such an app is in Android's exempted standby bucket, so it can't be put to sleep. The
  Samsung step now shows only while battery use isn't Unrestricted (ADR-014, R5).

Left on the phone: the debug build with these fixes; Media management is on. The test images
`undo_1.png` (backed up to `Settle (debug)/`) and `undo_2.png` are in `Pictures/SettleTest/`.

Left on the emulator:
- Backup resumed. The test folder doesn't back up (`back_up` is an explicit `false`) and is shown.
- Photo access is granted again and notifications are off, as before the onboarding test.
- The debug build has onboarding recorded as finished.

## Phase 4 — Deletion and the trash (2026-10-31)

Delivered:
- **Research first (ADR-015).** From the API 37 framework sources:
  - `createTrashRequest(…, false)` untrashes, through the same prompt;
  - `DATE_EXPIRES` is set by the system, typically 30 days for trashed rows;
  - at most 2000 URIs per request;
  - AOSP checks `MANAGE_MEDIA` as an app-op alone.
- **`:domain` rules** (`deletion/`):
  - `afterScan` follows the phone after each scan;
  - deleting, restoring, deleting forever, the user's decision, and expiry;
  - `DriveChanges` runs the idempotent queue;
  - `RestoreDownload` downloads into a pending file, resumes, and publishes only matching
    SHA-256/MD5;
  - expired trash no longer counts toward the circuit breaker;
  - invariant tests: no Drive copy is trashed while its original is on the phone unless the user
    deleted it, and nothing outside the trash is deleted for good.
- **Room schema v5**: `trash_entry`, `vanished`, `drive_op`, `restore_request`, and the deletion
  policy. A scan and its trash changes are written in one transaction.
- **`DriveRest`**: trash, untrash, delete, ranged download.
- **Jobs**: 5 runs queued Drive changes on any network; 6 is a user-initiated restore download
  with a notification. Maintenance runs the queue and expiry.
- **UI** (plain Material 3 until the Claude Design pass):
  - multi-select in the grids: long-press, drag, a day's header; share, back up now, delete;
  - Delete in the viewer, with Undo;
  - Trash (from Folders): days left, deleted elsewhere, restoring and why it stopped, Restore,
    Delete forever, Empty trash behind confirmations;
  - the mass-disappearance notice on the timeline;
  - Settings → Deletion (policy, optional Media management);
  - grid badges from `deriveStatus`;
  - English and Turkish.
- `docs/design/brief.md`: the handoff prompts for Claude Design.
- 146 domain tests and 144 app tests, including an end-to-end deletion test on Room with fakes for
  MediaStore and Drive.

Verified on the emulator (`settle_api37`, `Settle (emulator)/`, generated PNGs in
`Pictures/SettleTest/` only):
- **Delete from the viewer without Media management.** Android asked "Allow Settle to move this
  photo to trash?". Deny changed nothing. Allow trashed the phone file, Drive answered 200 to the
  trash PATCH, and the snackbar offered Undo.
- **A file that wasn't backed up.** The message said it is deleted for good after 30 days. Undo
  asked "move this photo out of trash?" and brought it back.
- **Media management** (granted with `appops`). Trash, untrash and delete ran with no prompt, on
  `READ_MEDIA_*` alone.
- **Multi-select.** A long-press and drag selected two items, which were deleted together.
- **Trash screen.** Thumbnails of trashed files load, and each shows 30 days left. Restore untrashed
  the file on the phone and in Drive. Delete forever and Empty trash asked first, then removed the
  files from the phone and from Drive (204) and from the library.
- **Deleted in another app** (`content delete` on a backed-up test row). The item entered Settle's
  trash at once. Its Drive copy was trashed when the debug wait (a minute) had passed.
- **Restore of that item.** Drive untrash, then a download into `Pictures/SettleTest/`. The
  restored file's SHA-256 equals the original's, it was linked to the same item, and its record
  took the new modification time, so no re-upload followed.
- **Offline.** A deletion in airplane mode trashed the phone file at once and queued the Drive
  change. It ran 2 s after the network returned.
- **Media management link.** The Settings row opens Android's "Media management apps" page for
  Settle.

Findings, fixed:
- **Breaker in slices.** Deleting 205 files at once reached Settle as 11 live scans of 10–49 files,
  so the per-scan circuit breaker never tripped. Backed up, those files would have gone to the Drive
  trash an hour later without asking. The hour's wait is now also the breaker's window (ADR-015).
- **Selection jumped.** A long-press swapped the large top bar for a small one, the grid jumped
  under the finger, and dragging selected nothing. The selection bar now keeps the height.
- **Gestures.** A long-press also opened the photo under it, because the cell's click handler took
  the touch. The grid now handles taps and long-presses itself. Its hit test had ignored the top
  content padding; tests caught that.
- **Two runs at once.** The restore job and the change job sent the same untrash request at the
  same moment, and a finishing change job cancelled itself. The queue now runs under one lock.

Verified on the S24 FE (owner approved; four generated test files in `Pictures/SettleTest/` only,
debug build, `Settle (debug)/`):
- **Migration.** The phone's debug database went from schema v3 to v5 on real data. The same 595
  present and 120 missing items; no trash entries, decisions or Drive changes were created.
- **Deleting in Settle.** Samsung's own dialog ("Settle uygulamasının bu fotoğrafı çöp kutusuna
  taşımasına izin verilsin mi?") showed the one test file. After Allow, the Drive copy was trashed.
- **Samsung Gallery's trash** says it shows items deleted from Gallery, My Files and other apps. The
  file Settle trashed appeared there ("29 gün"), and was gone from Gallery's timeline. Restoring it
  there would bring it back in Settle too.
- **Deleting in Samsung Gallery** (opened on the one test file through `ACTION_VIEW`). Samsung's
  delete removes the MediaStore row; its trash is its own, as ADR-006 says. Settle noticed while in
  the background, put the item in its trash as "deleted in another app", and trashed its Drive copy
  about a minute later.
- **Restoring both from Settle's trash.** The one deleted in Samsung Gallery was downloaded into
  `Pictures/SettleTest/`. The one Settle trashed came out of the phone's trash after Samsung's
  untrash dialog. Both are byte-identical to the originals (SHA-256).
- **Galaxy AI.** Samsung Gallery opened through `ACTION_VIEW` from another app shows its AI edit
  button, which answers the spec's open question about "Open in Samsung Gallery".
- **The owner's media is untouched.** The MediaStore listing outside the test folder (IDs, names,
  folders, sizes of all 595 items) is identical before and after. Media management stayed off on
  the phone, and Empty trash was not used there.

Found on the phone, fixed:
- **Samsung's trash folder.** One UI moves a trashed file under `.trash-storage/` and MediaStore
  reports `.trash-storage/Pictures/SettleTest/` as its folder. Settle stored that, so a restore by
  download after the phone's copy expired would have gone into the hidden folder. A trashed row
  now keeps its original folder.

Not done or deviations from the plan:
- **Not tried on the emulator; covered by tests:**
  - the breaker window with backed-up files, which would mean uploading 200+ files to Drive;
  - a killed download resuming.
- **Keep in Drive** (`KEEP_IN_CLOUD`) waits for cloud-only items in the timeline.
- **Undo lost.** Deleting the only item open in the viewer closes it, and the Undo with it.
- **Privacy policy.** It must say Settle now trashes, deletes and downloads its own Drive files and
  moves phone files to the trash. It is updated on the local `gh-pages` branch and not pushed;
  publishing is the owner's call.
- The UI is plain Material 3 and is redone in the design pass.

Left on the phone:
- Samsung Gallery's trash holds the test file `ph_2.png`, a noise image, until Samsung deletes it
  after 30 days.
- Settle's trash lists `ph_1`–`ph_3`, deleted in another app, until they expire. Their Drive copies
  are in the Drive trash, from `Settle (debug)/`.
- `Pictures/SettleTest/` is removed. The debug build is installed with the folder fix. Media
  management is off.

Left on the emulator:
- `del_1`, `del_5` (backed up) and `del_6` in `Pictures/SettleTest/`, and the Drive copies of
  `del_1` and `del_5` in `Settle (emulator)/`.
- Media management reset to default.

## Review fixes after Phase 3 (2026-10-31)

A code review of Phases 0–3 found these; each has its own commit and tests:
- **Checksum wait.** A copy whose SHA-256 Drive hadn't reported yet was ready again at once, so
  jobs followed each other without a pause. It now waits 15 minutes, doubling up to 6 hours, and
  its request ends.
- **Emulator reset.** Resetting the debug Drive folder crashed on the emulator, whose folder is
  `Settle (emulator)/`. Both debug folders are accepted; nothing else ever is.
- **Start-up race.** Settle's start-up planning could replace an automatic job the system had just
  started. The scheduler now asks JobScheduler whether that job is executing.
- **Viewer status.** A request showed an endless spinner while backup was paused, blocked or
  refused by Android. The viewer shows the reason instead, and a refused job says it waits for the
  next automatic backup.
- **Replaced Drive files.** The ID of a copy replaced by a newer upload was lost. It is now kept
  in `superseded_file` (schema v4) for superseding and the audit.
- **Stale problem.** A retried upload no longer shows the problem of its last stop.
- **Settings lock.** Settings changes from a run and from the screen can't undo each other.
- First tests for `BackupScheduler`: 100 domain and 93 app tests in total.

Not done:
- Not tried on the phone or the emulator; the fixes are covered by JVM and Robolectric tests.
- A review finding that the notification's Pause left the automatic job scheduled was wrong:
  Pause cancels both upload jobs.

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
