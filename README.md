# Camera Viewer

A native Android app for person-detection home monitoring across two or
more phones on a [Tailscale](https://tailscale.com) tailnet — no browser,
no third-party cloud service. Each phone does its own on-device person
detection (a real TensorFlow Lite model, not just motion) and pushes
alerts directly to other phones over the tailnet.

- **[SETUP.md](SETUP.md)** — start here. Full walkthrough: Tailscale, the
  required third-party camera app, building, first-run configuration, and
  the Android background/battery/autostart settings that determine whether
  this actually keeps running reliably.
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — how it's built, design
  decisions, and known limitations.
- **[CHANGELOG.md](CHANGELOG.md)** — version history.
- **[desktop-viewer/](desktop-viewer/)** — a viewer-role client for
  Windows/Linux desktops, same alert/video/snapshot protocol as the phone
  app's viewer role. No sender/host mode — see its own README.

## Prerequisites

- Two or more Android phones (minSdk 26+) on the same [Tailscale](https://tailscale.com) network
- The third-party **Android IP Camera** app installed and configured on
  each phone that will act as a camera (a "sender")
- A [Tailscale API access token](https://login.tailscale.com/admin/settings/keys)

See [SETUP.md](SETUP.md) for the full walkthrough covering all of the above.

## License

MIT — see [LICENSE](LICENSE).
