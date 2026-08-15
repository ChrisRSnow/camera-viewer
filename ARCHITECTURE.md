# CameraViewerApp — Technical Structure & Implementation Notes

A native Android app for person-detection home monitoring across two or more
phones on a Tailscale tailnet — no browser, no third-party cloud service,
each phone doing its own detection and pushing alerts directly to the
others. This document explains how the pieces fit together and the
non-obvious things discovered along the way, so the next change doesn't
have to rediscover them. It's self-contained — no other repo or document
is required to understand or build this project.

Package: `com.cameraviewer.app` (debug builds get an appended
`.debug` applicationIdSuffix). minSdk 26, target/compileSdk 37, Kotlin/Java
17. No automated test suite exists yet.

## 1. Prerequisite: the "Android IP Camera" app

This project doesn't capture video itself. On every sender phone, a
separate, third-party app called **Android IP Camera** must already be
installed and configured — it's what actually captures video from the
phone's hardware camera and exposes it over HTTPS on `127.0.0.1:4444`
(self-signed cert, HTTP Basic Auth, no anonymous access to anything
including `/info.json`). This project is purely a client of that app; all
of the following were discovered empirically while building against it and
aren't documented anywhere in the camera app itself:

- **Real endpoints**: `/video/mjpeg` (multipart/x-mixed-replace JPEG
  stream — what this project actually uses) and `/video/h264` (raw
  Annex-B elementary stream, not used here — would need MSE/WebCodecs-style
  decoding). `/info.json` returns camera list, battery, Wi-Fi strength,
  current settings. There is no `/snapshot` or `/shot.jpg` — both 404;
  those were guesses based on a different, unrelated camera app's
  conventions.
- **Anti-brute-force rate limiting** on failed auth returns `429` with
  `Retry-After: 30`, but has been observed to escalate well past 30 seconds
  under repeated failures. The reliable fix is force-stopping and
  reopening the camera app, which resets its lockout state immediately
  rather than waiting out an unknown, possibly-escalating timer.
- **The Basic Auth password field mangles at least one special
  character**: `&` caused persistent `401`s even though the app's settings
  screen showed it as saved. Plain alphanumeric, or a character already
  confirmed to work (`$`), is the safe choice for a new password.
- **Single-viewer encoder design** — the camera app appears to run an
  independent capture+encode pipeline per connected viewer. A second
  simultaneous connection degrades performance for both, and restarting
  the camera app is the only way to clear the degraded state once it's
  hit. This project deliberately never opens two connections to the same
  local camera app from one phone — see §4's note on why the sender's own
  live-preview reuses `CameraDetectionService`'s existing connection rather
  than opening a second one.
