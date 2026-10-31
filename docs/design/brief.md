# Claude Design brief

The owner runs these prompts in Claude Design to redesign Settle's UI from scratch. The result
(HTML or React, screenshots and the handoff sheet) goes in this folder, and a design pass rebuilds
the screens in Compose from it.

**How to use it:**
1. Paste **Message 1** into a new Claude Design project, with no screenshots so it isn't anchored on today's UI.
2. Pick one of its three directions.
3. Send Messages 2–4 one at a time. Message 2 holds the screens Phase 4 needs.
4. Export the HTML or React and the handoff sheet into `docs/design/`.

## Message 1: master brief

```
# Design brief: Settle, a calm and trustworthy Android gallery

## Your role
You are a senior product designer who specialises in Android and Material 3 Expressive. Design the
complete UI and UX of Settle from a blank page. Ignore any existing app; nothing has to be kept.
Your design will be rebuilt by an engineer in Jetpack Compose with Material 3 Expressive
(androidx material3 1.5), so every element must map to a Material 3 component or a simple
composition of them. Aim for an app that feels beautiful, effortless and quietly confident: the
kind of gallery people keep on their home screen because it is pleasant to open.

## The product
Settle is a personal Android gallery that treats the phone and the owner's own Google Drive as one
photo and video library. Promise: "Your phone and your Drive as one library. Backed up carefully,
deleted once, never lost."
- Photos are backed up to a "Settle" folder in the user's Google Drive, byte for byte, and each
  copy is verified (size and checksums) before Settle calls it safe.
- Backup is delayed on purpose: new photos wait (24 hours by default) so that the shots the user
  deletes soon never reach the cloud. It runs at night on Wi-Fi and the charger, unnoticed.
- Deleting once deletes everywhere: the phone file and the Drive copy both go to a trash for 30
  days and can be restored.
- Later, originals of old photos leave the phone to free space; the phone keeps a preview and
  downloads the original on demand ("cloud only").

The three feelings the design must create:
1. Photos first. The app gets out of the way; chrome is light, content is large.
2. Quiet confidence. At a glance the user knows whether things are safe in Drive, without the grid
   turning into a wall of icons. Problems are visible, calm, and always say what to do.
3. Safe to act. Deleting is easy and undoable. Anything irreversible is unmistakable.

## The user
One owner, technically literate, uses Settle daily on a Samsung Galaxy S24 FE: 6.7" portrait,
about 412 dp wide, gesture navigation, Android 17. Library today: about 600 items on the phone
(500 photos, 90 videos, a few motion photos), growing to tens of thousands. The UI is in English
and Turkish; Turkish short labels run up to 40% longer ("Photos" → "Fotoğraflar", "Show in Photos"
→ "Fotoğraflar'da göster"), so leave room and never truncate actions.

## Hard constraints
- Material 3 Expressive. Prefer its components: flexible top app bars (large, collapsing on scroll),
  short navigation bar, floating toolbar, FAB menu, button groups, split button, loading indicator
  (shape-morphing) and wavy progress indicators, M3 shapes (the shape library) and the expressive
  spring motion scheme, grouped/segmented lists, modal bottom sheets, snackbars, chips, dialogs.
  If you need something M3 lacks, say so and keep it a simple composition.
- Colour comes from the user's wallpaper (dynamic colour). Use M3 colour roles only (primary,
  secondaryContainer, surfaceContainerHigh, error…), never fixed brand hues in UI. Show every screen
  with one sample wallpaper palette, and the key screens with a second very different one to prove
  it holds. Light and dark themes.
- Never convey status by colour alone: every status has an icon and text somewhere.
- Edge to edge: content draws behind the status and navigation bars. Only the photo viewer hides
  the system bars.
- Icons: Material Symbols Rounded; filled for selected or "on" states.
- Type: the M3 type scale (system font), emphasised styles allowed for headlines.
- Touch targets at least 48 dp, WCAG AA contrast, layouts survive 130% font scale.
- No hamburger drawer, no iOS idioms, no glassmorphism or heavy blur, no lorem ipsum.

## Vocabulary: an item's status (exactly one at a time)
| Status | Meaning |
|---|---|
| Not backed up | On the phone; its folder isn't chosen for backup |
| Waiting | On the phone, inside the waiting period ("backs up in 5 hours") |
| Queued | Ready; goes with the next automatic backup |
| Uploading | Being uploaded (with progress when known) |
| Backed up | Verified in Drive |
| Cloud only | Only a preview on the phone; the original is in Drive |
| In trash | Deleted; restorable for N more days |
| Needs attention | Something is wrong; says what to do |
Backup as a whole can also be paused, with a reason: paused by the user, Drive not connected,
Drive full, Google needs the user to sign in again, or location access for originals missing.
Design how these show on a grid cell (must stay subtle: most items are simply "Backed up" and
should look clean), in the viewer, and in an app-level status surface.

## Screens and flows (details come in later messages)
A. Onboarding and photo access (full / only some photos / blocked), connecting Google Drive.
B. Photos timeline: day sections, dense grid, fast scrolling through years, status badges,
   banner slot for important notices, empty and loading states.
C. Selection mode: long-press, drag to select, select a whole day; actions on the selection.
D. Photo and video viewer: swipe, zoom, video, motion photos, favourite, share, delete, details.
E. Delete with undo; deleting something that isn't backed up yet.
F. Trash: restore, delete forever, empty trash, items deleted in another app.
G. Mass disappearance: hundreds of files vanished at once; ask before touching Drive.
H. Folders, Favourites, a folder's page, per-folder options.
I. Settings: library, Google Drive, backup, deletion, background setup steps, language, about.
J. Backup activity: progress of a running backup, pause and resume, reasons it is paused.
K. Later (lower fidelity): cloud-only items, restore after reinstall, "free up space" preview,
   albums and pinned items.
L. App icon (adaptive, with a monochrome themed variant) and a small brand mark.

## Copy rules
Plain, short, calm, sentence case. Say what happened and what the user can do. Never blame, no
exclamation marks, no jargon on the surface (checksums appear only in a details view). Say
"Nothing was lost" when it is true. Where I give strings, use them; if you can improve one, show
your version marked "proposed".

## Deliverables
1. Now: three clearly different visual directions, each shown on the Photos timeline, the viewer
   and the Trash, in light and dark. Give each a name and a short rationale (what it optimises
   for, its risks). Then stop and wait for my choice.
2. Later messages: the full screen set for the chosen direction as a clickable prototype at 412 dp
   width, every state as its own frame.
3. A handoff sheet: colour roles used (as M3 role names, not hex), type roles, shape and corner
   tokens, spacing scale, per screen the M3 component names with sizes in dp, motion notes (what
   animates, which spring), and every string.
Build prototypes in HTML/React with M3 token names as CSS variables (for example
--md-sys-color-surface-container-high) so the mapping to Compose is direct.

## Before you start
If anything is ambiguous, ask me up to five questions. Otherwise list your assumptions in a few
lines and go straight to the three directions.
```

