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
