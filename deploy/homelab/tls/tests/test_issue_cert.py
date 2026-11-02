"""Tests for deploy/homelab/tls/issue-cert.sh.

Each test runs the script with HOME pointed at a throwaway directory, so the
CA and server certificate land in <tmp>/.config/lyrics-canvas/tls. Real
openssl does the cryptography (the tests then inspect the certificates with
openssl); ssh is replaced by the recording stub from deploy/homelab/tests/stubs,
which saves the manifest piped to `kubectl apply -f -`. A failing openssl is
simulated with a stub put first on PATH. Nothing touches the network or the
cluster.

Run with:  python3 deploy/homelab/tls/tests/test_issue_cert.py
Needs bash and openssl; shellcheck is used when installed.
"""

from __future__ import annotations

import base64
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import_yaml_error: Exception | None = None
try:
    import yaml
except ImportError as exc:  # PyYAML is optional; the manifest is also checked textually
    yaml = None  # type: ignore[assignment]
    import_yaml_error = exc

HERE = Path(__file__).resolve().parent
TLS_DIR = HERE.parent
SCRIPT = TLS_DIR / "issue-cert.sh"
REPO_ROOT = HERE.parents[3]
SSH_STUBS = REPO_ROOT / "deploy" / "homelab" / "tests" / "stubs"

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_DEPENDENCY = 3
EXIT_CA_STATE = 4
EXIT_ISSUE = 5
EXIT_SSH = 6
EXIT_APPLY = 7

EXPECTED_SANS = {"IP Address:10.1.1.69", "IP Address:10.1.1.68", "IP Address:100.64.0.4", "DNS:lyrics.s8.gay"}
APPLY_COMMAND = "microk8s kubectl apply --server-side --field-manager=lyrics-canvas-tls --force-conflicts -f -"


def openssl(*args: str, stdin: bytes | None = None) -> str:
    proc = subprocess.run(["openssl", *args], input=stdin, capture_output=True, check=False)
    if proc.returncode != 0:
        raise AssertionError(f"openssl {' '.join(args)} failed: {proc.stderr.decode()}")
    return proc.stdout.decode()


def cert_text(path: Path) -> str:
    return openssl("x509", "-in", str(path), "-noout", "-text")


def not_after(path: Path) -> datetime:
    out = openssl("x509", "-in", str(path), "-noout", "-enddate", "-dateopt", "iso_8601")
    return datetime.strptime(out.strip().split("=", 1)[1], "%Y-%m-%d %H:%M:%SZ").replace(tzinfo=timezone.utc)


def not_before(path: Path) -> datetime:
    out = openssl("x509", "-in", str(path), "-noout", "-startdate", "-dateopt", "iso_8601")
    return datetime.strptime(out.strip().split("=", 1)[1], "%Y-%m-%d %H:%M:%SZ").replace(tzinfo=timezone.utc)


def sans(path: Path) -> set[str]:
    out = openssl("x509", "-in", str(path), "-noout", "-ext", "subjectAltName")
    body = out.split("\n", 1)[1]
    return {s.strip() for s in body.split(",") if s.strip()}


def fingerprint(path: Path) -> str:
    return openssl("x509", "-in", str(path), "-noout", "-fingerprint", "-sha256").strip()


def mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


@dataclass
class Run:
    code: int
    stdout: str
    stderr: str
    calls: list[dict]

    def commands(self) -> list[str]:
        return [c["command"] for c in self.calls]


class IssueCertTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="lc-tls-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.state = self.tmp / "state"
        self.state.mkdir()
        self.dir = self.home / ".config" / "lyrics-canvas" / "tls"

    @property
    def ca_key(self) -> Path:
        return self.dir / "ca.key"

    @property
    def ca_crt(self) -> Path:
        return self.dir / "ca.crt"

    @property
    def server_key(self) -> Path:
        return self.dir / "server.key"

    @property
    def server_crt(self) -> Path:
        return self.dir / "server.crt"

    def run_script(
        self,
        *args: str,
        rules: list[dict] | None = None,
        env: dict[str, str] | None = None,
        extra_path: list[Path] | None = None,
    ) -> Run:
        log = self.state / "ssh.log"
        if log.exists():
            log.unlink()
        for old in self.state.glob("stdin-*"):
            old.unlink()
        rules_file = self.state / "rules.json"
        rules_file.write_text(json.dumps(rules or []))
        path_parts = [str(p) for p in (extra_path or [])] + [str(SSH_STUBS), os.environ["PATH"]]
        full_env = {
            "PATH": ":".join(path_parts),
            "HOME": str(self.home),
            "STUB_SSH_LOG": str(log),
            "STUB_SSH_RULES": str(rules_file),
        }
        full_env.update(env or {})
        proc = subprocess.run(
            ["bash", str(SCRIPT), *args],
            cwd=self.tmp,
            env=full_env,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=120,
        )
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return Run(proc.returncode, proc.stdout, proc.stderr, calls)

    def ok(self, *args: str, **kw: object) -> Run:
        run = self.run_script(*args, **kw)  # type: ignore[arg-type]
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        return run

    def manifest(self, run: Run) -> str:
        applies = [c for c in run.calls if c["command"] == APPLY_COMMAND]
        self.assertEqual(len(applies), 1, run.calls)
        self.assertIsNotNone(applies[0]["stdin_file"])
        return Path(applies[0]["stdin_file"]).read_text()

    def failing_openssl_dir(self, match: str) -> Path:
        """A directory with an openssl wrapper that fails when its arguments
        contain `match` and otherwise runs the real openssl."""
        real = shutil.which("openssl")
        assert real
        stub_dir = self.tmp / "openssl-stub"
        stub_dir.mkdir(exist_ok=True)
        stub = stub_dir / "openssl"
        stub.write_text(
            "#!/usr/bin/env bash\n"
            f'if [[ " $* " == *{match}* ]]; then echo "stub openssl failure" >&2; exit 1; fi\n'
            f'exec {real} "$@"\n'
        )
        stub.chmod(0o755)
        return stub_dir


class UsageTest(IssueCertTestCase):
    def test_help_lists_flags_and_exit_codes(self) -> None:
        run = self.run_script("-h")
        self.assertEqual(run.code, EXIT_OK)
        for needle in ("--dry-run", "--renew", "--force", "--no-apply", "-h", "LYRICS_TLS_DIR"):
            self.assertIn(needle, run.stdout)
        self.assertEqual(run.calls, [])
        self.assertFalse(self.dir.exists())

    def test_unknown_flag_is_usage_error(self) -> None:
        run = self.run_script("--bogus")
        self.assertEqual(run.code, EXIT_USAGE)
        self.assertIn("event=usage.unknown_argument", run.stderr)
        self.assertEqual(run.calls, [])
        self.assertFalse(self.dir.exists())

    def test_validity_above_397_days_rejected(self) -> None:
        for days in ("398", "0", "-1", "abc", ""):
            with self.subTest(days=days):
                run = self.run_script(env={"LYRICS_TLS_DAYS": days})
                self.assertEqual(run.code, EXIT_USAGE, run.stderr)
                self.assertIn("key=LYRICS_TLS_DAYS", run.stderr)
                self.assertFalse(self.dir.exists())

    def test_bad_sans_rejected(self) -> None:
        for value in ("", "10.1.1.69", "IP:10.1.1.999", "DNS:bad host", "IP:1.2.3.4,EMAIL:a@b", "DNS:a;b"):
            with self.subTest(value=value):
                run = self.run_script(env={"LYRICS_TLS_SANS": value})
                self.assertEqual(run.code, EXIT_USAGE, run.stderr)
                self.assertIn("key=LYRICS_TLS_SANS", run.stderr)

    def test_bad_namespace_and_secret_rejected(self) -> None:
        for key, value in (
            ("LYRICS_NAMESPACE", "Bad_NS"),
            ("LYRICS_NAMESPACE", ""),
            ("LYRICS_TLS_SECRET", "x y"),
            ("LYRICS_TLS_SECRET", "UPPER"),
            ("LYRICS_KUBE_HOST", ""),
        ):
            with self.subTest(key=key, value=value):
                run = self.run_script(env={key: value})
                self.assertEqual(run.code, EXIT_USAGE, run.stderr)
                self.assertIn(f"key={key}", run.stderr)

    def test_missing_openssl_is_dependency_error(self) -> None:
        empty = self.tmp / "empty-bin"
        empty.mkdir()
        for tool in ("bash", "date", "mkdir", "chmod", "mv", "rm", "base64", "stat", "tr", "sort", "cat", "mktemp", "dirname", "env"):
            found = shutil.which(tool)
            if found:
                (empty / tool).symlink_to(found)
        proc = subprocess.run(
            ["bash", str(SCRIPT), "--no-apply"],
            env={"PATH": str(empty), "HOME": str(self.home)},
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, EXIT_DEPENDENCY, proc.stderr)
        self.assertIn("event=dependency.missing", proc.stderr)
        self.assertIn("tool=openssl", proc.stderr)


class FirstRunTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        # One full run shared by the read-only assertions below.
        cls.shared = IssueCertTestCase("run")
        cls.shared.setUp()
        cls.first = cls.shared.ok()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.shared.doCleanups()

    def test_directory_and_key_modes(self) -> None:
        s = self.shared
        self.assertEqual(mode(s.dir), 0o700)
        self.assertEqual(mode(s.ca_key), 0o600)
        self.assertEqual(mode(s.server_key), 0o600)
        self.assertEqual(mode(s.ca_crt), 0o644)
        self.assertEqual(mode(s.server_crt), 0o644)

    def test_no_temporary_or_csr_files_left(self) -> None:
        names = sorted(p.name for p in self.shared.dir.iterdir())
        self.assertEqual(names, ["ca.crt", "ca.key", "server.crt", "server.key"])

    def test_ca_is_a_ca(self) -> None:
        text = cert_text(self.shared.ca_crt)
        self.assertRegex(text, r"X509v3 Basic Constraints: critical\s+CA:TRUE, pathlen:0")
        self.assertRegex(text, r"X509v3 Key Usage: critical\s+Certificate Sign, CRL Sign")
        self.assertIn("Lyrics Canvas Homelab CA", text)
        self.assertGreater(not_after(self.shared.ca_crt), not_after(self.shared.server_crt))

    def test_server_cert_sans(self) -> None:
        self.assertEqual(sans(self.shared.server_crt), EXPECTED_SANS)

    def test_server_cert_chrome_requirements(self) -> None:
        crt = self.shared.server_crt
        text = cert_text(crt)
        self.assertRegex(text, r"X509v3 Extended Key Usage:\s+TLS Web Server Authentication\n")
        self.assertRegex(text, r"X509v3 Basic Constraints: critical\s+CA:FALSE")
        self.assertRegex(text, r"X509v3 Key Usage: critical\s+Digital Signature")
        self.assertIn("X509v3 Authority Key Identifier", text)
        self.assertIn("sha256", text.lower().split("signature algorithm", 1)[1][:60])
        lifetime = not_after(crt) - not_before(crt)
        self.assertLessEqual(lifetime.days, 397)
        self.assertGreaterEqual(lifetime.days, 396)

    def test_server_cert_verifies_against_ca_for_every_address(self) -> None:
        s = self.shared
        out = openssl("verify", "-CAfile", str(s.ca_crt), "-purpose", "sslserver", str(s.server_crt))
        self.assertIn("OK", out)
        for ip in ("10.1.1.69", "10.1.1.68", "100.64.0.4"):
            openssl("verify", "-CAfile", str(s.ca_crt), "-verify_ip", ip, str(s.server_crt))
        openssl("verify", "-CAfile", str(s.ca_crt), "-verify_hostname", "lyrics.s8.gay", str(s.server_crt))

    def test_server_key_matches_cert(self) -> None:
        s = self.shared
        pub_from_cert = openssl("x509", "-in", str(s.server_crt), "-noout", "-pubkey")
        pub_from_key = openssl("pkey", "-in", str(s.server_key), "-pubout")
        self.assertEqual(pub_from_cert, pub_from_key)

    def test_secret_applied_over_ssh_stdin(self) -> None:
        run = self.first
        self.assertEqual(run.commands(), [APPLY_COMMAND])
        call = run.calls[0]
        self.assertEqual(call["host"], "node0")
        self.assertIn("BatchMode=yes", call["flags"])
        self.assertNotIn("-n", call["flags"])

    def test_manifest_is_tls_secret_with_local_files(self) -> None:
        s = self.shared
        text = s.manifest(self.first)
        if yaml is None:
            self.skipTest(f"PyYAML not installed: {import_yaml_error}")
        doc = yaml.safe_load(text)
        self.assertEqual(doc["apiVersion"], "v1")
        self.assertEqual(doc["kind"], "Secret")
        self.assertEqual(doc["type"], "kubernetes.io/tls")
        self.assertEqual(doc["metadata"]["name"], "lyrics-canvas-tls")
        self.assertEqual(doc["metadata"]["namespace"], "lyrics-canvas")
        self.assertEqual(set(doc["data"]), {"tls.crt", "tls.key"})
        self.assertEqual(base64.b64decode(doc["data"]["tls.crt"]), s.server_crt.read_bytes())
        self.assertEqual(base64.b64decode(doc["data"]["tls.key"]), s.server_key.read_bytes())
        self.assertNotIn(base64.b64encode(s.ca_key.read_bytes()).decode(), text)

    def test_logs_are_structured_and_never_contain_keys(self) -> None:
        run = self.first
        lines = [line for line in run.stderr.splitlines() if line.strip()]
        self.assertTrue(lines)
        for line in lines:
            self.assertRegex(line, r"^ts=\S+ level=(debug|info|warn|error) event=[a-z_.]+( [a-z_]+=\S.*)?$")
        for event in ("ca.created", "server.issued", "secret.applied", "finished"):
            self.assertIn(f"event={event}", run.stderr)
        self.assertNotIn("PRIVATE KEY", run.stderr + run.stdout)

    def test_stdout_tells_user_how_to_trust_the_ca(self) -> None:
        self.assertIn(str(self.shared.ca_crt), self.first.stdout)
        self.assertIn("default-ssl-certificate=lyrics-canvas/lyrics-canvas-tls", self.first.stdout)