## Message 2: the screens Phase 4 needs (after you pick a direction)

```
Direction <name> it is. Now design these in full detail, every state as its own frame, light and
dark, with the handoff notes for each.

B. Photos timeline
- Day headers ("Today", "Yesterday", "Friday, 31 October"; other years add the year). A fast
  scroller with month and year labels for libraries of 30,000 items. Pinch to change density
  (3 to 6 columns), with 4 as default.
- Grid-cell status: design a subtle system. Backed up = the clean default (little or nothing);
  Waiting, Queued, Uploading, Not backed up, Needs attention, Cloud only must be distinguishable
  when you look for them, invisible noise when you don't. Plus video duration, motion photo and
  favourite markers that don't collide with status.
- A top-of-timeline notice for app-level situations (Drive full, reconnect needed, mass
  disappearance pending), dismissible where it makes sense.
- Loading, empty ("Nothing here yet"), and a first-run state before Drive is connected.

C. Selection mode
- Enter by long-press; drag across cells to extend; tap a day header to select or clear that day.
- A count ("12 selected") and actions: Delete, Back up now, Share; Favourite optional. Use the
  floating toolbar or top bar, your call; justify it.
- What happens if the selection mixes backed-up and not-backed-up items.

D. Viewer
- Immersive black stage; tap toggles chrome. Swipe between items; pinch and double-tap zoom.
  Video with scrubber; motion photo "Play motion"; favourite, share, delete, details.
- Backup status in the viewer: current status, progress while uploading, "Back up now" when it
  makes sense, the pause reason when paused.
- Details sheet: file name, date and time, size, resolution and type, folder, location (an address
  line and a small map thumbnail), and a backup section: "Backed up to Google Drive · Size and
  checksums match the original · Open in Google Drive".

E. Delete
- From the viewer and from a selection. Result: "Moved to trash" snackbar with Undo.
- Android may show its own system confirmation dialog before Settle can trash files unless the
  user granted "Media management" access. Design the moment that explains this once, with a path
  to grant it (it is optional).
- Deleting items that are only on the phone and not backed up: say it plainly before or after
  ("3 of these aren't backed up. They stay in the trash for 30 days, then they're gone.").
- Deleting while an item is uploading.

F. Trash
- Reached from Folders (a Trash tile) and from the snackbar. Grid with "N days left" per item,
  grouped by when they were deleted. An explainer: "Items stay here and in your Drive's trash for
  30 days, then they're deleted for good."
- Actions on one item or a selection: Restore, Delete forever. A top action: Empty trash.
- Irreversible confirmations: "Delete 12 items forever? They'll be removed from this phone and
  from Google Drive. This can't be undone." (error-coloured confirm button).
- A section "Deleted in another app": items the user deleted in Samsung Gallery. Their Drive copy
  is in Drive's trash; Restore downloads it back to the phone and checks it. Show download
  progress and the success and failure states ("Couldn't download it. It's still in your Drive's
  trash for 18 days.").
- A setting-dependent variant: "Ask me" mode lists items deleted elsewhere with two choices per
  item or for all: "Move Drive copies to trash" / "Keep in Drive".
- Empty trash state.

G. Mass disappearance
- Shown when, in one check, hundreds of files vanished at once (often a removed SD card, a
  revoked permission or a system rebuild, not the user deleting). Nothing has been changed in
  Drive. Copy draft: "312 photos and videos disappeared from this phone at once. Their Drive
  copies are untouched." Choices: "Keep Drive copies" (safe default), "Move Drive copies to
  trash", "Check again", and a way to see which items.
- It must feel serious but not alarming, and stay until answered (survives closing the app).
```

