"""Stub backend for the /api body-limit nginx tests.

Answers every request on port 8000 with 200 and a JSON body reporting the
method, path and how many request-body bytes arrived, so the tests can tell
whether nginx forwarded an upload in full. Standard library only; runs in
python:3.12-alpine.
"""

from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class BackendHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("stub-backend " + (fmt % args) + "\n")

    def _answer(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        received = 0
        while received < length:
            chunk = self.rfile.read(min(1 << 20, length - received))
            if not chunk:
                break
            received += len(chunk)
        body = json.dumps({"method": self.command, "path": self.path, "received": received}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = _answer
    do_POST = _answer
    do_PUT = _answer


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), BackendHandler).serve_forever()