class RerunTest(IssueCertTestCase):
    def test_second_run_keeps_ca_and_valid_server_cert(self) -> None:
        self.ok()
        ca_fp = fingerprint(self.ca_crt)
        ca_key = self.ca_key.read_bytes()
        srv_fp = fingerprint(self.server_crt)
        run = self.ok()
        self.assertEqual(fingerprint(self.ca_crt), ca_fp)
        self.assertEqual(self.ca_key.read_bytes(), ca_key)
        self.assertEqual(fingerprint(self.server_crt), srv_fp)
        self.assertIn("event=ca.exists", run.stderr)
        self.assertIn("event=server.valid", run.stderr)
        # The Secret is still (re)applied so a wiped namespace gets it back.
        self.assertEqual(run.commands(), [APPLY_COMMAND])

    def test_renew_reissues_server_cert_with_same_ca(self) -> None:
        self.ok()
        ca_fp = fingerprint(self.ca_crt)
        srv_fp = fingerprint(self.server_crt)
        old_key = self.server_key.read_bytes()
        run = self.ok("--renew")
        self.assertEqual(fingerprint(self.ca_crt), ca_fp)
        self.assertNotEqual(fingerprint(self.server_crt), srv_fp)
        self.assertNotEqual(self.server_key.read_bytes(), old_key)
        self.assertRegex(run.stderr, r"event=server.issued .*reason=renew_requested")
        openssl("verify", "-CAfile", str(self.ca_crt), str(self.server_crt))

    def test_expiring_cert_is_reissued(self) -> None:
        self.ok(env={"LYRICS_TLS_DAYS": "10"})
        srv_fp = fingerprint(self.server_crt)
        run = self.ok()
        self.assertNotEqual(fingerprint(self.server_crt), srv_fp)
        self.assertRegex(run.stderr, r"event=server.issued .*reason=expiring")

    def test_changed_sans_reissue(self) -> None:
        self.ok(env={"LYRICS_TLS_SANS": "IP:10.1.1.69"})
        self.assertEqual(sans(self.server_crt), {"IP Address:10.1.1.69"})
        run = self.ok()
        self.assertEqual(sans(self.server_crt), EXPECTED_SANS)
        self.assertRegex(run.stderr, r"event=server.issued .*reason=sans_changed")

    def test_cert_from_other_ca_is_reissued(self) -> None:
        self.ok()
        other = self.tmp / "other"
        other.mkdir()
        openssl(
            "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
            "-keyout", str(other / "k"), "-out", str(self.server_crt), "-days", "300",
            "-subj", "/CN=other", "-addext", "subjectAltName=IP:10.1.1.69,IP:10.1.1.68,IP:100.64.0.4,DNS:lyrics.s8.gay",
        )
        shutil.copy(other / "k", self.server_key)
        run = self.ok()
        self.assertRegex(run.stderr, r"event=server.issued .*reason=not_signed_by_ca")
        openssl("verify", "-CAfile", str(self.ca_crt), str(self.server_crt))

    def test_mismatched_server_key_is_reissued(self) -> None:
        self.ok()
        openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(self.server_key))
        run = self.ok()
        self.assertRegex(run.stderr, r"event=server.issued .*reason=key_mismatch")

    def test_missing_server_key_is_reissued(self) -> None:
        self.ok()
        self.server_key.unlink()
        run = self.ok()
        self.assertRegex(run.stderr, r"event=server.issued .*reason=missing")
        self.assertEqual(mode(self.server_key), 0o600)


