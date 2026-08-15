"""Lists tailnet devices via the Tailscale REST API — same endpoint and
auth scheme as the Android app's TailscaleDiscovery.kt, so a token that
works in the phone app's Settings works here unchanged.

Auth: API access tokens (tskey-api-...) are HTTP Basic Auth with the token
as the username and an empty password (requests' `auth=(token, "")`) — NOT
a bearer token. Easy to get wrong; Tailscale's own docs use curl's
`-u "$TOKEN:"` form, which is exactly this.
"""

from __future__ import annotations

import socket

import requests

DEVICES_URL = "https://api.tailscale.com/api/v2/tailnet/-/devices"
REQUEST_TIMEOUT = 10
PROBE_TIMEOUT = 2

RELAY_PORT = 8792  # VideoRelayServerService
ALERT_PORT = 8790  # AlertReceiverService


class TailscaleApiError(Exception):
    pass


def list_peers(api_token: str) -> list[dict]:
    """Returns [{"hostname": str, "ip": str}, ...] for every tailnet device with an IPv4 address."""
    try:
        resp = requests.get(DEVICES_URL, auth=(api_token, ""), timeout=REQUEST_TIMEOUT)
    except requests.RequestException as e:
        raise TailscaleApiError(f"could not reach Tailscale API: {e}") from e

    if resp.status_code != 200:
        hint = " — check the API token in Settings" if resp.status_code in (401, 403) else ""
        raise TailscaleApiError(f"Tailscale API returned HTTP {resp.status_code}{hint}")

    peers = []
    for device in resp.json().get("devices", []):
        hostname = device.get("hostname") or device.get("name") or "unknown"
        ipv4 = next((a for a in device.get("addresses", []) if a.startswith("100.")), None)
        if ipv4:
            peers.append({"hostname": hostname, "ip": ipv4})
    return peers


def probe_port(ip: str, port: int, timeout: float = PROBE_TIMEOUT) -> bool:
    """Weak discovery signal (matches ViewerProber's approach): just checks something is listening. Same caveat applies — this can false-positive on any other device with the port open, unlikely on a home tailnet."""
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def scan_for_cameras(api_token: str) -> list[dict]:
    """Tailnet peers with an open video-relay port — candidate sender phones."""
    peers = list_peers(api_token)
    return [p for p in peers if probe_port(p["ip"], RELAY_PORT)]
