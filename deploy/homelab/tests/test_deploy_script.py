"""Tests for deploy/homelab/deploy.sh.

Each test builds a throwaway git repository holding a copy of the script and
the files it ships, puts a recording ssh stub first on PATH, and runs the
script against it. Real git is used (it is what the script inspects); only
ssh is stubbed, because every remote action goes through it. Nothing touches
the network or the cluster.

Run with:  python3 deploy/homelab/tests/test_deploy_script.py
"""

import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
SCRIPT = REPO_ROOT / "deploy" / "homelab" / "deploy.sh"
STUBS = HERE / "stubs"
HOMELAB_VALUES = REPO_ROOT / "deploy" / "helm" / "environments" / "homelab.yaml"

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_GIT = 3
EXIT_SSH = 4
EXIT_SHIP = 5
EXIT_REGISTRY = 6
EXIT_BUILD = 7
EXIT_HELM_UPGRADE = 8
EXIT_HELM_TEST = 9

STATUS_OUTPUT = "NAME: lyrics\nNAMESPACE: lyrics-canvas\nSTATUS: deployed\nREVISION: 7\n"


@dataclass
class Run:
    code: int
    stdout: str
    stderr: str
    calls: list[dict]

    def commands(self) -> list[str]:
        return [c["command"] for c in self.calls]

    def matching(self, pattern: str) -> list[str]:
        return [c for c in self.commands() if re.search(pattern, c)]


def registry_rule(repo: str, tags: list[str] | None) -> dict:
    """Answer for the build host's curl against the registry tags endpoint."""
    if tags is None:
        body = '{"errors":[{"code":"NAME_UNKNOWN"}]}\n404'
    else:
        body = json.dumps({"name": repo, "tags": tags}) + "\n200"
    return {"match": rf"/v2/{repo}/tags/list", "stdout": body, "exit": 0}


class DeployScriptTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="deploy-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.repo = self.tmp / "repo"
        self.state = self.tmp / "state"
        self.state.mkdir()
        self._make_repo()

    def _git(self, *args: str) -> str:
        return subprocess.run(
            ["git", "-C", str(self.repo), *args],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def _make_repo(self) -> None:
        files = {
            "deploy/homelab/deploy.sh": SCRIPT.read_text(),
            "deploy/helm/environments/homelab.yaml": HOMELAB_VALUES.read_text(),
            "deploy/helm/lyrics-canvas/Chart.yaml": "apiVersion: v2\nname: lyrics-canvas\nversion: 0.1.0\n",
            "backend/Dockerfile": "FROM scratch\n",
            "frontend/Dockerfile.prod": "FROM scratch\n",
            "README.md": "committed\n",
        }
        for rel, content in files.items():
            path = self.repo / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        (self.repo / "deploy/homelab/deploy.sh").chmod(0o755)
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        self._git("config", "user.email", "test@example.invalid")
        self._git("config", "user.name", "test")
        self._git("config", "commit.gpgsign", "false")
        self._git("add", *files.keys())
        self._git("commit", "-q", "-m", "init")

    def sha(self) -> str:
        return self._git("rev-parse", "--short=12", "HEAD")

    def run_script(self, *args: str, rules: list[dict] | None = None, env: dict | None = None) -> Run:
        log = self.state / "ssh.log"
        rules_file = self.state / "rules.json"
        rules_file.write_text(json.dumps(rules or []))
        full_env = {
            "PATH": f"{STUBS}:{os.environ['PATH']}",
            "HOME": str(self.tmp),
            "STUB_SSH_LOG": str(log),
            "STUB_SSH_RULES": str(rules_file),
        }
        full_env.update(env or {})
        proc = subprocess.run(
            [str(self.repo / "deploy/homelab/deploy.sh"), *args],
            cwd=self.tmp,
            env=full_env,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=60,
        )
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return Run(proc.returncode, proc.stdout, proc.stderr, calls)

    def happy_rules(self, *, backend: list[str] | None = None, frontend: list[str] | None = None) -> list[dict]:
        return [
            registry_rule("lyrics-canvas-backend", backend),
            registry_rule("lyrics-canvas-frontend", frontend),
            {"match": r"helm3 status", "stdout": STATUS_OUTPUT, "exit": 0},
        ]

    def assert_cleaned_up(self, run: Run) -> None:
        last = run.commands()[-1]
        self.assertRegex(last, rf"rm -rf lyrics-canvas-deploy/{self.sha()}-\d+$")


class UsageTest(DeployScriptTestCase):
    def test_help_exits_zero_and_lists_flags(self) -> None:
        run = self.run_script("-h")
        self.assertEqual(run.code, EXIT_OK)
        for flag in ("--dry-run", "--skip-build", "--rebuild", "--allow-dirty", "-h"):
            self.assertIn(flag, run.stdout)
        self.assertEqual(run.calls, [])

    def test_unknown_flag_is_usage_error(self) -> None:
        run = self.run_script("--bogus")
        self.assertEqual(run.code, EXIT_USAGE)
        self.assertEqual(run.calls, [])

    def test_skip_build_and_rebuild_conflict(self) -> None:
        run = self.run_script("--skip-build", "--rebuild")
        self.assertEqual(run.code, EXIT_USAGE)
        self.assertEqual(run.calls, [])

    def test_unsafe_remote_base_rejected(self) -> None:
        for base in ("/tmp/x", "../x", "a/../../b", ""):
            with self.subTest(base=base):
                run = self.run_script(env={"LYRICS_REMOTE_BASE": base})
                self.assertEqual(run.code, EXIT_USAGE)
                self.assertEqual(run.calls, [])


class PreflightTest(DeployScriptTestCase):
    def test_modified_tracked_file_refused(self) -> None:
        (self.repo / "README.md").write_text("changed\n")
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_GIT)
        self.assertIn("event=preflight.dirty_tree", run.stderr)
        self.assertEqual(run.calls, [])

    def test_untracked_file_refused(self) -> None:
        (self.repo / "stray.txt").write_text("x\n")
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_GIT)
        self.assertEqual(run.calls, [])

    def test_staged_change_refused(self) -> None:
        (self.repo / "README.md").write_text("changed\n")
        self._git("add", "README.md")
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_GIT)
        self.assertEqual(run.calls, [])

    def test_allow_dirty_warns_and_ships_committed_content_only(self) -> None:
        (self.repo / "README.md").write_text("uncommitted\n")
        (self.repo / "stray.txt").write_text("x\n")
        run = self.run_script("--allow-dirty", rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertRegex(run.stderr, r"level=warn event=preflight\.dirty_tree_allowed")
        shipped = [c for c in run.calls if c["stdin_file"]]
        self.assertEqual(len(shipped), 1)
        with tarfile.open(shipped[0]["stdin_file"]) as tar:
            names = tar.getnames()
            self.assertNotIn("stray.txt", names)
            readme = tar.extractfile("README.md")
            assert readme is not None
            self.assertEqual(readme.read(), b"committed\n")

    def test_values_file_must_be_committed(self) -> None:
        run = self.run_script(
            rules=self.happy_rules(),
            env={"LYRICS_VALUES": "deploy/helm/environments/missing.yaml"},
        )
        self.assertEqual(run.code, EXIT_GIT)
        self.assertEqual(run.calls, [])

    def test_ssh_unreachable(self) -> None:
        run = self.run_script(rules=[{"match": r"^true$", "exit": 255}])
        self.assertEqual(run.code, EXIT_SSH)
        self.assertEqual(run.commands(), ["true"])

    def test_ssh_uses_batch_mode_and_configured_host(self) -> None:
        run = self.run_script(rules=self.happy_rules(), env={"LYRICS_BUILD_HOST": "nymph@10.1.1.69"})
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        for call in run.calls:
            self.assertEqual(call["host"], "nymph@10.1.1.69")
            self.assertIn("BatchMode=yes", call["flags"])


class TagTest(DeployScriptTestCase):
    def test_tag_is_short_sha(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        sha = self.sha()
        self.assertEqual(len(sha), 12)
        self.assertIn(f"tag={sha}", run.stderr)
        self.assertTrue(run.matching(rf"docker build .* -t localhost:32000/lyrics-canvas-backend:{sha} "))
        self.assertTrue(run.matching(rf"docker build .* -t localhost:32000/lyrics-canvas-frontend:{sha} "))
        self.assertTrue(run.matching(rf"docker push localhost:32000/lyrics-canvas-backend:{sha}$"))
        self.assertTrue(run.matching(rf"docker push localhost:32000/lyrics-canvas-frontend:{sha}$"))

    def test_tag_follows_new_commit(self) -> None:
        first = self.sha()
        (self.repo / "README.md").write_text("second\n")
        self._git("commit", "-q", "-am", "second")
        self.assertNotEqual(first, self.sha())
        run = self.run_script("--dry-run")
        self.assertIn(f"backend.image.tag={self.sha()}", run.stderr)
        self.assertNotIn(first, run.stderr)


class DryRunTest(DeployScriptTestCase):
    def test_dry_run_makes_no_remote_calls(self) -> None:
        run = self.run_script("--dry-run", rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertEqual(run.calls, [])

    def test_dry_run_prints_every_remote_step(self) -> None:
        run = self.run_script("--dry-run")
        sha = self.sha()
        for needle in (
            "event=remote.dry_run",
            "tar -xf -",
            "/v2/lyrics-canvas-backend/tags/list",
            f"lyrics-canvas-backend:{sha}",
            f"lyrics-canvas-frontend:{sha}",
            "helm3 upgrade --install lyrics",
            "helm3 test lyrics",
            "rm -rf lyrics-canvas-deploy/",
        ):
            self.assertIn(needle, run.stderr)

    def test_dry_run_tolerates_dirty_tree_only_with_flag(self) -> None:
        (self.repo / "README.md").write_text("changed\n")
        self.assertEqual(self.run_script("--dry-run").code, EXIT_GIT)
        self.assertEqual(self.run_script("--dry-run", "--allow-dirty").code, EXIT_OK)


class BuildTest(DeployScriptTestCase):
    def test_build_uses_host_network_and_build_dir_under_home(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        builds = run.matching(r"docker build")
        self.assertEqual(len(builds), 2)
        for cmd in builds:
            self.assertIn("--network host", cmd)
            self.assertNotIn("/tmp", cmd)
            self.assertRegex(cmd, rf" lyrics-canvas-deploy/{self.sha()}-\d+/(backend|frontend)$")
        (frontend,) = run.matching(r"docker build .*lyrics-canvas-frontend")
        self.assertRegex(frontend, r"-f lyrics-canvas-deploy/\S+/frontend/Dockerfile\.prod")
        self.assertIn("--build-arg VITE_GOOGLE_CLIENT_ID=", frontend)

    def test_google_client_id_passed_to_frontend_build(self) -> None:
        run = self.run_script(rules=self.happy_rules(), env={"LYRICS_VITE_GOOGLE_CLIENT_ID": "abc.apps"})
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertTrue(run.matching(r"--build-arg VITE_GOOGLE_CLIENT_ID=abc\.apps"))

    def test_existing_tag_skips_build(self) -> None:
        sha = self.sha()
        run = self.run_script(rules=self.happy_rules(backend=["dev", sha], frontend=["dev"]))
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertEqual(run.matching(r"docker (build|push) .*lyrics-canvas-backend"), [])
        self.assertEqual(len(run.matching(r"docker (build|push) .*lyrics-canvas-frontend")), 2)
        self.assertIn("event=image.exists", run.stderr)

    def test_both_tags_existing_skips_all_builds(self) -> None:
        sha = self.sha()
        run = self.run_script(rules=self.happy_rules(backend=[sha], frontend=[sha]))
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertEqual(run.matching(r"docker "), [])
        self.assertTrue(run.matching(r"helm3 upgrade"))

    def test_tag_prefix_match_is_not_existence(self) -> None:
        sha = self.sha()
        run = self.run_script(rules=self.happy_rules(backend=[sha + "x", sha[:-1]], frontend=None))
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertEqual(len(run.matching(r"docker build .*lyrics-canvas-backend")), 1)

    def test_rebuild_ignores_existing_tags(self) -> None:
        sha = self.sha()
        run = self.run_script("--rebuild", rules=self.happy_rules(backend=[sha], frontend=[sha]))
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertEqual(len(run.matching(r"docker build")), 2)
        self.assertEqual(run.matching(r"/tags/list"), [])

    def test_skip_build_requires_existing_tags(self) -> None:
        run = self.run_script("--skip-build", rules=self.happy_rules(backend=[self.sha()], frontend=["dev"]))
        self.assertEqual(run.code, EXIT_REGISTRY)
        self.assertEqual(run.matching(r"docker "), [])
        self.assertEqual(run.matching(r"helm3 upgrade"), [])
        self.assert_cleaned_up(run)

    def test_skip_build_with_existing_tags_deploys(self) -> None:
        sha = self.sha()
        run = self.run_script("--skip-build", rules=self.happy_rules(backend=[sha], frontend=[sha]))
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertEqual(run.matching(r"docker "), [])
        self.assertTrue(run.matching(r"helm3 upgrade"))

    def test_registry_unreachable(self) -> None:
        rules = [{"match": r"/tags/list", "stdout": "\n000", "exit": 7}, *self.happy_rules()]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_REGISTRY)
        self.assertEqual(run.matching(r"docker "), [])
        self.assert_cleaned_up(run)

    def test_registry_unexpected_status(self) -> None:
        rules = [{"match": r"/tags/list", "stdout": "oops\n500", "exit": 0}, *self.happy_rules()]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_REGISTRY)
        self.assert_cleaned_up(run)


class HelmTest(DeployScriptTestCase):
    def upgrade_command(self, run: Run) -> str:
        (cmd,) = run.matching(r"helm3 upgrade")
        return cmd

    def test_helm_command_uses_values_file_and_tags(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        cmd = self.upgrade_command(run)
        sha = self.sha()
        base = rf"lyrics-canvas-deploy/{sha}-\d+"
        self.assertRegex(cmd, rf"^microk8s helm3 upgrade --install lyrics {base}/deploy/helm/lyrics-canvas ")
        self.assertIn(" -n lyrics-canvas ", cmd)
        self.assertIn(" --create-namespace ", cmd)
        self.assertRegex(cmd, rf" -f {base}/deploy/helm/environments/homelab\.yaml ")
        self.assertIn(f" --set backend.image.tag={sha}", cmd)
        self.assertIn(f" --set frontend.image.tag={sha}", cmd)
        self.assertIn(" --wait", cmd)
        self.assertIn(" --timeout 10m", cmd)
        self.assertNotIn("reuse-values", cmd)
        self.assertNotIn("reset-values", cmd)

    def test_reuse_values_never_appears_anywhere(self) -> None:
        run = self.run_script("--dry-run")
        self.assertNotIn("reuse-values", run.stderr)
        self.assertNotIn("reuse-values", SCRIPT.read_text().replace("never --reuse-values", ""))

    def test_existing_release_upgrades_atomically(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertIn(" --atomic", self.upgrade_command(run))

    def test_first_install_is_not_atomic(self) -> None:
        rules = [
            registry_rule("lyrics-canvas-backend", None),
            registry_rule("lyrics-canvas-frontend", None),
            {"match": r"helm3 status", "stdout": "", "exit": 1},
        ]
        run = self.run_script(rules=rules)
        # The final status read fails too, so revision is unknown, but the deploy succeeded.
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertNotIn("--atomic", self.upgrade_command(run))

    def test_custom_release_namespace_values(self) -> None:
        (self.repo / "deploy/helm/environments/other.yaml").write_text("{}\n")
        self._git("add", "deploy/helm/environments/other.yaml")
        self._git("commit", "-q", "-m", "other")
        run = self.run_script(
            rules=self.happy_rules(),
            env={
                "LYRICS_RELEASE": "rel2",
                "LYRICS_NAMESPACE": "ns2",
                "LYRICS_VALUES": "deploy/helm/environments/other.yaml",
            },
        )
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        cmd = self.upgrade_command(run)
        self.assertIn("upgrade --install rel2 ", cmd)
        self.assertIn(" -n ns2 ", cmd)
        self.assertRegex(cmd, r" -f \S+/deploy/helm/environments/other\.yaml ")
        self.assertTrue(run.matching(r"helm3 test rel2 -n ns2"))

    def test_helm_test_runs_after_upgrade(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        cmds = run.commands()
        upgrade = next(i for i, c in enumerate(cmds) if "helm3 upgrade" in c)
        test = next(i for i, c in enumerate(cmds) if "helm3 test" in c)
        self.assertLess(upgrade, test)

    def test_success_prints_url_and_revision(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assertIn("http://10.1.1.69:32443", run.stdout)
        self.assertIn("revision 7", run.stdout)
        self.assertIn(self.sha(), run.stdout)
        self.assertRegex(run.stderr, r"event=deploy\.done .*revision=7")

    def test_app_host_is_configurable(self) -> None:
        run = self.run_script(rules=self.happy_rules(), env={"LYRICS_APP_HOST": "10.1.1.68"})
        self.assertIn("http://10.1.1.68:32443", run.stdout)

    def test_helm_upgrade_failure(self) -> None:
        rules = [{"match": r"helm3 upgrade", "exit": 1}, *self.happy_rules()]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_HELM_UPGRADE)
        self.assertEqual(run.matching(r"helm3 test"), [])
        self.assert_cleaned_up(run)

    def test_helm_test_failure(self) -> None:
        rules = [{"match": r"helm3 test", "exit": 1}, *self.happy_rules()]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_HELM_TEST)
        self.assertIn("rollback", run.stderr)
        self.assert_cleaned_up(run)


class CleanupTest(DeployScriptTestCase):
    def test_cleanup_on_success(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        self.assertEqual(run.code, EXIT_OK, run.stderr)
        self.assert_cleaned_up(run)

    def test_cleanup_on_build_failure(self) -> None:
        rules = [{"match": r"docker build", "exit": 1}, *self.happy_rules()]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_BUILD)
        self.assertEqual(run.matching(r"helm3 upgrade"), [])
        self.assert_cleaned_up(run)

    def test_cleanup_on_push_failure(self) -> None:
        rules = [{"match": r"docker push", "exit": 1}, *self.happy_rules()]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_BUILD)
        self.assert_cleaned_up(run)

    def test_cleanup_on_ship_failure(self) -> None:
        rules = [{"match": r"tar -xf -", "exit": 2}, *self.happy_rules()]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_SHIP)
        self.assert_cleaned_up(run)

    def test_cleanup_failure_keeps_original_exit_code(self) -> None:
        rules = [
            {"match": r"rm -rf", "exit": 1},
            {"match": r"helm3 upgrade", "exit": 1},
            *self.happy_rules(),
        ]
        run = self.run_script(rules=rules)
        self.assertEqual(run.code, EXIT_HELM_UPGRADE)
        self.assertRegex(run.stderr, r"level=warn event=cleanup\.failed")

    def test_no_cleanup_when_nothing_was_shipped(self) -> None:
        run = self.run_script(rules=[{"match": r"^true$", "exit": 255}])
        self.assertEqual(run.matching(r"rm -rf"), [])

    def test_ship_comes_before_any_build_and_cleanup_is_last(self) -> None:
        run = self.run_script(rules=self.happy_rules())
        cmds = run.commands()
        self.assertEqual(cmds[0], "true")
        self.assertIn("tar -xf -", cmds[1])
        self.assertRegex(cmds[-1], r"^rm -rf ")
        self.assertEqual(len(run.matching(r"rm -rf")), 1)


class RepoConsistencyTest(unittest.TestCase):
    def test_script_registry_matches_homelab_values(self) -> None:
        values = HOMELAB_VALUES.read_text()
        repos = re.findall(r"repository:\s*(\S+)", values)
        self.assertIn("localhost:32000/lyrics-canvas-backend", repos)
        self.assertIn("localhost:32000/lyrics-canvas-frontend", repos)

    def test_no_node_name_urls_in_repo_docs(self) -> None:
        """Node names are not in DNS; URLs shown to users must use IPs."""
        pattern = re.compile(r"https?://node\d")
        tracked = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "ls-files", "*.md", "*.yaml", "*.yml", "*.sh", "*.txt"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.split()
        offenders = [
            rel for rel in tracked if (REPO_ROOT / rel).is_file() and pattern.search((REPO_ROOT / rel).read_text())
        ]
        self.assertEqual(offenders, [])

    @unittest.skipUnless(shutil.which("shellcheck"), "shellcheck not installed")
    def test_shellcheck_clean(self) -> None:
        proc = subprocess.run(["shellcheck", "-x", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_bash_syntax(self) -> None:
        proc = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)


if __name__ == "__main__":
    unittest.main()