class CaSafetyTest(IssueCertTestCase):
    def test_ca_key_without_cert_is_refused(self) -> None:
        self.ok("--no-apply")
        self.ca_crt.unlink()
        key = self.ca_key.read_bytes()
        run = self.run_script("--no-apply")
        self.assertEqual(run.code, EXIT_CA_STATE, run.stderr)
        self.assertIn("event=ca.incomplete", run.stderr)
        self.assertEqual(self.ca_key.read_bytes(), key)
        self.assertEqual(run.calls, [])

    def test_ca_cert_without_key_is_refused(self) -> None:
        self.ok("--no-apply")
        self.ca_key.unlink()
        crt = self.ca_crt.read_bytes()
        run = self.run_script("--no-apply")
        self.assertEqual(run.code, EXIT_CA_STATE, run.stderr)
        self.assertEqual(self.ca_crt.read_bytes(), crt)
        self.assertFalse(self.ca_key.exists())

    def test_ca_key_not_matching_cert_is_refused(self) -> None:
        self.ok("--no-apply")
        openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(self.ca_key))
        self.ca_key.chmod(0o600)
        run = self.run_script("--no-apply")
        self.assertEqual(run.code, EXIT_CA_STATE, run.stderr)
        self.assertIn("event=ca.key_mismatch", run.stderr)

    def test_expired_or_non_ca_cert_is_refused(self) -> None:
        self.ok("--no-apply")
        key = self.ca_key.read_bytes()
        # Replace the CA cert with a leaf (CA:FALSE) certificate for the same key.
        openssl(
            "req", "-new", "-x509", "-key", str(self.ca_key), "-out", str(self.ca_crt), "-days", "30",
            "-subj", "/CN=not a ca", "-addext", "basicConstraints=critical,CA:FALSE",
        )
        run = self.run_script("--no-apply")
        self.assertEqual(run.code, EXIT_CA_STATE, run.stderr)
        self.assertIn("event=ca.invalid", run.stderr)
        self.assertEqual(self.ca_key.read_bytes(), key)

    def test_loose_ca_key_mode_is_tightened_with_warning(self) -> None:
        self.ok("--no-apply")
        self.ca_key.chmod(0o644)
        run = self.ok("--no-apply")
        self.assertEqual(mode(self.ca_key), 0o600)
        self.assertRegex(run.stderr, r"level=warn event=ca.key_mode_fixed")

    def test_force_replaces_ca_and_keeps_backup(self) -> None:
        self.ok("--no-apply")
        old_fp = fingerprint(self.ca_crt)
        old_key = self.ca_key.read_bytes()
        run = self.ok("--force", "--no-apply")
        self.assertNotEqual(fingerprint(self.ca_crt), old_fp)
        self.assertRegex(run.stderr, r"level=warn event=ca.replaced")
        backups = sorted(p.name for p in self.dir.glob("ca.key.replaced-*"))
        self.assertEqual(len(backups), 1)
        backup_key = self.dir / backups[0]
        self.assertEqual(backup_key.read_bytes(), old_key)
        self.assertEqual(mode(backup_key), 0o600)
        self.assertEqual(len(list(self.dir.glob("ca.crt.replaced-*"))), 1)
        # A new CA always means a new server certificate signed by it.
        openssl("verify", "-CAfile", str(self.ca_crt), str(self.server_crt))
        self.assertRegex(run.stderr, r"event=server.issued .*reason=ca_replaced")

    def test_existing_directory_mode_is_tightened(self) -> None:
        self.dir.mkdir(parents=True)
        self.dir.chmod(0o755)
        self.ok("--no-apply")
        self.assertEqual(mode(self.dir), 0o700)

    def test_custom_directory(self) -> None:
        custom = self.tmp / "elsewhere" / "tls"
        self.ok("--no-apply", env={"LYRICS_TLS_DIR": str(custom)})
        self.assertTrue((custom / "ca.key").exists())
        self.assertFalse(self.dir.exists())

    def test_relative_directory_rejected(self) -> None:
        run = self.run_script("--no-apply", env={"LYRICS_TLS_DIR": "relative/tls"})
        self.assertEqual(run.code, EXIT_USAGE, run.stderr)
        self.assertIn("key=LYRICS_TLS_DIR", run.stderr)


