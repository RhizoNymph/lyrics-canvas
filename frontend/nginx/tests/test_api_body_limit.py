"""Upload size limit on /api/ in the production frontend nginx.

Voice takes (POST /api/canvases/{id}/voice-takes) carry up to 25 MiB of audio
plus multipart overhead, far above nginx's 1 MB default. The /api/ location
raises client_max_body_size to 26m; everything else keeps the default.

Runs the real nginx image from frontend/Dockerfile.prod with this repo's
default.conf.template and entrypoint mounted, in front of stub_backend.py,
which reports how many body bytes reached it. Same requirements as
test_alerts_endpoint.py (a docker CLI that can bind-mount this directory and
reach container IPs). Standard library only:

    python3 frontend/nginx/tests/test_api_body_limit.py

Set DOCKER to use another docker binary.
"""

from __future__ import annotations

import json
import re
import socket
import sys
import time
import unittest
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
NGINX_DIR = HERE.parent
sys.path.insert(0, str(HERE))

from test_alerts_endpoint import (  # noqa: E402
    STUB_IMAGE,
    container_ip,
    docker,
    is_running,
    nginx_image,
    request,
)

PREFIX = f"lcbody-{uuid.uuid4().hex[:8]}"
NETWORK = PREFIX
BACKEND_ALIAS = "backend-stub"
MIB = 1024 * 1024
LIMIT = 26 * MIB
UPLOAD_PATH = "/api/canvases/c1/voice-takes"


def raw_request(host: str, method: str, target: str, content_length: int, port: int = 8080) -> tuple[int, bytes]:
    """Sends only the request head, announcing `content_length` body bytes
    without sending them, and returns the status nginx answers with. Used for
    oversized requests, which nginx must refuse from the header alone."""
    with socket.create_connection((host, port), timeout=20) as sock:
        head = (
            f"{method} {target} HTTP/1.1\r\nHost: test\r\n"
            f"Content-Type: application/octet-stream\r\nContent-Length: {content_length}\r\n\r\n"
        )
        sock.sendall(head.encode())
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = sock.recv(65536)
            if not chunk:
                break
            data += chunk
    match = re.match(rb"HTTP/1\.1 (\d{3})", data)
    if not match:
        raise AssertionError(f"no HTTP status in {data[:200]!r}")
    return int(match.group(1)), data


def start_backend() -> None:
    docker(
        "run", "-d", "--name", f"{PREFIX}-backend", "--network", NETWORK,
        "--network-alias", BACKEND_ALIAS,
        "-v", f"{HERE / 'stub_backend.py'}:/stub.py:ro",
        STUB_IMAGE, "python", "-u", "/stub.py",
    )
    ip = container_ip(f"{PREFIX}-backend")
    deadline = time.monotonic() + 60
    while True:
        try:
            if request(ip, "/ready", port=8000, timeout=2).status == 200:
                return
        except OSError:
            pass
        if time.monotonic() > deadline:
            raise AssertionError("stub backend never became ready")
        time.sleep(0.3)


def start_nginx() -> str:
    name = f"{PREFIX}-nginx"
    docker(
        "run", "-d", "--name", name, "--network", NETWORK,
        "-e", f"BACKEND_URL=http://{BACKEND_ALIAS}:8000",
        "-v", f"{NGINX_DIR / 'default.conf.template'}:/etc/nginx/templates/default.conf.template:ro",
        "-v", f"{NGINX_DIR / 'entrypoint' / '16-cluster-alerts.envsh'}:/docker-entrypoint.d/16-cluster-alerts.envsh:ro",
        nginx_image(),
    )
    ip = container_ip(name)
    deadline = time.monotonic() + 30
    while True:
        if not is_running(name):
            raise AssertionError(f"nginx exited:\n{docker('logs', name, check=False)}")
        try:
            if request(ip, "/healthz", timeout=2).status == 200:
                return ip
        except OSError:
            pass
        if time.monotonic() > deadline:
            raise AssertionError(f"nginx never became ready:\n{docker('logs', name, check=False)}")
        time.sleep(0.3)


