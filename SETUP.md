# Setup Walkthrough

A practical, start-to-finish guide to getting Camera Viewer actually working
reliably on real phones — not just installed, but *staying* connected and
alerting correctly over days of normal use. Most of the failure modes here
aren't bugs in this app; they're Android (and specifically OEM
customizations like OxygenOS/MIUI/One UI) actively working against a
background app unless you explicitly tell it not to. This document exists
because every one of these gotchas was hit for real during development.

For how the app is built internally, see [ARCHITECTURE.md](ARCHITECTURE.md).
This document is about getting it *running*, not how it works.

## Contents

1. [What you need](#1-what-you-need)
2. [Set up Tailscale](#2-set-up-tailscale)
3. [Set up the Android IP Camera app (sender phones only)](#3-set-up-the-android-ip-camera-app-sender-phones-only)
4. [Build and install Camera Viewer](#4-build-and-install-camera-viewer)
5. [First-run configuration](#5-first-run-configuration)
6. [Camera rotation (sender phones only)](#6-camera-rotation-sender-phones-only)
7. [The big one: keeping the app alive in the background](#7-the-big-one-keeping-the-app-alive-in-the-background)
8. [Networking gotchas](#8-networking-gotchas)
9. [Troubleshooting](#9-troubleshooting)

## 1. What you need

- Two or more Android phones, Android 8.0 (API 26) or newer
- A [Tailscale](https://tailscale.com) account (free tier is fine)
- The third-party **Android IP Camera** app installed on every phone that
  will act as a camera ("sender")
- A way to sideload an APK (this project has no Play Store listing) — ADB,
  or a file transferred/downloaded directly to the phone
- Optional: a Windows or Linux computer, if you want a viewer that isn't a
  phone — see [desktop-viewer/README.md](desktop-viewer/README.md). No
  sender/host mode there, viewer only.

## 2. Set up Tailscale

Every phone (sender and viewer) needs Tailscale installed and logged into
the **same tailnet**, so they can all reach each other regardless of which
Wi-Fi/cellular network each one happens to be on.

1. Install Tailscale from the Play Store (or [tailscale.com/download](https://tailscale.com/download)) on every phone
2. Log in with the same account on all of them
3. Confirm each phone shows as connected in the Tailscale app

**You also need an API access token** (not the same thing as logging in) —
this is what lets Camera Viewer itself query the list of tailnet devices
for camera/viewer discovery:

1. Go to [login.tailscale.com/admin/settings/keys](https://login.tailscale.com/admin/settings/keys)
2. Click **Generate access token** — **not** "Generate auth key," these are
   different things and easy to confuse. An auth key authenticates a
   *device* to the tailnet; an access token authenticates *API calls*,
   which is what this app needs.
3. Copy the token (starts with `tskey-api-`) — you'll paste this into
   Camera Viewer's Settings on every phone

## 3. Set up the Android IP Camera app (sender phones only)

This is a separate, third-party app — not something this project builds or
distributes. Install it from wherever you normally get it, then:

1. Open its settings and set a **username and password** for HTTP Basic
   Auth. **Avoid `&` in the password** — it's been observed to silently
   fail to save correctly even though the app's own UI shows it as saved,
   causing persistent `401` errors that look like a wrong password when
   the real problem is that character specifically. Plain alphanumeric is
   safest.
2. Make sure the app is actually running and its camera preview is live
   before moving on — Camera Viewer connects to it at `127.0.0.1:4444` and
   won't work if it isn't.
3. Remember the exact username/password — you'll need to enter the
   identical values into Camera Viewer's Settings.

**If you get locked out** (repeated `401`s during setup, or a `429` rate
limit): force-stop and reopen the Android IP Camera app. Its rate-limit
lockout is documented as `Retry-After: 30` seconds but has been observed
to escalate well past that under repeated failures — restarting the app
resets it immediately rather than waiting out an unknown timer.

## 4. Build and install Camera Viewer

1. Clone this repo
2. Download the EfficientDet-Lite0 TFLite model — search "EfficientDet-Lite0
   detection metadata TFLite" on [kaggle.com/models](https://www.kaggle.com/models),
   published by TensorFlow — and place it at
   `app/src/main/assets/person_detector.tflite`. **The build fails without
   this file present.** (It isn't bundled in this repo — see
   [ARCHITECTURE.md §3](ARCHITECTURE.md#3-person-detection) for why.)
3. `./gradlew assembleDebug`
4. Install the APK on every phone: `adb install -r app/build/outputs/apk/debug/app-debug.apk`,
   or transfer the APK to the phone and install it directly if ADB isn't
   practical (a phone's browser downloading it from a local file server
   works fine too — just serve it over your Tailscale address if the phone
   might be on a different network than wherever you're serving from, not
   a LAN address, which breaks the moment either device switches networks)

## 5. First-run configuration

On first launch, you'll be asked whether this phone is a **Sender**
(hosts a camera, runs detection) or a **Viewer** (watches feeds, receives
alerts). This isn't a hard restriction — you can still fill in the other
role's fields by hand later — but it decides which Settings sections show
by default and what auto-starts.

Then, in Settings, fill in:
- **Tailscale API access token** — from step 2 above (needed by both roles)
- **Camera username/password** — the exact values from step 3 (needed by
  both roles: a sender authenticates to its own local camera with these; a
  viewer authenticates to whichever remote camera it connects to)
- **Sender-only**: camera label (a friendly name), alert targets (or just
  use "Scan tailnet for viewers" instead of typing IPs by hand), snapshot
  retention count (default 20), auto-refocus interval in minutes (default
  5 — see the note on stuck autofocus below), camera rotation (see
  [§6](#6-camera-rotation-sender-phones-only) if the sender phone isn't
  mounted the way it was held during setup)
- **Viewer-only**: "Reduce camera quality on cellular" — off by default.
  Turning it on asks the sender to drop resolution while *this* phone is
  on cellular data, restoring it when back on Wi-Fi. Worth understanding
  before enabling: camera resolution isn't per-viewer, it's a property of
  the sender's one camera app — if anyone else is watching the same
  sender at the same time (including the sender's own local preview),
  your phone switching to cellular lowers *their* quality too, not just
  yours. Fine for a single-viewer setup, worth thinking about for a
  multi-viewer one.

Save. On a correctly configured sender, detection and the snapshot server
start automatically — you don't need to keep tapping "Start" every time
you reopen the app. Same for a viewer's alert listener. The Settings
screen's Start/Stop buttons reflect the actual running state (not just
whichever you last tapped), since detection/listening can also be running
because it auto-started, not because you pressed the button here.

**Autofocus getting stuck**: this camera app's autofocus has been observed
getting stuck pointed at the same distance with no automatic correction,
only clearing when the phone was physically moved. Camera Viewer works
around this by periodically nudging it back into auto-focus mode on its
own (the "auto-refocus every N minutes" Settings field controls how
often) — you shouldn't need to physically touch a mounted camera phone to
fix this, but if detection seems to be missing people who are clearly in
frame, a stuck-and-not-yet-nudged focus is worth suspecting.

**On a sender, detection missing more distant people** is a real,
inherent limitation, not a bug to chase down: the on-device model
(EfficientDet-Lite0, chosen for low battery/thermal load) downscales
every frame to a fixed 320×320 before inference, so a person several
metres away occupies much less of that downscaled frame than someone
close to the lens — and scores lower even when correctly identified as a
person. There's no user-facing setting for this currently (the confidence
threshold is a compiled-in constant); if detection range is a problem for
your setup, that's worth raising as something to make configurable.

## 6. Camera rotation (sender phones only)

If a sender phone is mounted in landscape, upside-down, or otherwise not
held the way you'd normally hold a phone, the video will appear rotated
wrong on every viewer — this app applies no rotation correction anywhere
by default, so whatever raw orientation the camera captures is exactly
what's shown.

**Manual setup**: Settings → sender section → "Camera rotation" — tap to
cycle 0°/90°/180°/270°, Save, and check a viewer to see if it looks right.
If it's backwards, keep cycling; there's no way to predict the correct
value without checking the actual video, since it depends on how the
sensor is mounted relative to how you've physically positioned the phone.

**Auto-detect** (optional): once the manual value looks correct for the
phone's *current* physical orientation, tick "Auto-detect camera
orientation" and Save — rotation will then follow the phone's orientation
automatically as it's turned, using the accelerometer. This is calibrated
*relative to* whatever you just set manually, not computed from a fixed
formula (the sign of that relationship depends on how a given phone's
camera sensor is physically mounted, which can't be predicted without
testing that exact phone). If auto-detected rotation goes the wrong way
after turning the phone, tick "Invert auto-rotation direction" and Save —
don't try to work around it by re-cycling the manual value instead, that
just changes the calibration point, not the direction it tracks in.

**Applying a rotation change**: Save actively restarts the camera
connection so a change takes effect immediately. If you ever see a
rotation change not visibly apply, that's worth reporting — it's meant to
never require anything beyond hitting Save.

## 7. The big one: keeping the app alive in the background

This is the section that will actually determine whether this works
reliably or silently stops after a while. **Every major Android
manufacturer customizes stock Android's background-process handling to be
more aggressive about killing apps to save battery** — this isn't unique
to one brand, though the exact severity and settings menu differ by OEM.
[dontkillmyapp.com](https://dontkillmyapp.com) is a well-known,
community-maintained resource cataloguing exactly this problem
per-manufacturer, with the specific steps for each — worth checking for
your exact phone model if the steps below (written primarily against
OxygenOS, since that's what this project was developed and tested against)
don't match what you see.

**On every phone running Camera Viewer** (and separately, the Android IP
Camera app on senders), go through all of these — missing even one can
mean the app dies silently after the screen locks or after some period in
the background, with no error shown anywhere:

1. **Battery optimization exemption** — Settings → Apps → [App name] →
   Battery → set to **Unrestricted** (wording varies: "Don't optimize,"
   "No restrictions," "Allow background activity" are all the same
   underlying setting on different Android versions/skins)
2. **Autostart permission** (OnePlus/OxygenOS specifically; other OEMs have
   an equivalent under a different name — MIUI calls it "Autostart" too,
   Huawei calls it "App launch," Samsung folds it into battery settings) —
   allow the app to start itself after a reboot or being killed
3. **"Foreground activity" permission** (OnePlus-specific, a *separate*
   toggle from Autostart — easy to miss, since it's not grouped with the
   other permission toggles in the same screen) — allow the app to run in
   the foreground/show notifications reliably
4. **Lock the app in the recent-apps/task-switcher view** — swipe up to
   the app-switcher, find Camera Viewer's card, and tap the lock icon (or
   long-press, depending on OxygenOS version). This tells the OS not to
   include it in background app cleanup sweeps.

**Notification permission**: on Android 13+ (API 33+), the app requests
`POST_NOTIFICATIONS` on first launch — without granting it, detection and
listening still work, but you won't see any of the status/alert
notifications, which makes it look broken when it isn't.

**Full-screen alert notifications**: person-detection alerts use
`USE_FULL_SCREEN_INTENT` so the viewer's screen wakes and shows the alert
prominently without needing a tap first (see
[ARCHITECTURE.md §9](ARCHITECTURE.md#9-full-screen-alert-notifications)
for why this doesn't bypass the lock screen). On Android 14+, a freshly
installed app is **not** automatically granted this permission — check
**Settings → Apps → Special app access → Full screen intent** (may also
be worded "Send full-screen notifications") and make sure Camera Viewer
is enabled there. Separately, also check the per-app notification
**"Banner"** toggle (Settings → Apps → Camera Viewer → Notifications →
the alert channel) is on — on some OxygenOS versions both of these
appear to matter together, and it isn't clear which one alone is
sufficient. If alerts arrive (check the notification shade) but never
appear prominently while the screen is on and unlocked, check both of
these first. Note that even with everything granted, Android's
documented behavior is to show a heads-up banner rather than a true
full-screen takeover when the screen is already unlocked — the full
takeover is reserved for locked/off/always-on-display states.

**Doze mode / App Standby**: if a phone sits completely stationary and
unused for a long time, stock Android's Doze mode can further restrict
background network access regardless of the app-specific settings above.
The battery-optimization exemption in step 1 is what tells Android to
exempt this app from Doze's restrictions — it's not just a nice-to-have,
it's the actual mechanism that matters here.

## 8. Networking gotchas

- **Ports 8790 (alerts), 8791 (snapshots), and 8792 (video relay)** need to
  actually be reachable between phones over Tailscale — this normally just
  works with Tailscale's default settings, but if you've customized [Tailscale ACLs](https://tailscale.com/kb/1018/acls)
  on your tailnet, make sure they don't block phone-to-phone traffic on
  these ports.
- **This app deliberately allows plain (non-HTTPS) HTTP traffic for
  itself** (`network_security_config.xml`) — this is intentional, not a
  leftover debug setting. Android blocks cleartext HTTP by default, and
  this app's alert/snapshot traffic is plain HTTP by design (see
  [ARCHITECTURE.md §8](ARCHITECTURE.md#8-two-real-bugs-found-and-fixed-not-just-design-notes)
  for the story of how this was discovered as a real bug during
  development, and why it's scoped app-wide rather than to specific
  hosts).
- **Same-network vs. Tailscale addresses**: always use a phone's Tailscale
  IP (starts with `100.`) for anything cross-device in this app's own
  Settings, never a LAN IP — Tailscale addresses stay valid regardless of
  which Wi-Fi network a phone is on; LAN IPs break the moment either
  device changes networks.

## 9. Troubleshooting

**Alerts don't arrive on the viewer, but detection notifications show
locally on the sender**: almost always means the viewer's alert listener
isn't actually running. Check for the "Listening for alerts" persistent
notification on the viewer, and the "Listening"/"Not listening" pill on
its main screen.

**Video is laggy or won't connect, and restarting Camera Viewer doesn't
help**: try force-stopping and reopening the **Android IP Camera** app
specifically, not Camera Viewer. Its single-viewer encoder can degrade
under certain reconnect patterns, and only restarting that app clears it
— see [ARCHITECTURE.md §1](ARCHITECTURE.md#1-prerequisite-the-android-ip-camera-app).
If this happens specifically a few minutes into a viewer watching a
sender, and you're on a build older than 1.1.0, that's a real bug that's
already fixed — see the changelog entry for that version.

**Everything was working, then silently stopped after a while**: almost
always §7 — check every battery/autostart/background setting again,
especially after an OS update, which can silently reset some of them.

**Main screen stuck on "Idle", never reconnects, even after waiting**: a
real bug, fixed in 1.7.1 — if you're on an older build, update. Before
that fix, returning to the app after `CameraDetectionService` had been
killed by the OS in the background could leave it recreated-but-never-
started, stuck reporting its default state indefinitely. Force-closing
and reopening the whole app worked around it on older builds; 1.7.1+
self-heals this automatically.

**"View Snapshots" or the last-snapshot thumbnail says/shows "No camera
known yet"**: on a viewer, this needs at least one successful connection
to a camera first (either a generic "Start monitoring," or having
received and opened a real alert) before it knows which camera's IP to
ask. On a sender, this should never happen (it queries itself) — if it
does, you're on a build older than 1.5.2.

**"Manual snapshot" says "Camera hasn't sent a frame yet"**: the sender
was reached fine, but its connection to the local camera app is
momentarily reconnecting (see the "video is laggy" entry above) — retry
in a few seconds. If it says "unreachable" instead, that's a genuine
network-level failure, not this.

**Settings screen text is invisible / black-on-black**: this was a real
bug, already fixed (see
[ARCHITECTURE.md §8](ARCHITECTURE.md#8-two-real-bugs-found-and-fixed-not-just-design-notes))
— if you see it, you're running a build from before that fix.

**Camera rotation change doesn't apply, or breaks the connection
entirely**: if the connection breaks outright as soon as a non-zero
rotation is saved, that's a real bug from 1.6.0, fixed in 1.6.2 — update.
If it's just not visually applying, make sure you hit Save (it's what
actually restarts the connection to apply a new value) — see
[§6](#6-camera-rotation-sender-phones-only).