class DryRunTest(IssueCertTestCase):
    def test_dry_run_on_empty_state_writes_nothing(self) -> None:
        run = self.ok("--dry-run")
        self.assertFalse(self.dir.exists())
        self.assertEqual(run.calls, [])
        self.assertIn("event=ca.would_create", run.stderr)
        self.assertRegex(run.stderr, r"event=server.would_issue .*reason=missing")
        self.assertRegex(run.stderr, r"event=remote.dry_run .*cmd=\"microk8s kubectl apply --server-side --field-manager=lyrics-canvas-tls --force-conflicts -f -\"")
        for line in run.stderr.splitlines():
            self.assertIn("dry_run=1", line)

    def test_dry_run_with_existing_state_changes_nothing(self) -> None:
        self.ok("--no-apply")
        before = {p.name: p.read_bytes() for p in self.dir.iterdir()}
        run = self.ok("--dry-run", "--renew")
        after = {p.name: p.read_bytes() for p in self.dir.iterdir()}
        self.assertEqual(before, after)
        self.assertEqual(run.calls, [])
        self.assertIn("event=ca.exists", run.stderr)
        self.assertRegex(run.stderr, r"event=server.would_issue .*reason=renew_requested")

    def test_dry_run_force_does_not_touch_ca(self) -> None:
        self.ok("--no-apply")
        key = self.ca_key.read_bytes()
        run = self.ok("--dry-run", "--force")
        self.assertEqual(self.ca_key.read_bytes(), key)
        self.assertIn("event=ca.would_replace", run.stderr)
        self.assertEqual(list(self.dir.glob("*.replaced-*")), [])


