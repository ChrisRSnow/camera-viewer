# Changelog

Format loosely follows [Keep a Changelog](https://keepachangelog.com/), versions follow semantic versioning (MAJOR.MINOR.PATCH).

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
