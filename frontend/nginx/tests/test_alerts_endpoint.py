"""Behavior tests for GET /_status/alerts in the production frontend nginx.

Runs the real nginx image from frontend/Dockerfile.prod with this repo's
default.conf.template and 16-cluster-alerts.envsh mounted where the Dockerfile
copies them, against a stub Alertmanager (stub_alertmanager.py) that records
every request it receives. Covers the response contract, that nginx only ever
requests the one fixed upstream URL, the unconfigured and misconfigured
cases, upstream failures, and that nginx starts even when Alertmanager cannot
be resolved.

Needs a docker CLI whose daemon can bind-mount this directory (snap docker
only mounts paths under $HOME) and reach container IPs from the host, plus
the images nginxinc/nginx-unprivileged:1.27-alpine and python:3.12-alpine
(pulled on first use). Standard library only:

    python3 frontend/nginx/tests/test_alerts_endpoint.py

Set DOCKER to use another docker binary.
"""

from __future__ import annotations

import http.client
import json
import os
import re
import subprocess
import sys
import time
import unittest
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

HERE = Path(__file__).resolve().parent
NGINX_DIR = HERE.parent
FRONTEND = NGINX_DIR.parent
sys.path.insert(0, str(HERE))

from stub_alertmanager import ALERTS_BODY, CANARY  # noqa: E402

DOCKER = os.environ.get("DOCKER", "docker")
STUB_IMAGE = "python:3.12-alpine"
PREFIX = f"lcalerts-{uuid.uuid4().hex[:8]}"
NETWORK = PREFIX
STUB_ALIAS = "am-stub"
STUB_FQDN = "alertmanager.monitoring.svc.cluster.local"
NAMESPACE = "songs"
EXPECTED_TARGET = (
    "/api/v2/alerts?active=true&silenced=false&inhibited=false&filter=namespace%3D%22songs%22"
)
UNAVAILABLE = b'{"error":"alertmanager_unavailable"}'
# Backend that refuses connections: /api is down, the alerts endpoint must not care.
DEAD_BACKEND = "http://127.0.0.1:9"


def nginx_image() -> str:
    froms = re.findall(r"^FROM\s+(\S+)", (FRONTEND / "Dockerfile.prod").read_text(), re.M)
    return froms[-1]


def docker(*args: str, check: bool = True) -> str:
    result = subprocess.run([DOCKER, *args], capture_output=True, text=True, check=False)
    if check and result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def container_ip(name: str) -> str:
    return docker("inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name)


def is_running(name: str) -> bool:
    return docker("inspect", "-f", "{{.State.Running}}", name, check=False) == "true"


class Response:
    def __init__(self, status: int, headers: list[tuple[str, str]], body: bytes, elapsed: float) -> None:
        self.status = status
        self.headers = headers
        self.body = body
        self.elapsed = elapsed

    def header(self, name: str) -> str | None:
        values = self.all(name)
        return values[0] if values else None

    def all(self, name: str) -> list[str]:
        return [v for k, v in self.headers if k.lower() == name.lower()]


def request(
    host: str,
    target: str,
    method: str = "GET",
    port: int = 8080,
    headers: dict[str, str] | None = None,
    body: bytes | None = None,
    timeout: float = 20,
) -> Response:
    """Sends `target` verbatim (no client-side normalization of ../ or %xx)."""
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    start = time.monotonic()
    try:
        conn.putrequest(method, target, skip_host="Host" in (headers or {}), skip_accept_encoding=True)
        for k, v in (headers or {}).items():
            conn.putheader(k, v)
        if body is not None:
            conn.putheader("Content-Length", str(len(body)))
        conn.endheaders(body)
        resp = conn.getresponse()
        data = resp.read()
        return Response(resp.status, resp.getheaders(), data, time.monotonic() - start)
    finally:
        conn.close()


class Stub:
    name = f"{PREFIX}-stub"

    @classmethod
    def start(cls) -> None:
        docker(
            "run", "-d", "--name", cls.name, "--network", NETWORK,
            "--network-alias", STUB_ALIAS, "--network-alias", STUB_FQDN,
            "-v", f"{HERE / 'stub_alertmanager.py'}:/stub.py:ro",
            STUB_IMAGE, "python", "-u", "/stub.py",
        )
        cls.ip = container_ip(cls.name)
        deadline = time.monotonic() + 60
        while True:
            try:
                cls.requests()
                return
            except OSError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.3)

    @classmethod
    def control(cls, method: str, path: str) -> object:
        resp = request(cls.ip, path, method=method, port=9094, timeout=5)
        return json.loads(resp.body)

    @classmethod
    def requests(cls) -> list[dict]:
        return cls.control("GET", "/requests")  # type: ignore[return-value]

    @classmethod
    def reset(cls) -> None:
        cls.control("POST", "/reset")

    @classmethod
    def mode(cls, mode: str) -> None:
        cls.control("POST", f"/mode/{mode}")


