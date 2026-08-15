"""Settings persistence for the desktop viewer.

Mirrors SecureCredentialStore's fields but stored as plain JSON, not
encrypted — the desktop equivalent of Android's EncryptedSharedPreferences
isn't a drop-in (OS keychains differ per platform), and the Tailscale API
token is the only real secret here. Same trust model as the rest of this
project already documents: Tailscale network membership is the access
control for phone-to-phone traffic, and this file is only readable by the
local OS user account anyway.
"""

import json
from pathlib import Path

CONFIG_DIR = Path.home() / ".camera-viewer-desktop"
CONFIG_FILE = CONFIG_DIR / "config.json"

DEFAULTS = {
    "tailscale_api_token": "",
    "known_cameras": [],  # [{"label": str, "ip": str}, ...]
    "last_camera_ip": None,
}


def load() -> dict:
    if not CONFIG_FILE.exists():
        return dict(DEFAULTS)
    try:
        data = json.loads(CONFIG_FILE.read_text())
    except (json.JSONDecodeError, OSError):
        return dict(DEFAULTS)
    merged = dict(DEFAULTS)
    merged.update(data)
    return merged


def save(config: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(config, indent=2))
