"""Stub Alertmanager for the /_status/alerts nginx tests.

Port 9093 plays Alertmanager and records every request it receives (method,
raw request target, headers). Port 9094 is the test's control channel:

    GET  /requests      recorded requests as JSON
    POST /reset         clear the record and return to mode "ok"
    POST /mode/<mode>   ok | error500 | error400 | error404 | slow | redirect

/api/v2/status answers with a canary string standing in for Alertmanager's
config (which carries notification credentials); the tests assert it never
reaches a client and that the path is never requested.

Standard library only; runs in python:3.12-alpine.
"""

from __future__ import annotations

import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CANARY = "CANARY-ALERTMANAGER-CONFIG-SECRET"

# Shaped like a real /api/v2/alerts answer; odd whitespace and a non-ASCII
# character so a byte-for-byte pass-through check means something.
ALERTS_BODY = (
    '[{"annotations":{"description":"Pod songs/lc-backend-0 restarted 5 times – crash loop.",'
    '"summary":"Backend is crash looping"},'
    '"endsAt":"2026-10-28T12:10:00.000Z","fingerprint":"0123456789abcdef",'
    '"receivers":[{"name":"null"}],"startsAt":"2026-10-28T11:55:00.000Z",'
    '"status":{"inhibitedBy":[],"silencedBy":[],"state":"active"},'
    '"updatedAt":"2026-10-28T12:06:00.000Z",'
    '"generatorURL":"http://prometheus/graph?g0.expr=up",'
    '"labels":{"alertname":"KubePodCrashLooping","namespace":"songs","severity":"warning"}}]\n'
).encode()

_lock = threading.Lock()
_requests: list[dict] = []
_mode = {"value": "ok"}


class AlertmanagerHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("stub-am " + (fmt % args) + "\n")

    def _record(self) -> str:
        with _lock:
            _requests.append(
                {"method": self.command, "target": self.path, "headers": dict(self.headers.items())}
            )
            return _mode["value"]

    def _send(self, code: int, body: bytes, content_type: str = "application/json", extra: dict | None = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=3600")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _handle(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        mode = self._record()
        path = self.path.split("?", 1)[0]
        if path == "/api/v2/status":
            self._send(200, json.dumps({"config": {"original": CANARY}}).encode())
            return
        if path != "/api/v2/alerts":
            self._send(404, b'{"error":"not found"}')
            return
        if mode == "error500":
            self._send(500, b'{"error":"boom"}')
        elif mode == "error400":
            self._send(400, b'{"error":"bad filter"}')
        elif mode == "error404":
            self._send(404, b'{"error":"gone"}')
        elif mode == "redirect":
            self._send(302, b"", extra={"Location": "/api/v2/status"})
        elif mode == "slow":
            time.sleep(12)
            self._send(200, ALERTS_BODY)
        else:
            self._send(
                200,
                ALERTS_BODY,
                extra={"Set-Cookie": "am=1", "X-Accel-Redirect": "/api/v2/status"},
            )

    do_GET = do_HEAD = do_POST = do_PUT = do_DELETE = do_OPTIONS = do_PATCH = _handle


class ControlHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        pass

    def _send(self, code: int, payload: object) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/requests":
            with _lock:
                self._send(200, list(_requests))
        else:
            self._send(404, {"error": "unknown"})

    def do_POST(self) -> None:
        with _lock:
            if self.path == "/reset":
                _requests.clear()
                _mode["value"] = "ok"
                self._send(200, {"ok": True})
            elif self.path.startswith("/mode/"):
                _mode["value"] = self.path[len("/mode/"):]
                self._send(200, {"mode": _mode["value"]})
            else:
                self._send(404, {"error": "unknown"})


def main() -> None:
    am = ThreadingHTTPServer(("0.0.0.0", 9093), AlertmanagerHandler)
    control = ThreadingHTTPServer(("0.0.0.0", 9094), ControlHandler)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    sys.stderr.write("stub-am listening on 9093 (alertmanager) and 9094 (control)\n")
    am.serve_forever()


if __name__ == "__main__":
    main()