def start_nginx(label: str, env: dict[str, str], dns_search: list[str] | None = None) -> str:
    """Starts the production nginx image like Dockerfile.prod assembles it and
    waits for /healthz. Returns the container IP."""
    name = f"{PREFIX}-{label}"
    args = ["run", "-d", "--name", name, "--network", NETWORK]
    for domain in dns_search or []:
        args += ["--dns-search", domain]
    for k, v in env.items():
        args += ["-e", f"{k}={v}"]
    args += [
        "-v", f"{NGINX_DIR / 'default.conf.template'}:/etc/nginx/templates/default.conf.template:ro",
        "-v", f"{NGINX_DIR / 'entrypoint' / '16-cluster-alerts.envsh'}:/docker-entrypoint.d/16-cluster-alerts.envsh:ro",
        nginx_image(),
    ]
    docker(*args)
    ip = container_ip(name)
    deadline = time.monotonic() + 30
    while True:
        if not is_running(name):
            raise AssertionError(f"nginx {label} exited:\n{docker('logs', name, check=False)}")
        try:
            if request(ip, "/healthz", timeout=2).status == 200:
                return ip
        except OSError:
            pass
        if time.monotonic() > deadline:
            raise AssertionError(f"nginx {label} never became ready:\n{docker('logs', name, check=False)}")
        time.sleep(0.3)


def nginx_logs(label: str) -> str:
    result = subprocess.run([DOCKER, "logs", f"{PREFIX}-{label}"], capture_output=True, text=True, check=False)
    return result.stdout + result.stderr


def configured_env(**overrides: str) -> dict[str, str]:
    env = {
        "BACKEND_URL": DEAD_BACKEND,
        "ALERTMANAGER_URL": f"http://{STUB_ALIAS}:9093",
        "ALERTS_NAMESPACE": NAMESPACE,
    }
    env.update(overrides)
    return env


def setUpModule() -> None:
    docker("network", "create", NETWORK)
    Stub.start()


def tearDownModule() -> None:
    names = docker("ps", "-aq", "--filter", f"name={PREFIX}", check=False).split()
    if names:
        docker("rm", "-f", *names, check=False)
    docker("network", "rm", NETWORK, check=False)


class AlertsTestCase(unittest.TestCase):
    def setUp(self) -> None:
        Stub.reset()

    def assert_json_no_store(self, resp: Response) -> None:
        self.assertEqual(resp.header("Content-Type"), "application/json")
        self.assertEqual(resp.all("Cache-Control"), ["no-store"])

    def assert_unavailable(self, resp: Response) -> None:
        self.assertEqual(resp.status, 503, resp.body)
        self.assertEqual(resp.body, UNAVAILABLE)
        self.assert_json_no_store(resp)

    def assert_upstream_saw_only_fixed_url(self, expected_count: int | None = None) -> list[dict]:
        seen = Stub.requests()
        for r in seen:
            self.assertEqual(r["target"], EXPECTED_TARGET)
            self.assertEqual(r["method"], "GET")
        if expected_count is not None:
            self.assertEqual(len(seen), expected_count, seen)
        return seen

    def assert_upstream_untouched(self) -> None:
        self.assertEqual(Stub.requests(), [])


