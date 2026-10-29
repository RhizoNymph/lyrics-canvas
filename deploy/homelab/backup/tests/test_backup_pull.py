"""Tests for the laptop-side backup pull (pull.sh, install.sh, unit templates).

Run with:  python3 deploy/homelab/backup/tests/test_backup_pull.py
Needs bash and rsync; shellcheck is used when installed.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
BACKUP = HERE.parent
PULL = BACKUP / "pull.sh"
INSTALL = BACKUP / "install.sh"
SERVICE = "lyrics-canvas-backup-pull.service"
TIMER = "lyrics-canvas-backup-pull.timer"


def dump(stamp: str) -> str:
    return f"lyrics_canvas-{stamp}T031500Z.dump"


class TempDirTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="lc-pull-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)


class PullTest(TempDirTest):
    def setUp(self) -> None:
        super().setUp()
        self.src = self.tmp / "node1"
        self.src.mkdir()
        self.dest = self.tmp / "laptop" / "Backups"

    def pull(self, keep: str | None = None, source: str | None = None) -> subprocess.CompletedProcess[str]:
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "BACKUP_SOURCE": source if source is not None else str(self.src),
            "BACKUP_DEST": str(self.dest),
        }
        if keep is not None:
            env["BACKUP_KEEP"] = keep
        return subprocess.run([str(PULL)], env=env, capture_output=True, text=True, check=False)

    def names(self) -> list[str]:
        return sorted(p.name for p in self.dest.iterdir())

    def test_copies_only_complete_dumps(self) -> None:
        (self.src / dump("20261001")).write_text("a")
        (self.src / dump("20261002")).write_text("b")
        (self.src / ".lyrics_canvas-20261003T031500Z.dump.partial").write_text("partial")
        (self.src / "notes.txt").write_text("other")
        (self.src / "lyrics_canvas-manual.dump").write_text("other")
        result = self.pull()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.names(), [dump("20261001"), dump("20261002")])
        self.assertEqual((self.dest / dump("20261002")).read_text(), "b")
        self.assertEqual(self.dest.stat().st_mode & 0o777, 0o700)
        self.assertIn("level=info", result.stdout)

    def test_keeps_history_pruned_on_source(self) -> None:
        (self.src / dump("20261001")).write_text("a")
        self.assertEqual(self.pull().returncode, 0)
        (self.src / dump("20261001")).unlink()
        (self.src / dump("20261002")).write_text("b")
        self.assertEqual(self.pull().returncode, 0)
        self.assertEqual(self.names(), [dump("20261001"), dump("20261002")])

    def test_keep_prunes_oldest_local(self) -> None:
        for day in range(1, 6):
            (self.src / dump(f"202610{day:02d}")).write_text(str(day))
        (self.dest).mkdir(parents=True)
        (self.dest / "readme.txt").write_text("mine")
        result = self.pull(keep="2")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.names(), [dump("20261004"), dump("20261005"), "readme.txt"])

    def test_keep_zero_keeps_everything(self) -> None:
        for day in range(1, 6):
            (self.src / dump(f"202610{day:02d}")).write_text(str(day))
        self.assertEqual(self.pull(keep="0").returncode, 0)
        self.assertEqual(len(self.names()), 5)

    def test_invalid_keep_fails(self) -> None:
        for bad in ("-1", "x", ""):
            result = self.pull(keep=bad)
            self.assertNotEqual(result.returncode, 0, bad)
            self.assertIn("level=error", result.stderr)

    def test_unreachable_source_fails(self) -> None:
        result = self.pull(source=str(self.tmp / "missing"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("level=error", result.stderr)

    def test_missing_settings_fail(self) -> None:
        result = subprocess.run([str(PULL)], env={"PATH": os.environ["PATH"]}, capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)


class InstallTest(TempDirTest):
    def install(self, *args: str) -> subprocess.CompletedProcess[str]:
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.tmp / "home")}
        (self.tmp / "home").mkdir(exist_ok=True)
        return subprocess.run(
            [str(INSTALL), "--no-enable", "--unit-dir", str(self.tmp / "units"), "--lib-dir", str(self.tmp / "lib"), *args],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    def unit(self, name: str) -> str:
        return (self.tmp / "units" / name).read_text()

    def test_defaults(self) -> None:
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        service = self.unit(SERVICE)
        self.assertIn('Environment="BACKUP_SOURCE=node1:/home/nymph/backups/lyrics-canvas"', service)
        self.assertIn(f'Environment="BACKUP_DEST={self.tmp}/home/Backups/lyrics-canvas"', service)
        self.assertIn('Environment="BACKUP_KEEP=0"', service)
        self.assertIn(f'ExecStart="{self.tmp}/lib/backup-pull.sh"', service)
        self.assertIn("Type=oneshot", service)
        timer = self.unit(TIMER)
        self.assertIn("Persistent=true", timer)
        self.assertIn("OnCalendar=*-*-* 04:30:00", timer)
        self.assertIn("WantedBy=timers.target", timer)
        for text in (service, timer):
            self.assertNotIn("@", text.replace("@daily", ""), "unrendered placeholder")
        installed = self.tmp / "lib" / "backup-pull.sh"
        self.assertEqual(installed.read_text(), PULL.read_text())
        self.assertTrue(os.access(installed, os.X_OK))

    def test_custom_values(self) -> None:
        result = self.install(
            "--host", "backup@node9", "--path", "/srv/dumps", "--dest", str(self.tmp / "d"),
            "--keep", "30", "--on-calendar", "*-*-* 06:00:00",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        service = self.unit(SERVICE)
        self.assertIn('Environment="BACKUP_SOURCE=backup@node9:/srv/dumps"', service)
        self.assertIn(f'Environment="BACKUP_DEST={self.tmp}/d"', service)
        self.assertIn('Environment="BACKUP_KEEP=30"', service)
        self.assertIn("OnCalendar=*-*-* 06:00:00", self.unit(TIMER))

    def test_rejects_bad_values(self) -> None:
        for args in (
            ("--dest", "relative/dir"),
            ("--path", "relative"),
            ("--host", ""),
            ("--host", "a b"),
            ("--keep", "-3"),
            ("--keep", "many"),
            ("--dest", "/tmp/50%"),
            ("--dest", '/tmp/a"b'),
            ("--path", "/x$HOME"),
            ("--bogus",),
        ):
            result = self.install(*args)
            self.assertNotEqual(result.returncode, 0, args)
            self.assertFalse((self.tmp / "units" / SERVICE).exists(), args)

    def test_rendered_units_verify(self) -> None:
        analyze = shutil.which("systemd-analyze")
        if analyze is None:
            self.skipTest("systemd-analyze not installed")
        self.assertEqual(self.install().returncode, 0)
        result = subprocess.run(
            [analyze, "verify", "--man=no", str(self.tmp / "units" / SERVICE), str(self.tmp / "units" / TIMER)],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0 and result.stderr.startswith("Failed to setup"):
            self.skipTest("systemd-analyze cannot run here: " + result.stderr.strip())
        self.assertEqual(result.returncode, 0, result.stderr)


class ShellcheckTest(unittest.TestCase):
    def test_scripts(self) -> None:
        shellcheck = shutil.which("shellcheck")
        if shellcheck is None:
            self.skipTest("shellcheck not installed")
        result = subprocess.run([shellcheck, str(PULL), str(INSTALL)], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=1)
