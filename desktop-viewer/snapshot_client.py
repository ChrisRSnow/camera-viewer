"""HTTP client for a sender's SnapshotServerService — the pull counterpart
to alert_server.py's push, same protocol as the Android app's
SnapshotFetcher: plain HTTP, no auth, Tailscale membership is the access
control.
"""

from __future__ import annotations

import requests

SNAPSHOT_PORT = 8791
TIMEOUT = 8


def list_snapshots(ip: str) -> list[dict]:
    """Returns [{"filename": str, "timestampMs": int}, ...], newest first."""
    resp = requests.get(f"http://{ip}:{SNAPSHOT_PORT}/snapshots", timeout=TIMEOUT)
    resp.raise_for_status()
    snaps = resp.json()
    return sorted(snaps, key=lambda s: s.get("timestampMs", 0), reverse=True)


def fetch_image(ip: str, filename: str) -> bytes:
    resp = requests.get(f"http://{ip}:{SNAPSHOT_PORT}/snapshots/{filename}", timeout=TIMEOUT)
    resp.raise_for_status()
    return resp.content


def delete_snapshot(ip: str, filename: str) -> bool:
    try:
        resp = requests.delete(f"http://{ip}:{SNAPSHOT_PORT}/snapshots/{filename}", timeout=TIMEOUT)
        return resp.status_code == 200
    except requests.RequestException:
        return False
