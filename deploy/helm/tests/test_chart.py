"""Render-and-assert tests for the lyrics-canvas Helm chart.

Run with:  python3 deploy/helm/tests/test_chart.py
Set HELM to a helm binary if it is not on PATH.
"""

from __future__ import annotations

import atexit
import base64
import os
import re
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
SOURCE_CHART = HERE.parent / "lyrics-canvas"


def _pristine_copy(source: Path) -> Path:
    """Copy the chart without stray dot-entries (editor and tooling files) so only
    tracked chart content is rendered. ``.helmignore`` is kept."""
    root = Path(tempfile.mkdtemp(prefix="lyrics-canvas-chart-"))
    atexit.register(shutil.rmtree, root, True)
    target = root / source.name
    shutil.copytree(
        source,
        target,
        ignore=lambda _dir, names: [n for n in names if n.startswith(".") and n != ".helmignore"],
    )
    return target


CHART = _pristine_copy(SOURCE_CHART)
VALUES = HERE / "values"
HELM = os.environ.get("HELM", "helm")
RELEASE = "lc"
NAMESPACE = "songs"


def helm(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([HELM, *args], capture_output=True, text=True, check=False)


def render(*values_files: str, **sets: str) -> dict[tuple[str, str], dict]:
    args = ["template", RELEASE, str(CHART), "--namespace", NAMESPACE]
    for name in values_files:
        args += ["-f", str(VALUES / name)]
    for key, value in sets.items():
        args += ["--set", f"{key.replace('__', '.')}={value}"]
    result = helm(*args)
    if result.returncode != 0:
        raise AssertionError(f"helm template failed:\n{result.stderr}")
    docs: dict[tuple[str, str], dict] = {}
    for doc in yaml.safe_load_all(result.stdout):
        if not doc:
            continue
        key = (doc["kind"], doc["metadata"]["name"])
        assert key not in docs, f"duplicate manifest {key}"
        docs[key] = doc
    return docs


def container(pod_template: dict, name: str, init: bool = False) -> dict:
    field = "initContainers" if init else "containers"
    for c in pod_template["spec"].get(field, []):
        if c["name"] == name:
            return c
    raise AssertionError(f"no {'init ' if init else ''}container {name!r}")


def env_map(c: dict) -> dict[str, dict]:
    return {e["name"]: e for e in c.get("env", [])}


def b64(value: str) -> str:
    return base64.b64decode(value).decode()


class LintTest(unittest.TestCase):
    def test_lint_default_values(self) -> None:
        result = helm("lint", str(CHART), "--strict")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_lint_full_values(self) -> None:
        result = helm("lint", str(CHART), "--strict", "-f", str(VALUES / "full.yaml"))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class DefaultRenderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.docs = render()

    def test_expected_resources(self) -> None:
        kinds = {kind for kind, _ in self.docs}
        self.assertEqual(
            kinds,
            {"Deployment", "StatefulSet", "Service", "Secret", "ConfigMap", "ServiceAccount", "Pod"},
        )
        self.assertIn(("Deployment", "lc-lyrics-canvas-backend"), self.docs)
        self.assertIn(("Deployment", "lc-lyrics-canvas-frontend"), self.docs)
        self.assertIn(("StatefulSet", "lc-lyrics-canvas-postgres"), self.docs)
        self.assertIn(("Service", "lc-lyrics-canvas-backend"), self.docs)
        self.assertIn(("Service", "lc-lyrics-canvas-frontend"), self.docs)
        self.assertIn(("Service", "lc-lyrics-canvas-postgres"), self.docs)
        self.assertIn(("Secret", "lc-lyrics-canvas-backend"), self.docs)
        self.assertIn(("Secret", "lc-lyrics-canvas-postgres"), self.docs)
        self.assertIn(("ConfigMap", "lc-lyrics-canvas-postgres-initdb"), self.docs)
        self.assertNotIn("Ingress", kinds)
        self.assertNotIn("PersistentVolumeClaim", kinds)

    def test_backend_image_defaults_to_app_version(self) -> None:
        chart = yaml.safe_load((SOURCE_CHART / "Chart.yaml").read_text())
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        c = container(dep["spec"]["template"], "backend")
        self.assertEqual(c["image"], f"ghcr.io/rhizonymph/lyrics-canvas-backend:{chart['appVersion']}")
        self.assertEqual(c["ports"][0]["containerPort"], 8000)

    def test_backend_env_wiring(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(
            env["SECRET_KEY"]["valueFrom"]["secretKeyRef"],
            {"name": "lc-lyrics-canvas-backend", "key": "secret-key"},
        )
        self.assertEqual(
            env["POSTGRES_PASSWORD"]["valueFrom"]["secretKeyRef"],
            {"name": "lc-lyrics-canvas-postgres", "key": "password"},
        )
        self.assertEqual(
            env["DATABASE_URL"]["value"],
            "postgresql+asyncpg://lyrics:$(POSTGRES_PASSWORD)@lc-lyrics-canvas-postgres:5432/lyrics_canvas",
        )
        self.assertEqual(env["CORS_ORIGINS"]["value"], "[]")
        self.assertEqual(env["ACCESS_TOKEN_EXPIRE_MINUTES"]["value"], "10080")
        self.assertEqual(env["HF_HOME"]["value"], "/cache/huggingface")
        self.assertNotIn("GOOGLE_CLIENT_ID", env)

    def test_backend_containers_never_resync_uv(self) -> None:
        # Pods restart without network access; `uv run` must use the image's venv.
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        for c in (
            container(dep["spec"]["template"], "backend"),
            container(dep["spec"]["template"], "migrate", init=True),
        ):
            self.assertEqual(env_map(c)["UV_NO_SYNC"]["value"], "1", c["name"])

    def test_backend_probes_hit_health(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        c = container(dep["spec"]["template"], "backend")
        for probe in ("startupProbe", "livenessProbe", "readinessProbe"):
            self.assertEqual(c[probe]["httpGet"]["path"], "/api/health", probe)
            self.assertEqual(c[probe]["httpGet"]["port"], "http", probe)

    def test_migrations_init_container(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        init = container(dep["spec"]["template"], "migrate", init=True)
        main = container(dep["spec"]["template"], "backend")
        self.assertEqual(init["image"], main["image"])
        script = " ".join(init["command"] + init.get("args", []))
        self.assertIn("alembic upgrade head", script)
        self.assertEqual(env_map(init)["DATABASE_URL"], env_map(main)["DATABASE_URL"])
        self.assertEqual(env_map(init)["POSTGRES_PASSWORD"], env_map(main)["POSTGRES_PASSWORD"])

    def test_model_cache_is_emptydir_by_default(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        volumes = {v["name"]: v for v in dep["spec"]["template"]["spec"]["volumes"]}
        self.assertIn("emptyDir", volumes["model-cache"])
        mounts = {m["name"]: m for m in container(dep["spec"]["template"], "backend")["volumeMounts"]}
        self.assertEqual(mounts["model-cache"]["mountPath"], "/cache")

    def test_backend_secret_has_generated_key(self) -> None:
        secret = self.docs[("Secret", "lc-lyrics-canvas-backend")]
        key = b64(secret["data"]["secret-key"])
        self.assertGreaterEqual(len(key), 48)
        self.assertNotIn("database-url", secret["data"])

    def test_frontend_proxies_to_backend_service(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-frontend")]
        c = container(dep["spec"]["template"], "frontend")
        self.assertEqual(env_map(c)["BACKEND_URL"]["value"], "http://lc-lyrics-canvas-backend:8000")
        self.assertEqual(c["ports"][0]["containerPort"], 8080)
        self.assertEqual(c["readinessProbe"]["httpGet"]["path"], "/healthz")
        svc = self.docs[("Service", "lc-lyrics-canvas-frontend")]
        self.assertEqual(svc["spec"]["ports"][0]["port"], 80)
        self.assertEqual(svc["spec"]["ports"][0]["targetPort"], "http")

    def test_postgres_statefulset(self) -> None:
        sts = self.docs[("StatefulSet", "lc-lyrics-canvas-postgres")]
        self.assertEqual(sts["spec"]["serviceName"], "lc-lyrics-canvas-postgres")
        self.assertEqual(sts["spec"]["replicas"], 1)
        c = container(sts["spec"]["template"], "postgres")
        self.assertEqual(c["image"], "pgvector/pgvector:pg16")
        env = env_map(c)
        self.assertEqual(env["POSTGRES_USER"]["value"], "lyrics")
        self.assertEqual(env["POSTGRES_DB"]["value"], "lyrics_canvas")
        self.assertEqual(env["PGDATA"]["value"], "/var/lib/postgresql/data/pgdata")
        self.assertEqual(
            env["POSTGRES_PASSWORD"]["valueFrom"]["secretKeyRef"],
            {"name": "lc-lyrics-canvas-postgres", "key": "password"},
        )
        mounts = {m["name"]: m for m in c["volumeMounts"]}
        self.assertEqual(mounts["initdb"]["mountPath"], "/docker-entrypoint-initdb.d")
        self.assertEqual(mounts["data"]["mountPath"], "/var/lib/postgresql/data")
        claims = {t["metadata"]["name"]: t for t in sts["spec"]["volumeClaimTemplates"]}
        self.assertEqual(claims["data"]["spec"]["resources"]["requests"]["storage"], "8Gi")
        self.assertIn("pg_isready", " ".join(c["readinessProbe"]["exec"]["command"]))

    def test_postgres_initdb_creates_vector_extension(self) -> None:
        cm = self.docs[("ConfigMap", "lc-lyrics-canvas-postgres-initdb")]
        self.assertIn("CREATE EXTENSION IF NOT EXISTS vector", cm["data"]["01-init.sql"])

    def test_postgres_secret_password_generated(self) -> None:
        secret = self.docs[("Secret", "lc-lyrics-canvas-postgres")]
        self.assertGreaterEqual(len(b64(secret["data"]["password"])), 24)

    def test_pods_use_service_account(self) -> None:
        for kind, name in (
            ("Deployment", "lc-lyrics-canvas-backend"),
            ("Deployment", "lc-lyrics-canvas-frontend"),
            ("StatefulSet", "lc-lyrics-canvas-postgres"),
        ):
            spec = self.docs[(kind, name)]["spec"]["template"]["spec"]
            self.assertEqual(spec["serviceAccountName"], "lc-lyrics-canvas", name)

    def test_helm_test_pod(self) -> None:
        pod = self.docs[("Pod", "lc-lyrics-canvas-test-connection")]
        self.assertEqual(pod["metadata"]["annotations"]["helm.sh/hook"], "test")
        script = " ".join(pod["spec"]["containers"][0]["command"] + pod["spec"]["containers"][0].get("args", []))
        self.assertIn("lc-lyrics-canvas-frontend:80/healthz", script)
        self.assertIn("lc-lyrics-canvas-backend:8000/api/health", script)

    def test_selector_labels_match(self) -> None:
        for kind, name, component in (
            ("Deployment", "lc-lyrics-canvas-backend", "backend"),
            ("Deployment", "lc-lyrics-canvas-frontend", "frontend"),
            ("StatefulSet", "lc-lyrics-canvas-postgres", "postgres"),
        ):
            workload = self.docs[(kind, name)]
            selector = workload["spec"]["selector"]["matchLabels"]
            self.assertEqual(selector["app.kubernetes.io/component"], component)
            pod_labels = workload["spec"]["template"]["metadata"]["labels"]
            for k, v in selector.items():
                self.assertEqual(pod_labels[k], v)
            svc = self.docs[("Service", name)]
            self.assertEqual(svc["spec"]["selector"], selector)


class IngressTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.docs = render("ingress.yaml")

    def test_ingress_routes(self) -> None:
        ing = self.docs[("Ingress", "lc-lyrics-canvas")]
        self.assertEqual(ing["spec"]["ingressClassName"], "nginx")
        self.assertEqual(ing["metadata"]["annotations"]["cert-manager.io/cluster-issuer"], "letsencrypt")
        rules = {r["host"]: r for r in ing["spec"]["rules"]}
        self.assertEqual(set(rules), {"lyrics.example.com", "www.lyrics.example.com"})
        paths = {p["path"]: p for p in rules["lyrics.example.com"]["http"]["paths"]}
        self.assertEqual(paths["/api"]["backend"]["service"]["name"], "lc-lyrics-canvas-backend")
        self.assertEqual(paths["/api"]["backend"]["service"]["port"]["number"], 8000)
        self.assertEqual(paths["/api"]["pathType"], "Prefix")
        self.assertEqual(paths["/"]["backend"]["service"]["name"], "lc-lyrics-canvas-frontend")
        self.assertEqual(paths["/"]["backend"]["service"]["port"]["number"], 80)
        self.assertEqual(
            ing["spec"]["tls"],
            [{"secretName": "lyrics-tls", "hosts": ["lyrics.example.com", "www.lyrics.example.com"]}],
        )

    def test_cors_origins_derived_from_tls_hosts(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(
            env["CORS_ORIGINS"]["value"],
            '["https://lyrics.example.com","https://www.lyrics.example.com"]',
        )

    def test_cors_origins_http_without_tls(self) -> None:
        docs = render("ingress.yaml", "ingress-no-tls.yaml")
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(env["CORS_ORIGINS"]["value"], '["http://lyrics.example.com"]')

    def test_api_route_can_be_disabled(self) -> None:
        docs = render("ingress.yaml", ingress__routeApiToBackend="false")
        ing = docs[("Ingress", "lc-lyrics-canvas")]
        paths = [p["path"] for p in ing["spec"]["rules"][0]["http"]["paths"]]
        self.assertEqual(paths, ["/"])


class ExternalDatabaseTest(unittest.TestCase):
    def test_external_url_in_secret(self) -> None:
        docs = render("external-db.yaml")
        self.assertNotIn(("StatefulSet", "lc-lyrics-canvas-postgres"), docs)
        self.assertNotIn(("Service", "lc-lyrics-canvas-postgres"), docs)
        self.assertNotIn(("Secret", "lc-lyrics-canvas-postgres"), docs)
        self.assertNotIn(("ConfigMap", "lc-lyrics-canvas-postgres-initdb"), docs)
        secret = docs[("Secret", "lc-lyrics-canvas-backend")]
        self.assertEqual(
            b64(secret["data"]["database-url"]),
            "postgresql+asyncpg://app:s3cret@db.internal:5432/lyrics",
        )
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        for name, init in (("backend", False), ("migrate", True)):
            env = env_map(container(dep["spec"]["template"], name, init=init))
            self.assertEqual(
                env["DATABASE_URL"]["valueFrom"]["secretKeyRef"],
                {"name": "lc-lyrics-canvas-backend", "key": "database-url"},
                name,
            )
            self.assertNotIn("POSTGRES_PASSWORD", env)

    def test_external_existing_secret(self) -> None:
        docs = render("external-db-existing-secret.yaml")
        secret = docs[("Secret", "lc-lyrics-canvas-backend")]
        self.assertNotIn("database-url", secret["data"])
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(
            env["DATABASE_URL"]["valueFrom"]["secretKeyRef"],
            {"name": "db-creds", "key": "url"},
        )

    def test_postgres_disabled_without_external_fails(self) -> None:
        result = helm("template", RELEASE, str(CHART), "--set", "postgres.enabled=false")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("externalDatabase", result.stderr)


class SecretsTest(unittest.TestCase):
    def test_explicit_secret_key(self) -> None:
        docs = render(backend__secretKey="my-jwt-key")
        self.assertEqual(b64(docs[("Secret", "lc-lyrics-canvas-backend")]["data"]["secret-key"]), "my-jwt-key")

    def test_backend_existing_secret(self) -> None:
        docs = render("backend-existing-secret.yaml")
        self.assertNotIn(("Secret", "lc-lyrics-canvas-backend"), docs)
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(
            env["SECRET_KEY"]["valueFrom"]["secretKeyRef"],
            {"name": "app-secrets", "key": "jwt"},
        )

    def test_postgres_explicit_password(self) -> None:
        docs = render(postgres__auth__password="pw123")
        self.assertEqual(b64(docs[("Secret", "lc-lyrics-canvas-postgres")]["data"]["password"]), "pw123")

    def test_postgres_existing_secret(self) -> None:
        docs = render("postgres-existing-secret.yaml")
        self.assertNotIn(("Secret", "lc-lyrics-canvas-postgres"), docs)
        sts = docs[("StatefulSet", "lc-lyrics-canvas-postgres")]
        ref = env_map(container(sts["spec"]["template"], "postgres"))["POSTGRES_PASSWORD"]["valueFrom"]["secretKeyRef"]
        self.assertEqual(ref, {"name": "pg-creds", "key": "pg-password"})
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(env["POSTGRES_PASSWORD"]["valueFrom"]["secretKeyRef"], ref)


class FullValuesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.docs = render("full.yaml")

    def test_images_and_pull_secrets(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        spec = dep["spec"]["template"]["spec"]
        self.assertEqual(container(dep["spec"]["template"], "backend")["image"], "registry.local/lc-backend:v9")
        self.assertEqual(container(dep["spec"]["template"], "backend")["imagePullPolicy"], "Always")
        self.assertEqual(spec["imagePullSecrets"], [{"name": "regcred"}])
        fe = self.docs[("Deployment", "lc-lyrics-canvas-frontend")]
        self.assertEqual(container(fe["spec"]["template"], "frontend")["image"], "registry.local/lc-frontend:v9")
        self.assertEqual(fe["spec"]["template"]["spec"]["imagePullSecrets"], [{"name": "regcred"}])
        pg = self.docs[("StatefulSet", "lc-lyrics-canvas-postgres")]
        self.assertEqual(pg["spec"]["template"]["spec"]["imagePullSecrets"], [{"name": "regcred"}])

    def test_replicas_resources_scheduling(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        self.assertEqual(dep["spec"]["replicas"], 2)
        spec = dep["spec"]["template"]["spec"]
        c = container(dep["spec"]["template"], "backend")
        self.assertEqual(c["resources"]["limits"]["memory"], "4Gi")
        self.assertEqual(spec["nodeSelector"], {"pool": "cpu"})
        self.assertEqual(spec["tolerations"][0]["key"], "dedicated")
        self.assertIn("podAntiAffinity", spec["affinity"])
        self.assertEqual(spec["securityContext"]["fsGroup"], 1000)
        self.assertEqual(c["securityContext"]["allowPrivilegeEscalation"], False)
        self.assertEqual(dep["spec"]["template"]["metadata"]["annotations"]["team"], "lyrics")
        self.assertEqual(dep["spec"]["template"]["metadata"]["labels"]["tier"], "api")
        fe = self.docs[("Deployment", "lc-lyrics-canvas-frontend")]
        self.assertEqual(fe["spec"]["replicas"], 3)

    def test_backend_extras(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(env["GOOGLE_CLIENT_ID"]["value"], "123.apps.googleusercontent.com")
        self.assertEqual(env["CORS_ORIGINS"]["value"], '["https://app.example.com","https://alt.example.com"]')
        self.assertEqual(env["LOG_LEVEL"]["value"], "debug")
        self.assertEqual(env["ACCESS_TOKEN_EXPIRE_MINUTES"]["value"], "60")
        self.assertEqual(env["FROM_SECRET"]["valueFrom"]["secretKeyRef"]["name"], "other")

    def test_model_cache_pvc(self) -> None:
        pvc = self.docs[("PersistentVolumeClaim", "lc-lyrics-canvas-model-cache")]
        self.assertEqual(pvc["spec"]["resources"]["requests"]["storage"], "5Gi")
        self.assertEqual(pvc["spec"]["storageClassName"], "fast")
        self.assertEqual(pvc["spec"]["accessModes"], ["ReadWriteMany"])
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        volumes = {v["name"]: v for v in dep["spec"]["template"]["spec"]["volumes"]}
        self.assertEqual(volumes["model-cache"]["persistentVolumeClaim"]["claimName"], "lc-lyrics-canvas-model-cache")

    def test_model_cache_existing_claim(self) -> None:
        docs = render("full.yaml", backend__modelCache__persistence__existingClaim="shared-cache")
        self.assertNotIn(("PersistentVolumeClaim", "lc-lyrics-canvas-model-cache"), docs)
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        volumes = {v["name"]: v for v in dep["spec"]["template"]["spec"]["volumes"]}
        self.assertEqual(volumes["model-cache"]["persistentVolumeClaim"]["claimName"], "shared-cache")

    def test_postgres_storage_and_resources(self) -> None:
        sts = self.docs[("StatefulSet", "lc-lyrics-canvas-postgres")]
        claim = sts["spec"]["volumeClaimTemplates"][0]["spec"]
        self.assertEqual(claim["resources"]["requests"]["storage"], "50Gi")
        self.assertEqual(claim["storageClassName"], "ssd")
        c = container(sts["spec"]["template"], "postgres")
        self.assertEqual(c["resources"]["requests"]["memory"], "1Gi")
        self.assertEqual(c["image"], "pgvector/pgvector:pg16-trixie")
        self.assertEqual(env_map(c)["POSTGRES_USER"]["value"], "songs")
        self.assertEqual(env_map(c)["POSTGRES_DB"]["value"], "songsdb")
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        env = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(
            env["DATABASE_URL"]["value"],
            "postgresql+asyncpg://songs:$(POSTGRES_PASSWORD)@lc-lyrics-canvas-postgres:5432/songsdb",
        )

    def test_service_types(self) -> None:
        self.assertEqual(self.docs[("Service", "lc-lyrics-canvas-frontend")]["spec"]["type"], "LoadBalancer")
        self.assertEqual(self.docs[("Service", "lc-lyrics-canvas-backend")]["spec"]["type"], "ClusterIP")

    def test_service_account_not_created(self) -> None:
        self.assertNotIn(("ServiceAccount", "lc-lyrics-canvas"), self.docs)
        self.assertNotIn(("ServiceAccount", "custom-sa"), self.docs)
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        self.assertEqual(dep["spec"]["template"]["spec"]["serviceAccountName"], "custom-sa")

    def test_fullname_override(self) -> None:
        docs = render(fullnameOverride="songs")
        self.assertIn(("Deployment", "songs-backend"), docs)
        self.assertIn(("StatefulSet", "songs-postgres"), docs)
        self.assertIn(("ServiceAccount", "songs"), docs)


class MigrationsTest(unittest.TestCase):
    def test_disabled_removes_init_container(self) -> None:
        docs = render(backend__migrations__enabled="false")
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        self.assertEqual(dep["spec"]["template"]["spec"].get("initContainers", []), [])

    def test_retry_parameters(self) -> None:
        docs = render(backend__migrations__attempts="7", backend__migrations__delaySeconds="3")
        dep = docs[("Deployment", "lc-lyrics-canvas-backend")]
        init = container(dep["spec"]["template"], "migrate", init=True)
        script = " ".join(init["command"] + init.get("args", []))
        self.assertIn("7", script)
        self.assertIn("sleep 3", script)


class FrontendNodePortTest(unittest.TestCase):
    def test_omitted_by_default(self) -> None:
        svc = render(frontend__service__type="NodePort")[("Service", "lc-lyrics-canvas-frontend")]
        self.assertNotIn("nodePort", svc["spec"]["ports"][0])

    def test_ignored_for_cluster_ip(self) -> None:
        svc = render(frontend__service__nodePort="31000")[("Service", "lc-lyrics-canvas-frontend")]
        self.assertNotIn("nodePort", svc["spec"]["ports"][0])


class PostgresPersistenceTest(unittest.TestCase):
    def test_disabled_uses_emptydir(self) -> None:
        docs = render(postgres__persistence__enabled="false")
        sts = docs[("StatefulSet", "lc-lyrics-canvas-postgres")]
        self.assertNotIn("volumeClaimTemplates", sts["spec"])
        volumes = {v["name"]: v for v in sts["spec"]["template"]["spec"]["volumes"]}
        self.assertIn("emptyDir", volumes["data"])


ENVIRONMENTS = HERE.parent / "environments"
REPO_ROOT = HERE.parents[2]

# Values that carry credentials. Committed values files must leave them empty;
# real values go in a gitignored `<env>.secret.yaml` next to the environment.
SECRET_PATHS = (
    ("backend", "secretKey"),
    ("postgres", "auth", "password"),
    ("externalDatabase", "url"),
)


def committed_values_files() -> list[Path]:
    return [CHART / "values.yaml", *sorted(ENVIRONMENTS.glob("*.yaml"))]


def lookup_path(values: dict, path: tuple[str, ...]) -> object:
    node: object = values
    for key in path:
        if not isinstance(node, dict) or key not in node:
            return None
        node = node[key]
    return node


class HomelabEnvironmentTest(unittest.TestCase):
    FILE = ENVIRONMENTS / "homelab.yaml"

    @classmethod
    def setUpClass(cls) -> None:
        cls.docs = render(str(cls.FILE))

    def test_lint(self) -> None:
        result = helm("lint", str(CHART), "--strict", "-f", str(self.FILE))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_images_from_cluster_registry(self) -> None:
        for component in ("backend", "frontend"):
            dep = self.docs[("Deployment", f"lc-lyrics-canvas-{component}")]
            c = container(dep["spec"]["template"], component)
            self.assertEqual(c["image"], f"localhost:32000/lyrics-canvas-{component}:dev")

    def test_workloads_pinned_to_node0_node1(self) -> None:
        for key in (
            ("Deployment", "lc-lyrics-canvas-backend"),
            ("Deployment", "lc-lyrics-canvas-frontend"),
            ("StatefulSet", "lc-lyrics-canvas-postgres"),
        ):
            affinity = self.docs[key]["spec"]["template"]["spec"]["affinity"]
            terms = affinity["nodeAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"][
                "nodeSelectorTerms"
            ]
            self.assertEqual(
                terms[0]["matchExpressions"][0],
                {"key": "kubernetes.io/hostname", "operator": "In", "values": ["node0", "node1"]},
                key,
            )

    def test_frontend_node_port_and_model_cache(self) -> None:
        svc = self.docs[("Service", "lc-lyrics-canvas-frontend")]["spec"]
        self.assertEqual(svc["type"], "NodePort")
        self.assertEqual(svc["ports"][0]["nodePort"], 32443)
        self.assertIn(("PersistentVolumeClaim", "lc-lyrics-canvas-model-cache"), self.docs)


class CommittedValuesSafetyTest(unittest.TestCase):
    def test_environments_exist(self) -> None:
        self.assertTrue(list(ENVIRONMENTS.glob("*.yaml")), "no environment values files found")

    def test_no_credentials_in_committed_values(self) -> None:
        for path in committed_values_files():
            values = yaml.safe_load(path.read_text()) or {}
            for secret in SECRET_PATHS:
                self.assertIn(
                    lookup_path(values, secret),
                    (None, ""),
                    f"{path.name} sets {'.'.join(secret)}; put it in a .secret.yaml file",
                )

    def test_secret_overrides_are_gitignored(self) -> None:
        if shutil.which("git") is None or not (REPO_ROOT / ".git").exists():
            self.skipTest("not in a git checkout")
        probe = ENVIRONMENTS.relative_to(REPO_ROOT) / "homelab.secret.yaml"
        result = subprocess.run(
            ["git", "check-ignore", "-q", str(probe)], cwd=REPO_ROOT, check=False
        )
        self.assertEqual(result.returncode, 0, f"{probe} is not gitignored")


class SchemaTest(unittest.TestCase):
    def assert_rejected(self, *sets: str) -> None:
        args = ["template", RELEASE, str(CHART)]
        for s in sets:
            args += ["--set", s]
        result = helm(*args)
        self.assertNotEqual(result.returncode, 0, "expected schema rejection for " + ", ".join(sets))

    def test_rejects_bad_types(self) -> None:
        self.assert_rejected("backend.replicaCount=two")
        self.assert_rejected("backend.service.type=Bogus")
        self.assert_rejected("ingress.hosts[0]=1")
        self.assert_rejected("postgres.persistence.size=big")

    def test_rejects_node_port_out_of_range(self) -> None:
        self.assert_rejected("frontend.service.type=NodePort", "frontend.service.nodePort=8080")
        self.assert_rejected("frontend.service.type=NodePort", "frontend.service.nodePort=40000")

    def test_rejects_unknown_keys(self) -> None:
        self.assert_rejected("backend.replicas=2")
        self.assert_rejected("postgresql.enabled=false")


BACKUP = "lc-lyrics-canvas-backup"
DUMP_GLOB = "lyrics_canvas-[0-9]*T[0-9]*Z.dump"


def backup_pod(docs: dict) -> dict:
    return docs[("CronJob", BACKUP)]["spec"]["jobTemplate"]["spec"]["template"]


def backup_script(docs: dict) -> list[str]:
    """The backup container's full argv (command + args)."""
    c = container(backup_pod(docs), "backup")
    return c["command"] + c.get("args", [])


class BackupDisabledTest(unittest.TestCase):
    def test_nothing_rendered_by_default(self) -> None:
        docs = render()
        self.assertNotIn("CronJob", {kind for kind, _ in docs})
        self.assertFalse([key for key in docs if "backup" in key[1]], "backup resources rendered while disabled")

    def test_disabled_with_external_database_renders(self) -> None:
        docs = render("external-db.yaml")
        self.assertNotIn(("CronJob", BACKUP), docs)


class BackupHostPathTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.docs = render("backup-hostpath.yaml")
        cls.cron = cls.docs[("CronJob", BACKUP)]
        cls.pod = backup_pod(cls.docs)
        cls.c = container(cls.pod, "backup")

    def test_cronjob_identity(self) -> None:
        self.assertEqual(self.cron["apiVersion"], "batch/v1")
        self.assertEqual(self.cron["metadata"]["name"], "lc-lyrics-canvas-backup")
        self.assertEqual(self.cron["metadata"]["labels"]["app.kubernetes.io/component"], "backup")
        self.assertEqual(self.pod["metadata"]["labels"]["app.kubernetes.io/component"], "backup")
        self.assertEqual(self.pod["metadata"]["labels"]["tier"], "backup")
        self.assertEqual(self.pod["metadata"]["annotations"]["backup"], "nightly")

    def test_schedule_and_job_policy(self) -> None:
        spec = self.cron["spec"]
        self.assertEqual(spec["schedule"], "45 2 * * *")
        self.assertEqual(spec["timeZone"], "Europe/Berlin")
        self.assertEqual(spec["concurrencyPolicy"], "Forbid")
        self.assertEqual(spec["startingDeadlineSeconds"], 600)
        self.assertEqual(spec["successfulJobsHistoryLimit"], 2)
        self.assertEqual(spec["failedJobsHistoryLimit"], 6)
        job = spec["jobTemplate"]["spec"]
        self.assertEqual(job["backoffLimit"], 4)
        self.assertEqual(job["activeDeadlineSeconds"], 900)
        self.assertEqual(self.pod["spec"]["restartPolicy"], "Never")

    def test_uses_postgres_image(self) -> None:
        self.assertEqual(self.c["image"], "pgvector/pgvector:pg16")
        self.assertEqual(self.c["imagePullPolicy"], "IfNotPresent")
        docs = render("backup-hostpath.yaml", postgres__image__tag="pg16-trixie")
        self.assertEqual(container(backup_pod(docs), "backup")["image"], "pgvector/pgvector:pg16-trixie")

    def test_image_override(self) -> None:
        docs = render(
            "backup-hostpath.yaml",
            backup__image__repository="registry.local/pg",
            backup__image__tag="pg17",
            backup__image__pullPolicy="Never",
        )
        c = container(backup_pod(docs), "backup")
        self.assertEqual(c["image"], "registry.local/pg:pg17")
        self.assertEqual(c["imagePullPolicy"], "Never")

    def test_connection_env_and_password_secret(self) -> None:
        env = env_map(self.c)
        self.assertEqual(env["PGHOST"]["value"], "lc-lyrics-canvas-postgres")
        self.assertEqual(env["PGPORT"]["value"], "5432")
        self.assertEqual(env["PGUSER"]["value"], "lyrics")
        self.assertEqual(env["PGDATABASE"]["value"], "lyrics_canvas")
        self.assertEqual(
            env["PGPASSWORD"]["valueFrom"]["secretKeyRef"],
            {"name": "lc-lyrics-canvas-postgres", "key": "password"},
        )
        self.assertNotIn("value", env["PGPASSWORD"])
        self.assertEqual(env["BACKUP_RETAIN"]["value"], "5")
        self.assertEqual(env["BACKUP_DIR"]["value"], "/backups")

    def test_password_secret_matches_backend(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-backend")]
        backend = env_map(container(dep["spec"]["template"], "backend"))
        self.assertEqual(
            env_map(self.c)["PGPASSWORD"]["valueFrom"],
            backend["POSTGRES_PASSWORD"]["valueFrom"],
        )

    def test_existing_postgres_secret(self) -> None:
        docs = render("postgres-existing-secret.yaml", "backup-hostpath.yaml")
        ref = env_map(container(backup_pod(docs), "backup"))["PGPASSWORD"]["valueFrom"]["secretKeyRef"]
        self.assertEqual(ref, {"name": "pg-creds", "key": "pg-password"})

    def test_explicit_password_never_inlined(self) -> None:
        docs = render("backup-hostpath.yaml", postgres__auth__password="pw-not-in-cronjob-123")
        self.assertNotIn("pw-not-in-cronjob-123", yaml.safe_dump(docs[("CronJob", BACKUP)]))

    def test_custom_auth_and_port(self) -> None:
        docs = render("full.yaml", "backup-hostpath.yaml", postgres__service__port="6543")
        env = env_map(container(backup_pod(docs), "backup"))
        self.assertEqual(env["PGUSER"]["value"], "songs")
        self.assertEqual(env["PGDATABASE"]["value"], "songsdb")
        self.assertEqual(env["PGPORT"]["value"], "6543")
        self.assertEqual(backup_pod(docs)["spec"]["imagePullSecrets"], [{"name": "regcred"}])

    def test_runs_pg_dump_custom_format(self) -> None:
        argv = backup_script(self.docs)
        self.assertEqual(argv[:2], ["/bin/sh", "-c"])
        script = argv[2]
        self.assertTrue(script.lstrip().startswith("set -eu"), script[:40])
        self.assertIn("pg_dump --format=custom", script)

    def test_hostpath_volume(self) -> None:
        volumes = {v["name"]: v for v in self.pod["spec"]["volumes"]}
        self.assertEqual(volumes["backups"]["hostPath"], {"path": "/srv/backups/lyrics", "type": "Directory"})
        self.assertIn("emptyDir", volumes["tmp"])
        mounts = {m["name"]: m for m in self.c["volumeMounts"]}
        self.assertEqual(mounts["backups"]["mountPath"], "/backups")
        self.assertEqual(mounts["tmp"]["mountPath"], "/tmp")
        self.assertNotIn(("PersistentVolumeClaim", BACKUP), self.docs)

    def test_scheduling(self) -> None:
        spec = self.pod["spec"]
        self.assertEqual(spec["nodeSelector"], {"kubernetes.io/hostname": "store1"})
        self.assertEqual(spec["tolerations"][0]["key"], "storage")
        self.assertIn("nodeAffinity", spec["affinity"])
        self.assertEqual(spec["serviceAccountName"], "lc-lyrics-canvas")

    def test_security_context(self) -> None:
        psc = self.pod["spec"]["securityContext"]
        self.assertEqual(psc["runAsUser"], 1000)
        self.assertEqual(psc["runAsGroup"], 1001)
        self.assertEqual(psc["fsGroup"], 1001)
        self.assertTrue(psc["runAsNonRoot"])
        csc = self.c["securityContext"]
        self.assertFalse(csc["allowPrivilegeEscalation"])
        self.assertTrue(csc["readOnlyRootFilesystem"])
        self.assertEqual(csc["capabilities"]["drop"], ["ALL"])

    def test_resources(self) -> None:
        self.assertEqual(self.c["resources"]["requests"]["memory"], "32Mi")
        self.assertEqual(self.c["resources"]["limits"]["memory"], "256Mi")

    def test_time_zone_omitted_when_empty(self) -> None:
        docs = render("backup-hostpath.yaml", backup__timeZone="")
        self.assertNotIn("timeZone", docs[("CronJob", BACKUP)]["spec"])

    def test_defaults(self) -> None:
        docs = render(backup__enabled="true", backup__destination__hostPath__path="/b")
        spec = docs[("CronJob", BACKUP)]["spec"]
        self.assertEqual(spec["concurrencyPolicy"], "Forbid")
        self.assertEqual(spec["schedule"], "15 3 * * *")
        pod = backup_pod(docs)
        self.assertEqual(env_map(container(pod, "backup"))["BACKUP_RETAIN"]["value"], "14")
        self.assertEqual(pod["spec"]["securityContext"]["runAsUser"], 999)
        self.assertNotIn("nodeSelector", pod["spec"])

    def test_fullname_override(self) -> None:
        docs = render("backup-hostpath.yaml", fullnameOverride="songs")
        pod = docs[("CronJob", "songs-backup")]["spec"]["jobTemplate"]["spec"]["template"]
        self.assertEqual(env_map(container(pod, "backup"))["PGHOST"]["value"], "songs-postgres")

    def test_lint(self) -> None:
        result = helm("lint", str(CHART), "--strict", "-f", str(VALUES / "backup-hostpath.yaml"))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class BackupPvcTest(unittest.TestCase):
    def test_chart_managed_claim(self) -> None:
        docs = render("backup-pvc.yaml")
        pvc = docs[("PersistentVolumeClaim", BACKUP)]
        self.assertEqual(pvc["spec"]["resources"]["requests"]["storage"], "3Gi")
        self.assertEqual(pvc["spec"]["storageClassName"], "slow")
        self.assertEqual(pvc["spec"]["accessModes"], ["ReadWriteOnce"])
        self.assertEqual(pvc["metadata"]["annotations"]["helm.sh/resource-policy"], "keep")
        volumes = {v["name"]: v for v in backup_pod(docs)["spec"]["volumes"]}
        self.assertEqual(volumes["backups"]["persistentVolumeClaim"], {"claimName": BACKUP})
        self.assertNotIn("hostPath", volumes["backups"])

    def test_existing_claim(self) -> None:
        docs = render("backup-pvc.yaml", backup__destination__persistentVolumeClaim__existingClaim="old-dumps")
        self.assertNotIn(("PersistentVolumeClaim", BACKUP), docs)
        volumes = {v["name"]: v for v in backup_pod(docs)["spec"]["volumes"]}
        self.assertEqual(volumes["backups"]["persistentVolumeClaim"], {"claimName": "old-dumps"})

    def test_lint(self) -> None:
        result = helm("lint", str(CHART), "--strict", "-f", str(VALUES / "backup-pvc.yaml"))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class BackupValidationTest(unittest.TestCase):
    def assert_rejected(self, *sets: str, values: tuple[str, ...] = (), message: str = "") -> None:
        args = ["template", RELEASE, str(CHART)]
        for name in values:
            args += ["-f", str(VALUES / name)]
        for s in sets:
            args += ["--set", s]
        result = helm(*args)
        self.assertNotEqual(result.returncode, 0, "expected rejection for " + ", ".join(sets))
        if message:
            self.assertIn(message, result.stderr)

    def test_external_database_not_supported(self) -> None:
        self.assert_rejected(
            "backup.enabled=true",
            "backup.destination.hostPath.path=/b",
            values=("external-db.yaml",),
            message="backup.enabled requires postgres.enabled",
        )

    def test_enabled_hostpath_requires_absolute_path(self) -> None:
        self.assert_rejected("backup.enabled=true")
        self.assert_rejected("backup.enabled=true", "backup.destination.hostPath.path=relative/dir")

    def test_rejects_bad_values(self) -> None:
        base = ("backup.enabled=true", "backup.destination.hostPath.path=/b")
        self.assert_rejected(*base, "backup.retain=0")
        self.assert_rejected(*base, "backup.retain=many")
        self.assert_rejected(*base, "backup.destination.type=nfs")
        self.assert_rejected(*base, "backup.schedule=nightly")
        self.assert_rejected(*base, "backup.schedule=")
        self.assert_rejected(*base, "backup.runAsUser=0")
        self.assert_rejected(*base, "backup.runAsGroup=-1")
        self.assert_rejected(*base, "backup.backoffLimit=-1")
        self.assert_rejected(*base, "backup.image.pullPolicy=Sometimes")
        self.assert_rejected(
            "backup.enabled=true",
            "backup.destination.type=persistentVolumeClaim",
            "backup.destination.persistentVolumeClaim.size=lots",
        )

    def test_rejects_unknown_keys(self) -> None:
        self.assert_rejected("backup.retention=3")
        self.assert_rejected("backup.destination.nfs.server=nas")
        self.assert_rejected("backup.destination.hostPath.type=DirectoryOrCreate")

    def test_accepts_schedule_macro(self) -> None:
        docs = render(backup__enabled="true", backup__destination__hostPath__path="/b", backup__schedule="@daily")
        self.assertEqual(docs[("CronJob", BACKUP)]["spec"]["schedule"], "@daily")


DUMP_NAME = re.compile(r"^lyrics_canvas-\d{8}T\d{6}Z\.dump$")

FAKE_PG_DUMP = """#!/bin/sh
# Test double: writes a fake archive to --file=..., records what the directory
# looked like mid-dump, and fails when FAKE_PG_DUMP_FAIL is set.
for arg; do
  case "$arg" in --file=*) out="${arg#--file=}" ;; esac
done
[ -n "${out:-}" ] || { echo "no --file" >&2; exit 2; }
printf 'PGDMP fake archive\\n' > "$out"
ls -A "$(dirname "$out")" > "$FAKE_LOG_DIR/during-dump.txt"
printf '%s\\n' "$*" > "$FAKE_LOG_DIR/pg_dump-args.txt"
env | grep '^PG' | sort > "$FAKE_LOG_DIR/pg_dump-env.txt"
[ -z "${FAKE_PG_DUMP_FAIL:-}" ] || exit 1
"""

FAKE_PG_RESTORE = """#!/bin/sh
# Test double: `pg_restore --list FILE` succeeds when FILE looks like our fake archive.
[ "$1" = "--list" ] || exit 2
[ -z "${FAKE_PG_RESTORE_FAIL:-}" ] || exit 1
head -c 5 "$2" | grep -q PGDMP
"""


class BackupScriptTest(unittest.TestCase):
    """Runs the rendered container command against a temp directory with
    pg_dump/pg_restore test doubles first on PATH."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.argv = backup_script(render("backup-hostpath.yaml"))
        cls.env_defaults = {
            e["name"]: e["value"]
            for e in container(backup_pod(render("backup-hostpath.yaml")), "backup")["env"]
            if "value" in e
        }

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="lc-backup-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.dir = self.tmp / "backups"
        self.dir.mkdir()
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.logs = self.tmp / "logs"
        self.logs.mkdir()
        for name, body in (("pg_dump", FAKE_PG_DUMP), ("pg_restore", FAKE_PG_RESTORE)):
            path = self.bin / name
            path.write_text(body)
            path.chmod(0o755)

    def run_backup(self, **env: str) -> subprocess.CompletedProcess[str]:
        full_env = {
            **self.env_defaults,
            "PATH": f"{self.bin}:{os.environ.get('PATH', '/usr/bin:/bin')}",
            "BACKUP_DIR": str(self.dir),
            "PGPASSWORD": "from-secret",
            "FAKE_LOG_DIR": str(self.logs),
            **env,
        }
        return subprocess.run(self.argv, env=full_env, capture_output=True, text=True, check=False)

    def dumps(self) -> list[str]:
        return sorted(p.name for p in self.dir.iterdir() if DUMP_NAME.match(p.name))

    def seed(self, count: int) -> list[str]:
        names = [f"lyrics_canvas-2020{1 + i // 28:02d}{1 + i % 28:02d}T031500Z.dump" for i in range(count)]
        for name in names:
            (self.dir / name).write_text("old")
        return names

    def test_writes_one_dump_atomically(self) -> None:
        result = self.run_backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.dumps()), 1)
        self.assertEqual(sorted(p.name for p in self.dir.iterdir()), self.dumps(), "temp file left behind")
        self.assertTrue((self.dir / self.dumps()[0]).read_text().startswith("PGDMP"))
        # While pg_dump was writing, only a hidden temp name existed.
        during = (self.logs / "during-dump.txt").read_text().split()
        self.assertEqual(len(during), 1, during)
        self.assertTrue(during[0].startswith("."), during)
        self.assertFalse(DUMP_NAME.match(during[0]))
        self.assertIn("level=info", result.stdout)

    def test_dump_is_owner_only(self) -> None:
        self.assertEqual(self.run_backup().returncode, 0)
        mode = (self.dir / self.dumps()[0]).stat().st_mode & 0o777
        self.assertEqual(mode, 0o600)

    def test_pg_dump_args_and_env(self) -> None:
        self.assertEqual(self.run_backup().returncode, 0)
        args = (self.logs / "pg_dump-args.txt").read_text()
        self.assertIn("--format=custom", args)
        self.assertIn("--no-password", args)
        env = (self.logs / "pg_dump-env.txt").read_text().splitlines()
        self.assertIn("PGHOST=lc-lyrics-canvas-postgres", env)
        self.assertIn("PGPASSWORD=from-secret", env)

    def test_prunes_to_retain_newest(self) -> None:
        old = self.seed(7)
        (self.dir / "notes.txt").write_text("keep me")
        (self.dir / "lyrics_canvas-manual.dump").write_text("keep me")
        result = self.run_backup()  # retain=5 in the fixture
        self.assertEqual(result.returncode, 0, result.stderr)
        dumps = self.dumps()
        self.assertEqual(len(dumps), 5)
        self.assertEqual(dumps[:4], old[-4:])
        self.assertNotIn(old[0], dumps)
        self.assertTrue((self.dir / "notes.txt").exists())
        self.assertTrue((self.dir / "lyrics_canvas-manual.dump").exists())

    def test_retain_from_env(self) -> None:
        self.seed(3)
        self.assertEqual(self.run_backup(BACKUP_RETAIN="1").returncode, 0)
        dumps = self.dumps()
        self.assertEqual(len(dumps), 1)
        self.assertFalse(dumps[0].startswith("lyrics_canvas-2020"))

    def test_under_retain_keeps_everything(self) -> None:
        old = self.seed(2)
        self.assertEqual(self.run_backup().returncode, 0)
        self.assertEqual(self.dumps()[:2], old)
        self.assertEqual(len(self.dumps()), 3)

    def test_pg_dump_failure_fails_and_keeps_old(self) -> None:
        old = self.seed(7)
        result = self.run_backup(FAKE_PG_DUMP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.dumps(), old, "failed run must not add or prune dumps")
        self.assertEqual(sorted(p.name for p in self.dir.iterdir()), old, "partial file left behind")
        self.assertIn("level=error", result.stderr)

    def test_unreadable_archive_fails(self) -> None:
        result = self.run_backup(FAKE_PG_RESTORE_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(self.dir.iterdir()), [])

    def test_missing_directory_fails(self) -> None:
        result = self.run_backup(BACKUP_DIR=str(self.tmp / "nope"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("level=error", result.stderr)

    def test_invalid_retain_fails(self) -> None:
        for bad in ("0", "abc", ""):
            result = self.run_backup(BACKUP_RETAIN=bad)
            self.assertNotEqual(result.returncode, 0, bad)
            self.assertEqual(list(self.dir.iterdir()), [], bad)

    def test_missing_password_fails(self) -> None:
        env = {k: v for k, v in self.env_defaults.items()}
        result = subprocess.run(
            self.argv,
            env={**env, "PATH": f"{self.bin}:/usr/bin:/bin", "BACKUP_DIR": str(self.dir), "FAKE_LOG_DIR": str(self.logs)},
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(self.dir.iterdir()), [])

    def test_stale_partials_removed_fresh_kept(self) -> None:
        stale = self.dir / ".lyrics_canvas-20200101T000000Z.dump.partial"
        fresh = self.dir / ".lyrics_canvas-20991231T000000Z.dump.partial"
        stale.write_text("x")
        fresh.write_text("x")
        two_days_ago = time.time() - 2 * 86400
        os.utime(stale, (two_days_ago, two_days_ago))
        self.assertEqual(self.run_backup().returncode, 0)
        self.assertFalse(stale.exists())
        self.assertTrue(fresh.exists())

    def test_shellcheck(self) -> None:
        shellcheck = shutil.which("shellcheck")
        if shellcheck is None:
            self.skipTest("shellcheck not installed")
        script = self.tmp / "backup.sh"
        script.write_text("#!/bin/sh\n" + self.argv[2])
        result = subprocess.run([shellcheck, "-s", "sh", str(script)], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stdout)


class HomelabBackupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.docs = render(str(ENVIRONMENTS / "homelab.yaml"))

    def test_backup_runs_on_node1_hostpath(self) -> None:
        cron = self.docs[("CronJob", BACKUP)]
        self.assertEqual(cron["spec"]["concurrencyPolicy"], "Forbid")
        self.assertEqual(cron["spec"]["timeZone"], "America/Los_Angeles")
        pod = backup_pod(self.docs)
        self.assertEqual(pod["spec"]["nodeSelector"], {"kubernetes.io/hostname": "node1"})
        volumes = {v["name"]: v for v in pod["spec"]["volumes"]}
        self.assertEqual(
            volumes["backups"]["hostPath"],
            {"path": "/home/nymph/backups/lyrics-canvas", "type": "Directory"},
        )
        psc = pod["spec"]["securityContext"]
        self.assertEqual((psc["runAsUser"], psc["runAsGroup"]), (1000, 1000))
        self.assertEqual(env_map(container(pod, "backup"))["BACKUP_RETAIN"]["value"], "14")
        self.assertEqual(container(pod, "backup")["image"], "pgvector/pgvector:pg16")
        self.assertNotIn(("PersistentVolumeClaim", BACKUP), self.docs)


if __name__ == "__main__":
    unittest.main(verbosity=1)