NGINX_IP = ""


def setUpModule() -> None:
    global NGINX_IP
    docker("network", "create", NETWORK)
    start_backend()
    NGINX_IP = start_nginx()


def tearDownModule() -> None:
    names = docker("ps", "-aq", "--filter", f"name={PREFIX}", check=False).split()
    if names:
        docker("rm", "-f", *names, check=False)
    docker("network", "rm", NETWORK, check=False)


class ApiBodyLimitTest(unittest.TestCase):
    def upload(self, size: int, target: str = UPLOAD_PATH, method: str = "POST") -> tuple[int, dict | bytes]:
        resp = request(
            NGINX_IP, target, method=method, body=b"\0" * size,
            headers={"Content-Type": "application/octet-stream"}, timeout=60,
        )
        if resp.status == 200:
            return resp.status, json.loads(resp.body)
        return resp.status, resp.body

    def test_voice_take_sized_upload_reaches_backend_in_full(self) -> None:
        size = 25 * MIB + 512 * 1024  # 25 MiB of audio plus multipart overhead
        status, body = self.upload(size)
        self.assertEqual(status, 200, body)
        assert isinstance(body, dict)
        self.assertEqual(body, {"method": "POST", "path": UPLOAD_PATH, "received": size})

    def test_upload_at_the_limit_is_accepted(self) -> None:
        status, body = self.upload(LIMIT)
        self.assertEqual(status, 200, body)
        assert isinstance(body, dict)
        self.assertEqual(body["received"], LIMIT)

    def test_upload_over_the_limit_is_refused(self) -> None:
        status, _ = raw_request(NGINX_IP, "POST", UPLOAD_PATH, LIMIT + 1)
        self.assertEqual(status, 413)

    def test_limit_applies_to_every_api_route(self) -> None:
        for method, target in (("PUT", "/api/snippets/s1"), ("POST", "/api/canvases/c1/snippets")):
            with self.subTest(method=method, target=target):
                status, body = self.upload(2 * MIB, target=target, method=method)
                self.assertEqual(status, 200, body)
                self.assertEqual(raw_request(NGINX_IP, method, target, LIMIT + 1)[0], 413)

    def test_other_locations_keep_the_one_megabyte_default(self) -> None:
        for target in ("/", "/canvas/1", "/assets/x.js"):
            with self.subTest(target=target):
                self.assertEqual(raw_request(NGINX_IP, "POST", target, 2 * MIB)[0], 413)


class TemplateTest(unittest.TestCase):
    TEMPLATE = (NGINX_DIR / "default.conf.template").read_text()

    def api_location(self) -> str:
        match = re.search(r"location /api/ \{(.*?)\n    \}", self.TEMPLATE, re.S)
        assert match, "no /api/ location"
        return match.group(1)

    def test_api_location_limits(self) -> None:
        block = self.api_location()
        self.assertRegex(block, r"\n\s*client_max_body_size 26m;")
        timeout = re.search(r"proxy_read_timeout (\d+)s;", block)
        assert timeout
        self.assertGreaterEqual(int(timeout.group(1)), 120)

    def test_body_limit_only_raised_for_api(self) -> None:
        self.assertEqual(len(re.findall(r"client_max_body_size", self.TEMPLATE)), 1)

    def test_only_backend_and_alertmanager_are_proxied(self) -> None:
        # Nothing else (in particular the internal STT service) is reachable
        # through the frontend nginx.
        targets = set(re.findall(r"proxy_pass\s+([^;]+);", self.TEMPLATE))
        self.assertEqual(targets, {"${BACKEND_URL}", "$alerts_upstream"})
        self.assertNotRegex(self.TEMPLATE.lower(), r"\bstt\b|whisper|transcrib")


if __name__ == "__main__":
    unittest.main()
