# ADR-014: Background backup as jobs, and polite networking

- Status: accepted
- Date: 2026-10-31

## Context
- Since Android 15 a cached app has no network (ADR-013), so uploads must run inside a job or a
  foreground service.
- **User-initiated data transfer (UIDT) jobs**:
  - have no quota and no fixed runtime cap, and may require Wi-Fi and the charger;
  - need `RUN_USER_INITIATED_JOBS` and a notification within 10 s;
  - can only be scheduled while the app is visible, and are "NOT for automatic features";
  - stop for good if the user stops them in Task Manager.
  (developer.android.com/develop/background-work/background-tasks/uidt, JobInfo.Builder)
- **Regular jobs**:
  - run for at most 10 minutes;
  - have quotas by standby bucket: active 20 min per hour (Android 16), working set 10 min per 4 h;
  - charging lifts those limits;
  - since Android 16, jobs running while the app is on top also count against quota; Android 17
    changed nothing here.
  (developer.android.com/topic/performance/power/power-details, Android 16/17 behaviour changes)
- **Content-URI trigger jobs** still exist; they can't be persisted.
- **WorkManager 2.12** has no UIDT support. A worker's `setForeground` from the background can be
  refused.
- **Live Updates** (promoted notifications) are meant for user-initiated, time-sensitive
  activities; Samsung's Now Bar reviews apps one by one.
- **Play policy** allows `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only for a short list of app types,
  and backup isn't one of them. Samsung documents an intent for its sleeping-app lists
  (developer.samsung.com/mobile/app-management.html).
- **Drive limits**: about 3 sustained writes per second per account, and 750 GB per day; rate
  limits answer 403/429. rclone defaults to 4 transfers and 8 MiB chunks, with HTTP/2 off.
- **Android doesn't know the internet uplink.** `getLinkUpstreamBandwidthKbps` is the Wi-Fi link.
  LEDBAT (RFC 6817, behind BitTorrent µTP and Windows BITS) uses delay above the lowest seen as the
  sign that a queue is filling.
- **Measured on the phone** (owner's line about 50 Mbps up; Wi-Fi 6):

  | Test | HTTP/2 | HTTP/1.1 |
  |---|---|---|
  | 12 × 4 MiB, one upload at a time | 1.5 MiB/s | 1.8 MiB/s |
  | 12 × 4 MiB, 4 at once | 3.9 MiB/s | 3.8 MiB/s |
  | 12 × 4 MiB, 6 at once | — | 4.4 MiB/s |
  | 64 MiB file, one upload | 4.95 MiB/s (41.5 Mbps) | 4.7 MiB/s |
  | 2 × 64 MiB, 2 at once | — | 5.0 MiB/s |

  - A ping from a PC on the same network stayed at a 70–71 ms median in every run, even at full
    upload: this router keeps its queue short.
  - Drive's smallest request (`about`) took 200–500 ms on Google's side, so it is useless as a delay
    signal. The round trip to `www.googleapis.com` was 39–56 ms (±5 ms).

## Decision
- **JobScheduler only**, one `BackupJobService` for four jobs. WorkManager would add nothing we need:
  Room already holds every bit of state.
  - **Automatic**: a regular persisted job.
    - Default conditions are unmetered and validated Wi-Fi, charging, and battery not low. In
      practice that means overnight, without a notification.
    - Its minimum latency is the next grace end.
  - **Requested** ("Back up now", "Back up everything now"): a UIDT job, scheduled from the visible
    app.
    - It ignores the grace period and the charger, but still needs Wi-Fi unless mobile data is
      allowed.
    - It shows one silent progress notification with Pause.
  - **Media**: a content-URI trigger on images and videos. It runs a library scan when photos
    arrive while Settle isn't running, then re-arms itself.
  - **Maintenance**: every 6 hours. It lifts the Drive-full pause once there is space, and plans
    again.
- **The plan is a pure function**, `planBackup`. Its inputs are:
  - the folder rules: only `DCIM/Camera/` by default, plus the user's switches;
  - the grace period, counted from MediaStore `DATE_ADDED` (24 h by default);
  - retry waits per problem; a checksum mismatch is retried once, then needs the user;
  - explicit requests, which are kept in Room until the item is verified.

  The app's `BackupScheduler` turns the plan into jobs. It plans again on:
  - app start (a force stop clears jobs);
  - scans that changed the index;
  - setting changes;
  - the end of each run;
  - boot.
- **Uploads run several at once.** There is one engine per slot, and the engines share a folder lock,
  so a month folder is never created twice.
  - Uploads use HTTP/1.1 connections of their own, as rclone does. Each upload gets its own TCP
    window, and the delay probe never waits behind upload data.
  - OkHttp's per-host limit is raised to 16.
- **Polite pacing** (`UploadPacer` in `:domain`):
  - **Delay**: a TCP handshake with Drive's host every 5 s. The quiet delay is the minimum over
    10 min, and the current delay is the minimum of the last 3 samples.
  - **Speed**: measured uncapped, with 4 uploads and over 4 ticks that carry traffic, at the start,
    on a new network, and every 30 min.
  - **Cap**: between 25% and 75% of that speed. It falls by 30% when queueing delay exceeds 100 ms
    or Drive rate-limits, and otherwise rises by 5% of the speed per tick.
  - **Slots**: start at 2; one is added every 6 busy ticks while it raises throughput by 10% or more,
    up to 6. Rate limits halve the slots, and delay takes one away.
  - **Idle ticks change nothing.** New files start at most every 500 ms (below Drive's write limit).
  - The user can choose Polite (default), a fixed Mbps limit, or Unlimited instead.
  - The cap is one shared `okio.Throttler` on the chunk bodies, which also count the bytes sent.
- **Pauses** (R6):
  - "Auth needed" and "Drive full" stop backup as a whole, notify the user, and are shown in
    Settings: Reconnect in the Drive section; Try again for a full Drive.
  - The user can pause too.
- **Reliability setup**: Settings shows notifications, unrestricted battery use (App info) and, on
  Samsung, the Never sleeping apps list, each only while it is still needed.
  - No battery-exemption permission is declared.
  - No promoted notifications are used.

## Verified on the phone (2026-10-31)
- New files waited out a 1-minute grace (debug setting). A file deleted during its grace was
  forgotten and never uploaded.
- With Settle closed, the automatic job started when the charger appeared and uploaded with no
  network block.
- A file added while Settle wasn't running started the media job about 10 s later; the automatic
  run followed after the grace.
- Killed with `kill -9` mid-upload: the next run asked Drive for each session's offset and finished
  without a new session. The SHA-256 values match the local files.
- "Back up everything now" ran with the phone reporting "unplugged" and Settle in the background,
  with the progress notification shown and cleared at the end.
- Pause stopped a running upload job within a second; Resume continued from the server's offsets.

## Consequences
- **Hands-off backup.** New photos go up the night after their grace period, and the user never sees
  a notification unless something needs them.
- **Restarts after a kill.** On this phone, an automatic job whose process was killed mid-run waited
  until conditions changed (the charger state) before it ran again. Maintenance every 6 hours
  bounds that wait.
- **Task Manager.** A user-initiated run stopped in Task Manager stays stopped until the user starts
  one again.
- **The pacer's numbers come from one home network.** Revisit them if backups feel slow or
  intrusive elsewhere.
- **Status in the UI.** The grid shows uploading, backed up and needs attention. The viewer adds
  waiting (with time), queued and not chosen.
- **Amends ADR-011.** Daily items and audits don't use WorkManager; the full Drive audit moves to
  the eviction phase.
