"""Render-and-assert tests for the lyrics-canvas Helm chart.

Run with:  python3 deploy/helm/tests/test_chart.py
Set HELM to a helm binary if it is not on PATH.
"""

from __future__ import annotations

import atexit
import base64
import os
import shutil
import subprocess
import tempfile
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


class FrontendClusterAlertsTest(unittest.TestCase):
    """frontend.clusterAlerts feeds the nginx /_status/alerts endpoint."""

    @staticmethod
    def frontend_env(docs: dict) -> dict[str, dict]:
        dep = docs[("Deployment", "lc-lyrics-canvas-frontend")]
        return env_map(container(dep["spec"]["template"], "frontend"))

    def test_disabled_by_default(self) -> None:
        env = self.frontend_env(render())
        self.assertEqual(env["ALERTMANAGER_URL"]["value"], "")
        self.assertEqual(env["ALERTS_RESOLVER"]["value"], "")

    def test_namespace_defaults_to_release_namespace(self) -> None:
        env = self.frontend_env(render())
        self.assertEqual(env["ALERTS_NAMESPACE"]["value"], NAMESPACE)

    def test_alertmanager_url_passed_through(self) -> None:
        env = self.frontend_env(
            render(frontend__clusterAlerts__alertmanagerUrl="http://am.monitoring.svc:9093")
        )
        self.assertEqual(env["ALERTMANAGER_URL"]["value"], "http://am.monitoring.svc:9093")
        self.assertEqual(env["ALERTS_NAMESPACE"]["value"], NAMESPACE)

    def test_namespace_and_resolver_overrides(self) -> None:
        env = self.frontend_env(
            render(
                frontend__clusterAlerts__alertmanagerUrl="https://am.example.com",
                frontend__clusterAlerts__namespace="other-ns",
                frontend__clusterAlerts__resolver="10.152.183.10",
            )
        )
        self.assertEqual(env["ALERTMANAGER_URL"]["value"], "https://am.example.com")
        self.assertEqual(env["ALERTS_NAMESPACE"]["value"], "other-ns")
        self.assertEqual(env["ALERTS_RESOLVER"]["value"], "10.152.183.10")

    def test_backend_url_unchanged(self) -> None:
        env = self.frontend_env(
            render(frontend__clusterAlerts__alertmanagerUrl="http://am.monitoring.svc:9093")
        )
        self.assertEqual(env["BACKEND_URL"]["value"], "http://lc-lyrics-canvas-backend:8000")


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

    def test_frontend_cluster_alerts_from_monitoring_alertmanager(self) -> None:
        dep = self.docs[("Deployment", "lc-lyrics-canvas-frontend")]
        env = env_map(container(dep["spec"]["template"], "frontend"))
        self.assertEqual(env["ALERTMANAGER_URL"]["value"], "http://kps-alertmanager.monitoring.svc:9093")
        self.assertEqual(env["ALERTS_NAMESPACE"]["value"], NAMESPACE)


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

    def test_rejects_alertmanager_url_with_path_or_query(self) -> None:
        # Only scheme://host[:port] is accepted; nginx appends the one fixed path.
        self.assert_rejected("frontend.clusterAlerts.alertmanagerUrl=http://am:9093/api/v2/status")
        self.assert_rejected("frontend.clusterAlerts.alertmanagerUrl=http://am:9093?x=1")
        self.assert_rejected("frontend.clusterAlerts.alertmanagerUrl=ftp://am:9093")
        self.assert_rejected("frontend.clusterAlerts.alertmanagerUrl=am:9093")
        self.assert_rejected("frontend.clusterAlerts.alertmanagerUrl=http://user@am:9093")

    def test_rejects_bad_alerts_namespace_and_resolver(self) -> None:
        self.assert_rejected("frontend.clusterAlerts.namespace=Bad_NS")
        self.assert_rejected("frontend.clusterAlerts.namespace=ns\"; evil")
        self.assert_rejected("frontend.clusterAlerts.resolver=dns.example.com")
        self.assert_rejected("frontend.clusterAlerts.resolver=10.0.0.1;evil")
        self.assert_rejected("frontend.clusterAlerts.enabled=true")

    def test_accepts_valid_cluster_alerts_values(self) -> None:
        result = helm(
            "template", RELEASE, str(CHART),
            "--set", "frontend.clusterAlerts.alertmanagerUrl=http://kps-alertmanager.monitoring.svc:9093/",
            "--set", "frontend.clusterAlerts.resolver=10.152.183.10 [fd00::a]:53",
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_unknown_keys(self) -> None:
        self.assert_rejected("backend.replicas=2")
        self.assert_rejected("postgresql.enabled=false")


if __name__ == "__main__":
    unittest.main(verbosity=1)