class ConfiguredTest(AlertsTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ip = start_nginx("configured", configured_env())

    def get(self, target: str = "/_status/alerts", **kw: object) -> Response:
        return request(self.ip, target, **kw)  # type: ignore[arg-type]

    def test_get_passes_alerts_through_unchanged(self) -> None:
        resp = self.get()
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.body, ALERTS_BODY)
        self.assert_json_no_store(resp)
        self.assertEqual(resp.header("X-Content-Type-Options"), "nosniff")
        alert = json.loads(resp.body)[0]
        for field in ("startsAt",):
            self.assertIn(field, alert)
        self.assertEqual(alert["labels"]["alertname"], "KubePodCrashLooping")
        self.assertEqual(alert["status"]["state"], "active")
        self.assert_upstream_saw_only_fixed_url(expected_count=1)

    def test_upstream_filter_is_active_unsilenced_uninhibited_namespace(self) -> None:
        self.get()
        (seen,) = self.assert_upstream_saw_only_fixed_url(expected_count=1)
        split = urlsplit(seen["target"])
        self.assertEqual(split.path, "/api/v2/alerts")
        self.assertEqual(
            parse_qs(split.query),
            {
                "active": ["true"],
                "silenced": ["false"],
                "inhibited": ["false"],
                "filter": ['namespace="songs"'],
            },
        )

    def test_head(self) -> None:
        resp = self.get(method="HEAD")
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.body, b"")
        self.assert_json_no_store(resp)
        self.assert_upstream_saw_only_fixed_url(expected_count=1)

    def test_client_query_and_headers_are_not_forwarded(self) -> None:
        resp = self.get(
            '/_status/alerts?filter=alertname%3D%22x%22&active=false&silenced=true&receiver=all',
            headers={
                "Host": "evil.example",
                "Authorization": "Bearer client-token",
                "Cookie": "session=abc",
                "X-Forwarded-For": "1.2.3.4",
                "X-Original-URI": "/api/v2/status",
                "X-Rewrite-URL": "/api/v2/status",
                "Accept": "text/html",
            },
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.body, ALERTS_BODY)
        (seen,) = self.assert_upstream_saw_only_fixed_url(expected_count=1)
        sent = {k.lower(): v for k, v in seen["headers"].items()}
        self.assertEqual(sent["host"], f"{STUB_ALIAS}:9093")
        self.assertEqual(sent["accept"], "application/json")
        for leaked in ("authorization", "cookie", "x-forwarded-for", "x-original-uri", "x-rewrite-url"):
            self.assertNotIn(leaked, sent)

    def test_other_methods_rejected_without_upstream_call(self) -> None:
        for method in ("POST", "PUT", "DELETE", "PATCH", "OPTIONS"):
            with self.subTest(method=method):
                resp = self.get(method=method, body=b'[{"labels":{"alertname":"x"}}]')
                self.assertEqual(resp.status, 405)
                self.assertEqual(resp.header("Allow"), "GET, HEAD")
                self.assert_json_no_store(resp)
        self.assert_upstream_untouched()

    def test_traversal_and_lookalike_paths_never_reach_alertmanager(self) -> None:
        targets = [
            "/_status/alerts/../status",
            "/_status/alerts/..%2Fstatus",
            "/_status/alerts%2F..%2Fstatus",
            "/_status/alerts%2F..%2F..%2Fapi%2Fv2%2Fstatus",
            "/_status/alerts/%2e%2e/status",
            "/_status/alertsX",
            "/_status/alerts/",
            "/_status/alerts/status",
            "/_status/alerts%3Ffilter=x",
            "/_status/alerts;/api/v2/status",
            "/_status/status",
            "/_status/",
            "/_status/api/v2/status",
            "/_status/alerts/../../api/v2/status",
            "/_status/alerts%2F..%2F..%2F..%2Fapi%2Fv2%2Fstatus",
            "/api/v2/status",
            "/api/v2/alerts",
        ]
        for target in targets:
            with self.subTest(target=target):
                resp = self.get(target)
                self.assertNotIn(CANARY.encode(), resp.body)
                self.assertNotEqual(resp.body, ALERTS_BODY)
                # 404 from the reserved /_status/ prefix, 400 for traversal
                # above the root, 502 where it normalizes into the (dead) /api.
                self.assertIn(resp.status, (400, 404, 502), resp.body[:200])
        self.assert_upstream_untouched()

    def test_encoded_and_suffixed_forms_of_the_endpoint_still_use_the_fixed_url(self) -> None:
        for target in (
            "/_status/%61lerts",
            "//_status/alerts",
            "/_status/./alerts",
            "/_status/alerts?../../api/v2/status",
            "/_status/alerts?%2F..%2Fstatus",
        ):
            with self.subTest(target=target):
                resp = self.get(target)
                self.assertEqual(resp.status, 200)
                self.assertEqual(resp.body, ALERTS_BODY)
        self.assert_upstream_saw_only_fixed_url(expected_count=5)

    def test_upstream_error_answers_are_unavailable(self) -> None:
        for mode in ("error500", "error400", "error404", "redirect"):
            with self.subTest(mode=mode):
                Stub.mode(mode)
                resp = self.get()
                self.assert_unavailable(resp)
                self.assertIsNone(resp.header("Location"))
        self.assert_upstream_saw_only_fixed_url(expected_count=4)

    def test_slow_upstream_times_out_as_unavailable(self) -> None:
        Stub.mode("slow")
        resp = self.get()
        self.assert_unavailable(resp)
        self.assertLess(resp.elapsed, 10)

    def test_upstream_headers_cannot_steer_nginx(self) -> None:
        # The stub answers with X-Accel-Redirect: /api/v2/status, Set-Cookie and
        # a long public Cache-Control.
        resp = self.get()
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.body, ALERTS_BODY)
        self.assertEqual(resp.all("Set-Cookie"), [])
        self.assertEqual(resp.all("Cache-Control"), ["no-store"])
        self.assert_upstream_saw_only_fixed_url(expected_count=1)

    def test_rest_of_the_site_unchanged_while_backend_is_down(self) -> None:
        health = self.get("/healthz")
        self.assertEqual((health.status, health.body), (200, b"ok\n"))
        for target in ("/", "/canvas/123"):
            with self.subTest(target=target):
                resp = self.get(target)
                self.assertEqual(resp.status, 200)
                self.assertIn("text/html", resp.header("Content-Type") or "")
        self.assertEqual(self.get("/api/health").status, 502)
        self.assertEqual(self.get().status, 200)

    def test_entrypoint_logged_enabled_state(self) -> None:
        logs = nginx_logs("configured")
        self.assertIn("level=info", logs)
        self.assertIn("state=enabled", logs)
        self.assertNotIn("[emerg]", logs)


