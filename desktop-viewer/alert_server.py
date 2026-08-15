"""Listens for person-detected alerts POSTed by sender phones — the desktop
equivalent of AlertReceiverService. Same protocol: plain HTTP POST to
/alert with a JSON body {label, ip, ts}, port 8790, no auth (Tailscale
membership is the access control, matching the rest of this project).
"""

from __future__ import annotations

import json
import threading
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ALERT_PORT = 8790


class _AlertHandler(BaseHTTPRequestHandler):
    on_alert: Callable[[str, str], None] = staticmethod(lambda label, ip: None)

    def do_POST(self) -> None:  # noqa: N802 - required BaseHTTPRequestHandler name
        if self.path != "/alert":
            self.send_response(404)
            self.end_headers()
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length))
            label = body.get("label", "camera")
            ip = body.get("ip", "")
        except (ValueError, json.JSONDecodeError):
            self.send_response(400)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()
        _AlertHandler.on_alert(label, ip)

    def log_message(self, format: str, *args) -> None:  # noqa: A002 - suppress default stderr logging
        pass


class AlertServer:
    def __init__(self, on_alert: Callable[[str, str], None]):
        _AlertHandler.on_alert = staticmethod(on_alert)
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._server = ThreadingHTTPServer(("0.0.0.0", ALERT_PORT), _AlertHandler)  # noqa: S104 - Tailscale-only traffic by design, see module docstring
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        if self._server:
            self._server.shutdown()
            self._server.server_close()
