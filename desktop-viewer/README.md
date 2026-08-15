# Desktop Viewer (Windows / Linux)

A viewer-role client for [Camera Viewer](../README.md) that runs on a
Windows or Linux desktop instead of an Android phone. Same protocol as the
Android app's viewer role: it discovers senders on your Tailscale tailnet,
watches a sender's live video via its relay, listens in the background for
person-detection alerts (with a desktop notification and auto-switch to
the alerting camera), and browses saved snapshots. There is no sender/host
mode here — a desktop machine has no camera hardware story analogous to
the Android IP Camera app, so this only implements the viewer side.

## Setup

1. Install Python 3.10+ (Windows: from [python.org](https://www.python.org/downloads/) — tick "Add python.exe to PATH" during install. Linux: almost certainly already installed; `python3 --version` to check).
2. From this directory:
   ```
   python -m venv .venv
   ```
   Windows: `.venv\Scripts\activate`   Linux: `source .venv/bin/activate`
   ```
   pip install -r requirements.txt
   ```
3. **Linux only** — desktop notifications go through `libnotify` via D-Bus, which needs your distro's D-Bus Python bindings (not just the pip package): Debian/Ubuntu `sudo apt install python3-dbus`, Fedora `sudo dnf install python3-dbus`, Arch `sudo pacman -S python-dbus`. Without this, the app still works fully (alert-triggered camera switching included) — you just won't get an OS popup, silently.
4. Run it:
   ```
   python main.py
   ```

## First-run configuration

Click **Settings**:
- **Tailscale API token** — same one used in the Android app's Settings
  (`tskey-api-...`, from [login.tailscale.com/admin/settings/keys](https://login.tailscale.com/admin/settings/keys)). Needed for **Scan tailnet**.
- **Known cameras** — add a sender's Tailscale IP + a friendly label by
  hand, or leave this empty and use **Scan tailnet** instead (probes every
  tailnet peer for an open video-relay port, same weak-but-adequate
  discovery signal as the Android app's tailnet scan).

Pick a camera in the dropdown and click **Watch**. **View Snapshots**
browses whichever camera is currently selected (or the last one watched).
The alert listener starts automatically on launch — the label next to it
shows whether it's actually bound to its port.

## Windows: building a standalone .exe

Not built or tested from this repo (this was developed on Linux, and
cross-compiling a Windows executable from Linux with PyInstaller isn't
reliably supported). To build one, run this **on a Windows machine**, from
this directory, inside the venv:
```
pip install pyinstaller
pyinstaller --onefile --windowed --name CameraViewer main.py
```
The resulting `.exe` lands in `dist/`. This hasn't been tried yet — if
`--windowed` causes issues with PySide6's Qt plugin loading in a frozen
build, `pyinstaller --onefile --name CameraViewer main.py` (console window
visible, useful for seeing errors) is the fallback to try first.

## Protocol notes

Talks directly to the same ports the Android app's phones use with each
other — see [ARCHITECTURE.md](../ARCHITECTURE.md) in the main repo for the
full picture:
- `:8790` — alert listener (this app is the server here, matching
  `AlertReceiverService`)
- `:8791` — snapshot browsing (this app is the client, matching
  `SnapshotFetcher` against `SnapshotServerService`)
- `:8792` — video relay (this app is the client, matching
  `CameraMonitorService` against `VideoRelayServerService`)

All plain HTTP, no auth beyond Tailscale network membership — same trust
model the whole project already uses, see `AlertClient`'s doc comment in
the main app for the reasoning.

## Known limitations

- No first-run role picker / no encrypted credential storage — the
  Tailscale token is stored in plain JSON at `~/.camera-viewer-desktop/config.json`
  (readable only by your OS user account, same practical protection as any
  other per-user config file, but not Keystore-grade like the Android app).
- No "keep the app alive in the background" story — closing the window
  stops the alert listener. This is meant to run while you're at the
  computer, not as an unattended background service (that's what a sender
  *or viewer* phone already covers).
- Tested only on Linux so far (this repo's dev machine). The Windows path
  should work unchanged — PySide6/Qt and Python's `requests`/`http.server`
  are all genuinely cross-platform — but hasn't been run on Windows yet.