class ServiceShortNameTest(AlertsTestCase):
    """http://<svc>.<ns>.svc:port works even though nginx's resolver ignores
    resolv.conf search domains: the entrypoint appends the cluster domain."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.ip = start_nginx(
            "svc",
            configured_env(ALERTMANAGER_URL="http://alertmanager.monitoring.svc:9093/"),
            dns_search=[f"{NAMESPACE}.svc.cluster.local", "svc.cluster.local", "cluster.local"],
        )

    def test_resolves_via_cluster_domain(self) -> None:
        resp = request(self.ip, "/_status/alerts")
        self.assertEqual(resp.status, 200, resp.body)
        self.assertEqual(resp.body, ALERTS_BODY)
        (seen,) = self.assert_upstream_saw_only_fixed_url(expected_count=1)
        self.assertEqual(seen["headers"].get("Host"), f"{STUB_FQDN}:9093")


class UnconfiguredTest(AlertsTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ips = {
            "unset": start_nginx("unset", {"BACKEND_URL": DEAD_BACKEND}),
            "empty": start_nginx(
                "empty", {"BACKEND_URL": DEAD_BACKEND, "ALERTMANAGER_URL": "", "ALERTS_NAMESPACE": NAMESPACE}
            ),
        }

    def test_returns_empty_list(self) -> None:
        for label, ip in self.ips.items():
            with self.subTest(label=label):
                resp = request(ip, "/_status/alerts?filter=x")
                self.assertEqual(resp.status, 200)
                self.assertEqual(resp.body, b"[]")
                self.assert_json_no_store(resp)
                head = request(ip, "/_status/alerts", method="HEAD")
                self.assertEqual((head.status, head.body), (200, b""))
                self.assertEqual(request(ip, "/_status/alerts", method="POST", body=b"x").status, 405)
                self.assertEqual(request(ip, "/").status, 200)
                self.assertEqual(request(ip, "/_status/alertsX").status, 404)
        self.assert_upstream_untouched()
        self.assertIn("state=disabled", nginx_logs("unset"))


class UnreachableTest(AlertsTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ips = {
            # Unresolvable at startup and at request time: nginx must still start.
            "unresolvable": start_nginx(
                "unresolvable", configured_env(ALERTMANAGER_URL="http://does-not-exist.invalid:9093")
            ),
            "unresolvable-svc": start_nginx(
                "unresolvable-svc",
                configured_env(ALERTMANAGER_URL="http://kps-alertmanager.monitoring.svc:9093"),
                dns_search=["svc.cluster.local"],
            ),
            "refused": start_nginx("refused", configured_env(ALERTMANAGER_URL=f"http://{STUB_ALIAS}:9")),
            # Resolver that never answers.
            "dead-resolver": start_nginx("dead-resolver", configured_env(ALERTS_RESOLVER="192.0.2.1")),
        }

    def test_answers_unavailable_and_site_keeps_working(self) -> None:
        for label, ip in self.ips.items():
            with self.subTest(label=label):
                resp = request(ip, "/_status/alerts")
                self.assert_unavailable(resp)
                self.assertLess(resp.elapsed, 10)
                self.assertEqual(request(ip, "/healthz").status, 200)
                self.assertEqual(request(ip, "/").status, 200)
                self.assertNotIn("[emerg]", nginx_logs(label))


class MisconfiguredTest(AlertsTestCase):
    """Bad values never break nginx and never produce a request to anything but
    the fixed URL; the endpoint answers 503 instead."""

    CASES = {
        "url-with-path": {"ALERTMANAGER_URL": f"http://{STUB_ALIAS}:9093/api/v2/status"},
        "url-with-query": {"ALERTMANAGER_URL": f"http://{STUB_ALIAS}:9093?x=1"},
        "url-no-scheme": {"ALERTMANAGER_URL": f"{STUB_ALIAS}:9093"},
        "url-credentials": {"ALERTMANAGER_URL": f"http://user@{STUB_ALIAS}:9093"},
        "url-injection": {"ALERTMANAGER_URL": f'http://{STUB_ALIAS}:9093"; return 200 "x'},
        "url-bad-port": {"ALERTMANAGER_URL": f"http://{STUB_ALIAS}:99999"},
        "namespace-missing": {"ALERTS_NAMESPACE": ""},
        "namespace-injection": {"ALERTS_NAMESPACE": 'songs",alertname=~".+'},
    }

    @classmethod
    def setUpClass(cls) -> None:
        cls.ips = {label: start_nginx(label, configured_env(**env)) for label, env in cls.CASES.items()}

    def test_answers_unavailable_without_upstream_call(self) -> None:
        for label, ip in self.ips.items():
            with self.subTest(label=label):
                self.assert_unavailable(request(ip, "/_status/alerts"))
                self.assertEqual(request(ip, "/healthz").status, 200)
                logs = nginx_logs(label)
                self.assertIn("level=error", logs)
                self.assertIn("state=misconfigured", logs)
        self.assert_upstream_untouched()


class ResolverOverrideTest(AlertsTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ips = {
            # Docker's embedded DNS, given explicitly.
            "explicit": start_nginx("resolver-explicit", configured_env(ALERTS_RESOLVER="127.0.0.11")),
            "explicit-port": start_nginx("resolver-port", configured_env(ALERTS_RESOLVER="127.0.0.11:53")),
            # Garbage is dropped and the resolv.conf nameservers are used.
            "garbage": start_nginx(
                "resolver-garbage", configured_env(ALERTS_RESOLVER="dns.example.com; 1.2.3.4.5 evil")
            ),
        }

    def test_lookups_work(self) -> None:
        for label, ip in self.ips.items():
            with self.subTest(label=label):
                resp = request(ip, "/_status/alerts")
                self.assertEqual(resp.status, 200, resp.body)
                self.assertEqual(resp.body, ALERTS_BODY)
        self.assert_upstream_saw_only_fixed_url(expected_count=3)
        self.assertIn("ignoring resolver address", nginx_logs("resolver-garbage"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