- **Camera control via query parameters, applied live to any request** —
  no dedicated control endpoint; parameters like `camera=`, `zoom=`,
  `exposure=`, `torch=`, `rotate=<degrees>`, and `focus_distance=` (`-1` =
  auto, `0..1` = manual) can be tacked onto any request (e.g.
  `/info.json?focus_distance=-1`) and take effect on the running camera
  session immediately, without needing to reconnect the stream.
  `rotate=` is applied to the actual `/video/mjpeg` connection itself
  (`MjpegClient`'s `query` parameter), not a throwaway request like
  `focus_distance`'s nudge — see the "Camera rotation" Settings field
  below.
- **Camera rotation** (`SecureCredentialStore.cameraRotationDegrees`,
  Settings' cycling "0°/90°/180°/270°" button, sender section) — corrects
  for how the sender phone is physically mounted (e.g. landscape-mounted
  needs 90 or 270) via the camera app's `rotate=` parameter, applied once
  at `CameraDetectionService`'s one connection to the camera app. Because
  that's upstream of everything else, correcting it there means the
  sender's own local preview, the video relay, and therefore every remote
  viewer, all see already-correct frames — no rotation logic needed
  anywhere else in this app. Discrete values only (not arbitrary degrees):
  the camera app's rotation is a rotation of the encoder output, not a
  free angle.
- **Autofocus can get stuck with no automatic correction** — observed on
  real hardware: the lens simply stopped refocusing on scene changes, and
  only cleared when the phone was physically moved (jostling the lens
  mechanically). `CameraDetectionService` works around this by calling
  `CameraControlClient.nudgeRefocus()` every `refocusIntervalMinutes`
  (user-configurable in Settings, default 5): it sets `focus_distance` to a manual value away from wherever
  it's stuck, briefly settles, then sets it back to `-1` (auto) — forcing
  the same kind of lens movement physically jostling the phone caused,
  without needing a hand on it. Fire-and-forget on the service's own
  coroutine scope, so a slow or failed nudge request never stalls frame
  processing or detection.
- **Suspends when backgrounded** unless *all four* of the following are
  set: battery-optimization exemption, OnePlus "Autostart" permission,
  OnePlus "Foreground activity" allowed (a separate toggle from Autostart),
  **and** the app locked in the recent-apps/task-switcher view. Missing any
  one results in the app dying on screen-lock, surfacing as either a
  multi-second delay on next connect (cold-starting the camera hardware)
  or a much longer delay if the whole process was killed. The same four
  settings are worth applying to CameraViewerApp itself on both sender and
  viewer phones, for the same reason — OxygenOS's background-kill behavior
  isn't specific to one app.

## 2. Component overview

There are two roles a phone can play, chosen once via a first-run prompt
and stored in `SecureCredentialStore.deviceRole` (`ROLE_SENDER` /
`ROLE_VIEWER`). The choice isn't a hard restriction — it only controls
which Settings sections show by default and what auto-starts on launch;
either role's fields can still be filled in by hand if a device needs both.

```
SENDER phone                                    VIEWER phone
┌─────────────────────────────┐                 ┌─────────────────────────────┐
│ "Android IP Camera" app      │                 │                             │
│ (third-party, HTTPS,         │                 │                             │
│  self-signed cert, Basic     │                 │                             │
│  Auth) — :4444                │                 │                             │
│        │ 127.0.0.1            │                 │                             │
│        ▼                      │                 │                             │
│ CameraDetectionService        │                 │                             │
│  - MjpegClient pulls frames   │                 │                             │
│  - publishes latestFrame/     │                 │                             │
│    status (StateFlow) for     │                 │                             │
│    MainActivity's own preview │                 │                             │
│  - PersonDetector (TFLite)    │  HTTP POST      │ AlertReceiverService        │
│  - on person found:           │  :8790/alert    │  - raw ServerSocket         │
│    fireLocalNotification()    │  {label, ip, ts} │  - parses {label, ip, ts}   │
│    SnapshotStore.save() ──────┼──┐              │  - fireAlertNotification()  │
│    AlertClient.sendAlert() ───┼──┼─────────────▶│      - full-screen intent   │
│      (includes own Tailscale  │  │              │      - EXTRA_CAMERA_IP set  │
│       IP via LocalTailscaleIp)│  │              │        on the PendingIntent │
│                                │  ▼              │        → MainActivity       │
│ SnapshotServerService ◀────────┼──┘              └──────────────┬──────────────┘
│  :8791/snapshots (list)       │  GET :8791                      │
│  :8791/snapshots/<file>       │◀─────────────────────────────── SnapshotsActivity
│                                │  (on demand, viewer-initiated)  │
│ VideoRelayServerService        │                                 │
│  :8792/video/mjpeg            │  GET :8792/video/mjpeg          │
│  (LiveFrameBus fan-out) ◀──────┼───────────────────────────────▶│
└─────────────────────────────┘  plain HTTP, full framerate       │
        ▲                                          └──────────────┬──────────────┘
        │ MJPEG stream, :4444                                     │
        │ HTTPS, Basic Auth                                       ▼
        │ (this app's ONLY connection to it — see §4)      CameraMonitorService
   "Android IP Camera" app                                  - explicit IP (from alert)
        (from §1)                                             → connects directly, no
                                                                discovery
                                                              - no explicit IP (manual
                                                                "Start monitoring") →
                                                                TailscaleDiscovery +
                                                                CameraProber cert check,
                                                                first match wins
                                                              - streams from the
                                                                sender's :8792 relay,
                                                                never from :4444
                                                                directly
```

A single phone can run both `CameraDetectionService` (watching its own
local camera) and `CameraMonitorService`/`AlertReceiverService` (watching a
different phone's camera) simultaneously if configured for both roles.

## 3. Person detection

`PersonDetector` uses a real on-device ML model — EfficientDet-Lite0,
COCO-trained, Google's official Task Library model — not a
brightness-delta heuristic. It genuinely distinguishes a person from a
shadow, a cat, or a moving curtain. Detection requires 2 consecutive
positive frames (debounce against a single spurious detection) and a 30s
cooldown between alerts (`MIN_SCORE = 0.5f`,
`CONSECUTIVE_FRAMES_REQUIRED = 2`, `ALERT_COOLDOWN_MS = 30_000L`).
Inference runs roughly once per second (`INFERENCE_EVERY_N_FRAMES = 12` at
the camera's ~12fps), not on every frame — far less battery/thermal load
than running a full object detector at stream framerate, and plenty for an
alert use case.

**The model file is not checked into this repo.** `assets/person_detector.tflite`
must be downloaded separately before building — its exact redistribution
license couldn't be confirmed with full certainty (strong circumstantial
evidence points to Apache 2.0, matching TensorFlow's own repo license and
platform-wide convention, but no explicit per-model license field was
found), so it's safer not to bundle it in a public repo. Get it from the
official Kaggle model listing (search "EfficientDet-Lite0 detection
metadata TFLite" on kaggle.com/models, published by TensorFlow — the exact
URL slug has moved before, e.g. during the TensorFlow Hub → Kaggle
migration, so search rather than rely on a hardcoded link) and place it at
`app/src/main/assets/person_detector.tflite`.

The `.tflite` asset **must** stay uncompressed in the APK
(`androidResources { noCompress += "tflite" }` in `build.gradle`) — TFLite
mmaps it directly at load time, and a compressed asset fails with an
unhelpful native error. Easy to accidentally undo if the build config is
ever restructured.

A separate, cruder brightness-delta `MotionDetector` used to run on the
viewer side (an earlier iteration, mirroring a now-retired browser-based
prototype's JS heuristic) — it's been **removed entirely**. If you see a
reference to it in old notes or a stale build, it no longer exists; person
detection (camera-side, real ML) is the only detection mechanism in this
app.

## 4. The sender's own live preview, and relaying video to remote viewers

`CameraDetectionService` publishes the frames it decodes for detection
(`latestFrame`/`status`, `StateFlow`s mirroring `CameraMonitorService`'s
existing bindable-service pattern), and `MainActivity` binds to both
services and displays whichever one is actually producing frames in the
same `imageFeed`/`textStatus` views. In normal single-role usage only one
service is ever running, so there's no real conflict over who's "driving"
the display.

This deliberately **reuses** the single MJPEG connection
`CameraDetectionService` already holds open for detection, rather than
having `MainActivity` open a second, independent connection (e.g. via
`CameraMonitorService` pointed at `127.0.0.1`) to show a preview. See §1's
note on the camera app's single-viewer encoder degradation — a second
connection from the same phone would risk exactly that problem, even
though both connections would technically originate from the same device.
The local preview updates at roughly the same ~1fps cadence as inference
(frames are decoded once per second either way), not full video framerate —
fine for "is this pointed where I think it is," not meant to be a smooth
feed.

**Remote viewers go through the same single connection too, via
`VideoRelayServerService` and `LiveFrameBus`.** Every frame
`CameraDetectionService` reads from the local camera app — not just the
throttled ~1fps inference frames — is published to `LiveFrameBus`, an
in-process `SharedFlow`. `VideoRelayServerService` is a second raw-socket
server on the sender (`:8792/video/mjpeg`, plain HTTP, same
Tailscale-membership-is-the-access-control trust model as
`AlertReceiverService`/`SnapshotServerService`) that fans those frames out
to any number of connected viewers via `SharedFlow`'s normal multi-collector
support. `CameraMonitorService` (on a viewer phone) connects to this relay,
not to the camera app's `:4444` directly.

This mattered in practice, not just in theory: earlier versions had
`CameraMonitorService` connect straight to the sender's camera app over
Tailscale. That is a second simultaneous connection to the same
single-viewer-encoder camera app discussed in §1 — just made remotely
instead of locally — and it reproduced the same degradation on real
hardware: after a few minutes, both the sender's local preview and the
viewer's remote view would freeze on the last frame, stuck in a
"Reconnecting in 30s…" loop that never recovered on its own (the camera
app's encoder itself needed restarting, not just the app's sockets). Routing
viewers through the relay means the camera app only ever has the one
connection `CameraDetectionService` holds, regardless of how many viewer
phones are watching.

## 5. Multi-camera routing

Originally, an alert only carried the sending camera's display **label**,
not its Tailscale IP — the viewer's discovery (`CameraProber` cert-checking
every tailnet peer) would connect to whichever camera it found *first*, not
specifically the one that alerted. Fine with one camera phone, silently
wrong with two or more.

Fixed via `LocalTailscaleIp` — reads this device's own tailnet IP directly
off `NetworkInterface.getNetworkInterfaces()` (filtering for a `100.`
prefix, Tailscale's CGNAT range), rather than asking the Tailscale API,
which has no "who am I" lookup usable for this. This works reliably from a
normal installed Android app; it would *not* work the same way from
inside Termux, where `/proc/net` access is denied to unprivileged apps —
`NetworkInterface` enumeration via the standard `java.net` API isn't
gated the same way.

The camera's own IP now rides along in the alert payload
(`{"label": ..., "ip": ..., "ts": ...}`), and the whole path from there
uses it instead of re-discovering:
`CameraDetectionService` → `AlertClient.sendAlert(targets, label, ip)` →
`AlertReceiverService` parses `ip` → `fireAlertNotification` puts it on the
notification's `PendingIntent` as `MainActivity.EXTRA_CAMERA_IP` →
`MainActivity.handleAutoConnectIntent` passes it to
`CameraMonitorService` as `EXTRA_TARGET_IP` → `runMonitoringLoop` connects
directly, skipping `discoverCameraIp()` entirely (and still updates
`lastKnownCameraIp`, so "View Snapshots" — §7 — has a camera to ask even on
a device that's never run generic discovery).

`CameraMonitorService.startMonitoring()` also handles the case of tapping a
*second* camera's alert while already watching a *different* one — it
compares against `currentTargetIp` and cancels/restarts the stream rather
than silently ignoring the new target, which is what a naive
"already running, do nothing" guard would otherwise do.

**Still not built**: choosing *which* camera to watch from a cold start
when there are several — `CameraMonitorService`'s generic discovery path
(no explicit IP; manual "Start monitoring" tap) still just returns the
first cert-matching tailnet peer, same as before. The routing fix only
applies when arriving via a tapped alert. There's also no in-app list of
"known cameras" anywhere — you only learn a camera exists by receiving an
alert from it or by reading the tailnet scan's results.

## 6. Sender/viewer role auto-behaviors

- **Viewer**: `AlertReceiverService` needs zero configuration to run (no
  credential checks in its `onStartCommand`), so `MainActivity` starts it
  unconditionally on every launch when `deviceRole == ROLE_VIEWER` — not
  just after setup. A visible "Listening"/"Not listening" pill on the main
  screen (bound to `AlertReceiverService.isListening`) reflects the
  service's real live state, not just its own notification.
- **Sender**: on every launch (and again after every Settings save),
  auto-starts `CameraDetectionService` and `SnapshotServerService` if
  camera username/password/label are filled in, and separately re-runs the
  tailnet viewer-scan if a Tailscale token is present, merging any
  newly-found viewers into Alert Targets. None of this checks whether the
  *external* Android IP Camera app is actually running first —
  `CameraDetectionService` already retries with backoff until its local
  connection succeeds (`INITIAL_RECONNECT_DELAY_MS` → `MAX_RECONNECT_DELAY_MS`),
  so starting it unconditionally has the same effect and self-heals if
  that app is closed and reopened later.
- **Manual "Scan tailnet for viewers"** button (`ViewerScan.findViewers`)
  still exists alongside the automatic version — enumerates tailnet peers
  via the Tailscale API, then probes each for an open port 8790
  (`ViewerProber`). This is a materially weaker signal than `CameraProber`'s
  TLS-cert check: `AlertReceiverService` is plain HTTP with no
  distinguishing certificate, so "something is listening on that port" is
  the best available signal, and could false-positive on any other device
  that happens to have 8790 open (unlikely on a home tailnet in practice).

## 7. Snapshots

On every person detection, the sender saves the detected frame as a JPEG
(`SnapshotStore`, app-private `filesDir/snapshots/`), keeping only the
most recent N (`SecureCredentialStore.snapshotRetentionCount`, default 20,
configurable in Settings — the sender section). Filenames are strictly
`snapshot_<epochMillis>.jpg`; this isn't just a naming convention, it's the
actual security boundary — any filename requested over the network is
validated against that exact pattern before ever touching the filesystem,
which is what makes a path-traversal attempt (`../../etc/...`) impossible
rather than just unlikely.

`SnapshotServerService` (sender, port 8791) serves them on demand — the
pull counterpart to `AlertClient`'s push, same trust model (plain HTTP, no
auth, Tailscale membership is the access control):
- `GET /snapshots` → JSON array of `{filename, timestampMs}`, newest first
- `GET /snapshots/<file>` → the JPEG bytes
- `POST /snapshots/capture` → saves a snapshot of whatever `LiveFrameBus`
  currently holds, regardless of whether a person is actually in frame —
  the backing call for the viewer's "Manual snapshot" button. Waits up to
  `CAPTURE_TIMEOUT_MS` (5s) for a frame before responding `503`; the
  Android app doesn't have a synchronous "current frame" accessor
  (`SharedFlow` has no `.value` the way `StateFlow` does), so this awaits
  `LiveFrameBus.frames.first()` instead — cheap in practice since replay=1
  means it resolves immediately whenever a frame has already arrived.

`SnapshotsActivity` (viewer) is the "View Snapshots" button on the main
screen — fetches the list from `credentialStore.lastKnownCameraIp`,
fetches every thumbnail concurrently (fine at this scale, ≤ the retention
limit), and shows plain dynamically-built rows rather than a RecyclerView
— not worth the extra machinery for ~20 items at most. Tapping a row shows
the full-size image in a dialog. The "Manual snapshot" button next to it
(also main-screen, viewer-role only) calls `SnapshotFetcher.triggerManualCapture()`
directly — no browsing UI of its own, just a fire-and-toast action.

No video, by design — saving "a few seconds around the moment of
detection" would need a continuously-buffered rolling frame window and a
real encoding pipeline (nothing in this codebase does video encoding at
all currently), a meaningfully bigger undertaking than a still image. A
future addition, not started here.

## 8. Two real bugs found and fixed (not just design notes)

- **Cleartext HTTP silently blocked** — `AlertClient` uses plain
  `HttpURLConnection`, but Android blocks cleartext traffic by default for
  apps targeting modern API levels. Every alert POST failed with
  `Cleartext HTTP traffic to <ip> not permitted`, logged at `Log.i` (easy to
  miss) rather than surfaced anywhere in the UI — this looked exactly like
  an intermittent network/OxygenOS background-kill issue until real device
  logcat was captured. Fixed via `res/xml/network_security_config.xml`
  (`<base-config cleartextTrafficPermitted="true">`, referenced from
  `AndroidManifest.xml`'s `android:networkSecurityConfig`). Scoped to the
  whole app rather than specific hosts because Tailscale IPs are dynamic
  and user-entered — Android's network security config has no CIDR support,
  only exact host/IP literals — and because this app never talks to the
  general internet anyway, only localhost (the camera app) and tailnet
  peers; the only thing actually cleartext is the deliberately-unauthenticated
  alert/snapshot POST/listen paths (see `AlertClient`'s own doc comment on
  that trust model). Camera streaming (`MjpegClient`/`CameraProber`)
  already uses TLS regardless of this setting.
- **Settings screen was black-on-black** — the theme was
  `Theme.MaterialComponents.DayNight.NoActionBar`, which resolves default
  text colors based on the phone's system light/dark setting, while
  `android:windowBackground` was hardcoded to pure black
  (`@color/feed_background`) regardless of that setting. In light mode,
  default text resolved to near-black against a background that's always
  black — every unstyled label on the Settings screen became invisible.
  Fixed by dropping `DayNight` from the parent theme
  (`Theme.MaterialComponents.NoActionBar`), which consistently uses the
  dark-mode-appropriate text colors matching the rest of the app's
  permanently-dark palette (`status_pill_text: #FFFFFF`,
  `controls_background: #1C1C1C`, etc. — this was never meant to be
  system-theme-adaptive in the first place).

## 9. Full-screen alert notifications

`AlertReceiverService.fireAlertNotification` calls
`.setFullScreenIntent(pending, true)` in addition to the normal
`setContentIntent` — the officially sanctioned Android mechanism for "this
needs to show itself now" (same category as an incoming-call screen), not
a background-activity-start workaround. Requires
`USE_FULL_SCREEN_INTENT` in the manifest and `IMPORTANCE_HIGH` on the
notification channel (already the case for `CHANNEL_ALERTS`).

Deliberately **not** paired with `showWhenLocked`/`turnScreenOn` on
`MainActivity` — this was an explicit choice, not an oversight. The screen
wakes and shows the alert prominently on the lock screen, but the live
camera feed itself still requires unlocking to view. Pairing those flags
would make the feed visible on a locked screen without authentication,
which is a real privacy trade a future change should only make
deliberately, not by accident.

## 10. Development & deployment

No CI/release pipeline — `./gradlew assembleDebug`, then deploy by hand.
Two practical paths, both used depending on circumstance:

- **ADB** (`adb install -r app-debug.apk`) — needs USB debugging enabled
  (Developer Options) and the device authorized once per computer. Same
  applicationId across installs means `-r` updates in place; Settings data
  (`SecureCredentialStore`, an EncryptedSharedPreferences file) survives a
  reinstall over the same signing key.
- **Local HTTP file server** (`python3 -m http.server`, serving just the
  built APK from a dedicated directory, not the whole project tree) — used
  when ADB/USB isn't practical (bad cable, different network). Serve over
  the *Tailscale* address, not the LAN address, when the phone might be on
  a different network than the dev machine — LAN IPs stop working the
  moment either device switches networks, Tailscale addresses don't.

`local.properties` (Android SDK path) is machine-specific and not meant to
be portable between dev machines. Before building, remember to place the
TFLite model at `app/src/main/assets/person_detector.tflite` (§3) — the
build will fail without it.

## 11. File manifest

| File | Role |
|---|---|
| `MainActivity.kt` | Main screen: live feed display (from either service, §4), Start/Stop monitoring, Manual snapshot, View Snapshots, pinch-to-zoom/pan on the feed (client-side display transform, `ScaleGestureDetector`+`GestureDetector`, double-tap to reset), status + listening pills, first-run role picker, keeps screen on while foregrounded |
| `SettingsActivity.kt` | All credentials + role-conditional section visibility + manual scan/detection/listening controls + snapshot retention count |
| `SecureCredentialStore.kt` | EncryptedSharedPreferences wrapper — Tailscale token, camera login, label, alert targets, device role, snapshot retention count |
| `CameraDetectionService.kt` | Sender role: watches local camera, publishes preview frames, runs `PersonDetector`, fires alerts + saves snapshots |
| `CameraMonitorService.kt` | Viewer role: streams video from a sender's relay (§4), explicit-target or discovery-based |
| `AlertReceiverService.kt` | Viewer role: listens on :8790 for pushed alerts, fires full-screen notification |
| `AlertClient.kt` | Sender-side: POSTs `{label, ip, ts}` to configured viewer targets |
| `CameraControlClient.kt` | Sender-side: periodically nudges the local camera app's `focus_distance` param to clear stuck autofocus (§1) |
| `PersonDetector.kt` | TFLite EfficientDet-Lite0 wrapper, debounced person detection |
| `MjpegClient.kt` | Raw-socket MJPEG stream consumer — TLS `:4444` for the camera app, or plain `:8792` for a sender's relay (§4) |
| `VideoRelayServerService.kt` | Sender-side: re-serves `LiveFrameBus` frames to remote viewers over `:8792`, so they never connect to the camera app directly (§4) |
| `LiveFrameBus.kt` | In-process `SharedFlow` handing frames from `CameraDetectionService`'s one camera-app connection to `VideoRelayServerService`'s many viewer connections |
| `CameraProber.kt` | TLS-connects to `:4444`, checks cert subject for the camera app's identity |
| `ViewerProber.kt` | TCP-connects to `:8790`, checks whether anything's listening (weaker signal than CameraProber) |
| `ViewerScan.kt` | Shared tailnet-scan-for-viewers logic, used by both the manual button and auto-scan-on-startup |
| `TailscaleDiscovery.kt` | Lists tailnet peers via the Tailscale REST API |
| `LocalTailscaleIp.kt` | Reads this device's own tailnet IP off local network interfaces |
| `SnapshotStore.kt` | Sender-side: saves/lists/prunes JPEG snapshots on person detection |
| `SnapshotServerService.kt` | Sender-side: serves snapshots on demand over :8791 |
| `SnapshotFetcher.kt` | Viewer-side: HTTP client for SnapshotServerService |
| `SnapshotsActivity.kt` | Viewer-side: browses/views snapshots from a known camera |

## 12. Known limitations (not bugs, just not built)

- No video, only stills — see §7.
- No in-app list of known cameras — you only learn one exists by receiving
  an alert or reading a scan result.
- Generic (non-alert-routed) discovery still can't choose among multiple
  cameras — first cert match wins.
- `ViewerProber`'s "port is open" check is a weaker signal than
  `CameraProber`'s cert check and could theoretically false-positive on an
  unrelated device.
- No automated tests.
- `deviceRole` is a simple two-value flag, not a real per-service
  enable/disable matrix — a phone that's genuinely both roles works (fields
  aren't hidden, just the *other* role's section is), but the UI doesn't
  have a clean "both" mode, only "whichever role you didn't pick still
  works if you fill it in by hand."