## Message 3: the rest of today's app

```
Next, design these for the same direction, with handoff notes.

A. Onboarding: welcome and full photo access ("Settle shows every photo and video on this phone in
   one place. To do that it needs access to all of them. Nothing leaves your phone until you
   connect Google Drive."); the "only some photos" state with "Allow all"; access blocked with a
   path to settings. Then connecting Google Drive (Google shows its own account picker) and a
   short setup for reliable background backup: notifications, unrestricted battery use, Samsung's
   "Never sleeping apps". Each step shows only while needed; all are skippable.

H. Folders: Favourites, Trash and every folder with cover and count; hidden folders marked. A
   folder's page. Per-folder options: "Show in Photos" and "Back up" switches (only the camera
   folder backs up by default).

I. Settings, grouped and scannable:
- Library: "602 photos and videos on this phone", last checked, Rescan.
- Google Drive: not connected ("Back up originals to your own Google Drive. Settle only sees the
  files it creates there." + Connect); connected (account, storage meter "50 GB of 5.5 TB used",
  folder "Settle"); reconnect needed; Disconnect with confirmation ("Files already in Drive stay
  there. Backups stop until you connect again.").
- Backup: status summary ("256 backed up · 3 waiting"), items needing attention, "Back up
  everything now" (skips the waiting time and the charger), Back up automatically, Wait before
  backing up (no wait, 1 hour, 24 hours, 3 days, 7 days), Only while charging, Use mobile data,
  Network use (Polite: leaves room for others on your network / Up to 5, 10, 20, 40 Mbps / As fast
  as possible), Folders to back up, Pause and Resume.
- Deletion: "When you delete in another app": Move the Drive copy to trash (default) / Ask me.
  "Delete without asking each time" (Media management access), shown only while not granted.
- Keep backup running (setup steps, only while needed), Language (System default, English,
  Türkçe), About and version.

J. Backup activity: where the user watches "Back up everything now" (12 of 40, speed, time left),
   pauses and resumes it, and sees why backup is paused with the one action that fixes it. Decide
   whether this is a sheet from a status element in the timeline's top bar or something else; it
   must not compete with the photos.
```

## Message 4: later screens and the icon

```
Finally, at lower fidelity but in the same system:

K1. Cloud-only items in the timeline (preview with a cloud marker) and opening one: the preview
    shows at once, the original downloads with progress, then swaps in.
K2. Restore after a reinstall: connect Drive → "Found 2,413 photos and videos in your Drive" →
    restore progress → done, with what was restored (items, folders, favourites).
K3. Free up space: a preview before the first run ("1,204 originals, 18.4 GB can leave this phone.
    They stay in your Drive and keep a preview here. Favourites and pinned items stay.") with a
    confirm, and the result.
K4. Albums and pinning: an album page, pin an album or folder to keep its originals on the phone.

L. App icon: an adaptive icon (foreground on a 108 dp canvas with the 66 dp safe zone, background
   layer) and a monochrome variant for themed icons, plus a small in-app brand mark. Idea space:
   settling, a calm layer coming to rest, phone and cloud as one. Show it among other icons on a
   home screen in light and dark.

End with a single consolidated handoff sheet covering every screen.
```
