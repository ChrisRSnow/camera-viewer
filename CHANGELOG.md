# Changelog

Format loosely follows [Keep a Changelog](https://keepachangelog.com/), versions follow semantic versioning (MAJOR.MINOR.PATCH).

## Desktop viewer client (Windows/Linux)

Added `desktop-viewer/`, a Python + PySide6 viewer-role client speaking the
same protocol as the Android app's viewer role: Tailscale discovery, the
video relay (`:8792`), the alert listener (`:8790`, with desktop
notifications and auto-switch to the alerting camera), and snapshot
browsing (`:8791`). No sender/host mode. See `desktop-viewer/README.md`
for setup — versioned independently of the Android app's numbers above
since it's a separate client, not part of the APK.

## [1.7.1]

- Fixed a real bug: the main screen could get permanently stuck showing
  "Idle" (never reconnecting) after `CameraDetectionService` was killed
  by the OS while backgrounded and the app returned to the foreground.
  `MainActivity.onStart()` re-binds to the service with `BIND_AUTO_CREATE`,
  which recreates the service object if it was killed — but binding alone
  never calls `onStartCommand()`, so the detection loop never actually
  restarted; the freshly-recreated service just sat reporting its default
  "Idle" state forever. `onStart()` now explicitly restarts the sender
  services (not just binds), closing that gap.

## [1.7.0]

- Added automatic camera rotation ("Auto-detect camera orientation" in
  Settings, sender section): as the sender phone is physically turned,
  rotation correction now applies itself instead of needing the manual
  cycling button every time. Tracks orientation via the accelerometer
  (`CameraOrientationMonitor`), relative to whatever value you last
  manually set for the phone's current orientation — not a fixed
  sensor-to-rotation formula, since that depends on how a given phone's
  camera sensor is physically mounted and can't be verified without
  testing on real hardware. If the auto-corrected direction turns out
  backwards on your phone, tick "Invert auto-rotation direction" rather
  than needing a code change. To set up: orient the phone normally,
  manually set rotation with the existing button until the image looks
  right, tick "Auto-detect," Save.

## [1.6.2]

- Fixed a real regression from 1.6.0: appending `rotate=<degrees>`
  directly to the `/video/mjpeg` connection URL broke the camera
  connection outright once a non-zero rotation was saved — the camera
  app's docs only demonstrate this parameter on the root `/` path or
  dedicated control endpoints, never the streaming endpoint itself.
  Rotation is now set via a separate one-shot control request (matching
  the app's documented `/?torch=on&zoom=2.0` example) before the stream
  connects, decoupled with `runCatching` so even a failed rotation
  request can no longer take down the actual video connection.

## [1.6.1]

- Fixed Settings not actually applying camera rotation (or credential)
  changes while detection was already running: `CameraDetectionService`
  only starts a fresh connection if one isn't already active, so Save was
  persisting the new value without ever reconnecting to use it. Save now
  explicitly restarts the camera connection when sender-configured, so a
  rotation change takes effect immediately instead of silently waiting on
  some unrelated future reconnect.

## [1.6.0]

- Added a "Camera rotation" setting (sender section) — corrects for a
  landscape-mounted (or otherwise rotated) sender phone showing the wrong
  orientation on viewers. A tap-to-cycle 0°/90°/180°/270° button, applied
  via the camera app's own `rotate=` control parameter at the one
  connection to it, so the sender's local preview, the video relay, and
  every remote viewer all see already-corrected frames — no rotation
  logic needed anywhere downstream.

## [1.5.2]

- Fixed "View Snapshots" and the last-snapshot thumbnail always showing
  "No camera known yet" on a sender device — `lastKnownCameraIp` is only
  ever populated by the viewer's remote-connect flow, which a sender never
  goes through for itself. Both now query `127.0.0.1` on a sender device,
  reusing the exact same SnapshotServerService/SnapshotFetcher path a
  viewer uses against a remote camera, since the server already binds all
  interfaces including loopback.

## [1.5.1]

- Lowered the person-detection confidence threshold (0.5 → 0.35): a
  person a few metres from the camera occupies a much smaller fraction of
  the fixed 320x320 frame EfficientDet-Lite0 runs inference on than a
  close/large subject does, and was scoring below 0.5 despite being a
  real, correctly-identified person — not noise. Trade-off: somewhat more
  willing to flag ambiguous person-shaped things.

## [1.5.0]

- Added a last-snapshot thumbnail (viewer role): a small corner overlay on
  the video feed showing the most recent snapshot from whichever camera
  is currently known, tap to view full size. Refreshes when the screen
  opens and immediately after a successful manual snapshot. Self-hiding
  when there's no known camera or no snapshots yet.

## [1.4.1]

- Shrunk the control buttons themselves (height/padding), not just their
  text — they were still full Material touch-target height (~48dp min)
  despite the smaller text from 1.3.0/1.3.3.
- Manual snapshot now distinguishes "camera reachable but has no frame
  ready yet" (its own local camera connection is momentarily
  reconnecting — a known flaky condition, see ARCHITECTURE.md §1) from a
  genuine network failure, instead of one generic "failed — unreachable?"
  message covering both. Should make it clear whether a retry in a moment
  is likely to work.

## [1.4.0]

- Fixed the main screen's control buttons truncating text when squeezed
  into one row: they now stack vertically (still horizontally-oriented
  text) using the black space below the video feed, rather than sharing
  one cramped row.
- Added a "Manual snapshot" button (viewer role): asks the currently-
  watched camera to save a snapshot of whatever it's seeing right now,
  regardless of whether a person is detected. New sender-side endpoint,
  `POST /snapshots/capture` on `SnapshotServerService`, pulling the
  current frame from the same `LiveFrameBus` the video relay already uses.
- Added pinch-to-zoom and pan on the video feed (double-tap to reset).
  Client-side only — a local display transform on frames already
  received, not a request to the sender's real camera zoom, so it works
  independently per viewer with no shared-camera-state complications.

## [1.3.3]

- Fixed a real layout bug: the main screen's bottom button row split space
  unevenly (one weighted button vs. two wrap-content ones), and on some
  screen widths that squeezed the weighted button down to almost nothing,
  making Android wrap its text one character per line. All three buttons
  now split the row evenly and use smaller text (~25% smaller), which both
  fixes the wrapping and gives a more compact control bar.

## [1.3.2]

- Hid the "Start monitoring" button on the main screen for sender-role
  devices — it controls watching a *remote* camera, which doesn't apply to
  a device that's already showing its own local feed. Was previously shown
  unconditionally regardless of role.

## [1.3.1]

- Settings' separate "Start listening for alerts"/"Stop listening for
  alerts" buttons are now a single toggle button reflecting the alert
  listener's actual running state — same treatment as 1.3.0's detection
  toggle.

## [1.3.0]

- Autofocus nudge interval is now user-configurable in Settings (minutes),
  instead of a fixed 5-minute constant.
- Settings' separate "Start person detection"/"Stop person detection"
  buttons are now a single toggle button reflecting the service's actual
  running state (matches the pattern already used for the viewer's Start/Stop
  monitoring button on the main screen).
- Fixed a gap: the manual "Start person detection" button wasn't starting
  VideoRelayServerService, so a sender started this way had no video for
  remote viewers even though detection/alerts worked.

## [1.2.0]

- Fixed stuck autofocus on the sender's camera: on real hardware the lens
  would stop refocusing on scene changes with no automatic correction,
  only clearing when the phone was physically moved — a real problem for
  an unattended camera, and the direct cause of missed person-detection
  alerts (a blurry frame rarely clears the detection confidence
  threshold). Added `CameraControlClient`, which periodically (every 5
  minutes) nudges the camera app's documented `focus_distance` control
  parameter — briefly forcing a manual value away from wherever the lens
  is stuck, then back to auto — recreating the same lens movement that
  physically jostling the phone caused.

## [1.1.0]

- Fixed a real bug: on a two-phone setup, the stream would freeze solid after
  a few minutes — both the sender's own local preview and the viewer's remote
  view stuck on the last frame, status showing "Reconnecting in 30s…"
  indefinitely. Root cause: a remote viewer connecting straight to the
  sender's Android IP Camera app over Tailscale was a *second* simultaneous
  connection to that app (on top of the sender's own detection loop) — its
  single-viewer encoder degrades under exactly that. Fixed by adding
  VideoRelayServerService, so the camera app only ever sees one connection
  (the sender's own) no matter how many viewer phones are watching; viewers
  now connect to that relay instead of the camera app directly.

## [1.0.0] — first public baseline

- Two device roles (Sender/Viewer), chosen once via a first-run prompt, driving role-appropriate Settings UI and auto-start behavior
- Sender: on-device person detection (TensorFlow Lite EfficientDet-Lite0) against the local Android IP Camera app, with alerts pushed to configured viewer phones
- Viewer: live video streaming from a sender, full-screen alert notifications, automatic alert listening
- Multi-camera routing — alerts carry the sending camera's own Tailscale IP, so tapping an alert connects to the right camera rather than a generic "first found" discovery
- Sender's own local camera preview, shown automatically on its own main screen
- Snapshots: sender saves a still image on every detection (configurable retention, default 20), served on demand to viewers over the tailnet, with delete support
- Tailnet auto-discovery of viewer phones (manual button + automatic on save/startup)
- Two real bugs found and fixed: cleartext HTTP silently blocked by Android's default network security policy, and a black-on-black Settings screen caused by a DayNight/hardcoded-background theme mismatch
- Package renamed from a personally-identifying name to `com.cameraviewer.app` ahead of first public release