class ApplyTest(IssueCertTestCase):
    def test_no_apply_makes_no_ssh_calls(self) -> None:
        run = self.ok("--no-apply")
        self.assertEqual(run.calls, [])
        self.assertIn("event=secret.apply_skipped", run.stderr)

    def test_configured_host_namespace_and_secret(self) -> None:
        run = self.ok(env={
            "LYRICS_KUBE_HOST": "nymph@10.1.1.69",
            "LYRICS_NAMESPACE": "songs",
            "LYRICS_TLS_SECRET": "songs-tls",
        })
        self.assertEqual(run.calls[0]["host"], "nymph@10.1.1.69")
        text = self.manifest(run)
        self.assertRegex(text, r"(?m)^  name: songs-tls$")
        self.assertRegex(text, r"(?m)^  namespace: songs$")
        self.assertIn("default-ssl-certificate=songs/songs-tls", run.stdout)

    def test_ssh_unreachable(self) -> None:
        run = self.run_script(rules=[{"match": "kubectl apply", "exit": 255}])
        self.assertEqual(run.code, EXIT_SSH, run.stderr)
        self.assertIn("event=secret.ssh_failed", run.stderr)
        # Local files are kept: the next run reuses them and only retries the apply.
        self.assertTrue(self.server_crt.exists())

    def test_kubectl_failure(self) -> None:
        run = self.run_script(rules=[{"match": "kubectl apply", "stdout": "", "exit": 1}])
        self.assertEqual(run.code, EXIT_APPLY, run.stderr)
        self.assertIn("event=secret.apply_failed", run.stderr)


class OpensslFailureTest(IssueCertTestCase):
    def test_ca_creation_failure_leaves_no_partial_ca(self) -> None:
        stub = self.failing_openssl_dir("-x509")
        run = self.run_script("--no-apply", extra_path=[stub])
        self.assertEqual(run.code, EXIT_ISSUE, run.stderr)
        self.assertIn("event=ca.create_failed", run.stderr)
        self.assertFalse(self.ca_key.exists())
        self.assertFalse(self.ca_crt.exists())
        self.assertEqual([p.name for p in self.dir.iterdir()] if self.dir.exists() else [], [])

    def test_signing_failure_keeps_previous_server_cert(self) -> None:
        self.ok("--no-apply")
        srv = self.server_crt.read_bytes()
        key = self.server_key.read_bytes()
        stub = self.failing_openssl_dir("-CAkey")
        run = self.run_script("--renew", extra_path=[stub])
        self.assertEqual(run.code, EXIT_ISSUE, run.stderr)
        self.assertIn("event=server.issue_failed", run.stderr)
        self.assertEqual(self.server_crt.read_bytes(), srv)
        self.assertEqual(self.server_key.read_bytes(), key)
        self.assertEqual(run.calls, [])
        self.assertEqual(sorted(p.name for p in self.dir.iterdir()), ["ca.crt", "ca.key", "server.crt", "server.key"])


class StaticTest(unittest.TestCase):
    def test_strict_mode(self) -> None:
        self.assertIn("set -euo pipefail", SCRIPT.read_text())

    def test_executable(self) -> None:
        self.assertTrue(os.access(SCRIPT, os.X_OK))

    def test_never_writes_a_file_on_the_node(self) -> None:
        text = SCRIPT.read_text()
        self.assertNotRegex(text, r"\bscp\b|\brsync\b")
        self.assertIsNone(re.search(r"kubectl apply -f (?!-)", text))

    def test_bash_syntax(self) -> None:
        proc = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)

    @unittest.skipUnless(shutil.which("shellcheck"), "shellcheck not installed")
    def test_shellcheck_clean(self) -> None:
        proc = subprocess.run(["shellcheck", "-x", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)


if __name__ == "__main__":
    unittest.main()
