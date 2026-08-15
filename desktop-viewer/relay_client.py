"""Consumes a sender's VideoRelayServerService MJPEG stream
(http://<ip>:8792/video/mjpeg, plain HTTP — see ARCHITECTURE.md §4 in the
main repo for why viewers connect to this relay rather than the camera app
directly). Same frame-boundary approach as the Android app's MjpegClient:
scan the raw byte stream for JPEG SOI/EOI markers rather than parsing the
multipart boundary text, since it works regardless of exact multipart
framing and matches the client already proven against this exact server.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable

import requests

RELAY_PORT = 8792
CHUNK_SIZE = 65536
SOI = b"\xff\xd8"
EOI = b"\xff\xd9"
INITIAL_RECONNECT_DELAY = 1.0
MAX_RECONNECT_DELAY = 30.0
CONNECT_TIMEOUT = 8
READ_TIMEOUT = 15


class MjpegStream:
    """Runs a reconnect-with-backoff loop on its own thread, calling
    on_frame(bytes) for every decoded JPEG frame and on_status(str) for
    status text — mirrors CameraMonitorService's runMonitoringLoop. Frames
    are the full-framerate relay output, not throttled."""

    def __init__(self, ip: str, on_frame: Callable[[bytes], None], on_status: Callable[[str], None]):
        self._ip = ip
        self._on_frame = on_frame
        self._on_status = on_status
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=2)

    def _run(self) -> None:
        reconnect_delay = INITIAL_RECONNECT_DELAY
        url = f"http://{self._ip}:{RELAY_PORT}/video/mjpeg"
        while not self._stop_event.is_set():
            self._on_status(f"Connecting to {self._ip}…")
            try:
                with requests.get(url, stream=True, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT)) as resp:
                    if resp.status_code != 200:
                        raise ConnectionError(f"relay returned HTTP {resp.status_code}")
                    buf = b""
                    for chunk in resp.iter_content(chunk_size=CHUNK_SIZE):
                        if self._stop_event.is_set():
                            return
                        if not chunk:
                            continue
                        reconnect_delay = INITIAL_RECONNECT_DELAY
                        buf += chunk
                        while True:
                            soi = buf.find(SOI)
                            if soi < 0:
                                buf = b""
                                break
                            eoi = buf.find(EOI, soi + 2)
                            if eoi < 0:
                                if soi > 0:
                                    buf = buf[soi:]
                                break
                            frame_end = eoi + 2
                            frame = buf[soi:frame_end]
                            buf = buf[frame_end:]
                            self._on_status(f"Live — {self._ip}")
                            self._on_frame(frame)
            except Exception as e:  # noqa: BLE001 - reconnect loop, any failure just retries
                if self._stop_event.is_set():
                    return
                self._on_status(f"Reconnecting in {int(reconnect_delay)}s… ({e})")
            if self._stop_event.wait(reconnect_delay):
                return
            reconnect_delay = min(reconnect_delay * 2, MAX_RECONNECT_DELAY)
