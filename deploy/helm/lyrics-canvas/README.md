# lyrics-canvas Helm chart

Deploys Lyrics Canvas to Kubernetes: the FastAPI backend, the React frontend
served by nginx, and (optionally) PostgreSQL 16 with pgvector.

```
Ingress (/ -> frontend, /api -> backend)
   |                          |
frontend Deployment       backend Deployment
nginx-unprivileged        uvicorn, init container runs `alembic upgrade head`
(/api proxied to backend)     |
                          postgres StatefulSet (pgvector/pgvector:pg16, PVC)
                          or externalDatabase.url
```

## Images

The chart does not build images. For the home cluster,
`deploy/homelab/deploy.sh` builds, pushes and deploys in one step (see
[Environments](#environments)). Elsewhere, build and push both from the
repository root:

```bash
REG=ghcr.io/rhizonymph TAG=0.1.0
docker build -t $REG/lyrics-canvas-backend:$TAG backend
docker build -f frontend/Dockerfile.prod -t $REG/lyrics-canvas-frontend:$TAG \
  --build-arg VITE_GOOGLE_CLIENT_ID=<google client id or empty> frontend
docker push $REG/lyrics-canvas-backend:$TAG
docker push $REG/lyrics-canvas-frontend:$TAG
```

`VITE_*` settings are compiled into the frontend bundle, so the Google client
id has to be given at image build time (and the matching
`backend.googleClientId` at install time). The image tag defaults to the
chart's `appVersion`; override with `backend.image.tag` / `frontend.image.tag`.

## Install

```bash
helm install lyrics deploy/helm/lyrics-canvas \
  --namespace lyrics --create-namespace \
  --set ingress.enabled=true \
  --set ingress.className=nginx \
  --set 'ingress.hosts[0]=lyrics.example.com' \
  --set 'ingress.tls[0].secretName=lyrics-tls' \
  --set 'ingress.tls[0].hosts[0]=lyrics.example.com'
```

Without an Ingress, port-forward the frontend Service; its nginx proxies
`/api` to the backend so the SPA works from a single origin:

```bash
kubectl -n lyrics port-forward svc/lyrics-lyrics-canvas-frontend 8080:80
```

Run `helm test lyrics` to hit both health endpoints from inside the cluster.

## Environments

Per-cluster values live in `deploy/helm/environments/<name>.yaml` and are
committed. They must not contain credentials: `backend.secretKey`,
`postgres.auth.password` and `externalDatabase.url` stay empty there (the
chart test suite fails otherwise). Generated credentials are kept across
upgrades; anything set explicitly goes in `<name>.secret.yaml` beside it,
which is gitignored, passed as a second `-f`.

`homelab.yaml` is the home microk8s cluster: images from the
`localhost:32000` registry addon, all workloads on node0/node1, the
frontend on NodePort 32443 reached at `http://10.1.1.69:32443` (node0's IP;
the node names are ssh aliases, not DNS names), the nightly backup CronJob
writing to `/home/nymph/backups/lyrics-canvas` on node1 (see
[Backups](#backups)), the alert rules enabled for the cluster's
kube-prometheus-stack, and `/_status/alerts` reading its Alertmanager
(`kps-alertmanager.monitoring.svc:9093`).

Deploy it from a clean checkout with:

```bash
deploy/homelab/deploy.sh            # --dry-run, --skip-build, --rebuild, --allow-dirty, -h
```

It ships the committed tree (`git archive HEAD`) to node0 over ssh, builds
both images there with `--network host`, pushes them tagged with the short
commit SHA (skipping tags already in the registry), runs the upgrade below
with `--set backend.image.tag=<sha> --set frontend.image.tag=<sha> --wait`
(plus `--atomic` once the release exists), then `helm test`, and prints the
URL and revision. See `docs/features/homelab_deploy.md` for configuration and
exit codes.

The equivalent manual upgrade, run on node0 (it falls back to the `dev` tags):

```bash
microk8s helm3 upgrade --install lyrics deploy/helm/lyrics-canvas \
  -n lyrics-canvas --create-namespace -f deploy/helm/environments/homelab.yaml
```

Always pass the values file; never use `--reuse-values`.

## Configuration highlights

| Value | Default | Notes |
|-------|---------|-------|
| `backend.secretKey` | generated | JWT signing key. Generated once and re-read from the live Secret on upgrade. Or set `backend.existingSecret` / `existingSecretKey`. |
| `frontend.service.nodePort` | unset | Fixed port (30000-32767), used only when `frontend.service.type=NodePort`. |
| `frontend.clusterAlerts.alertmanagerUrl` | `""` | `http(s)://host[:port]` of an Alertmanager the frontend pod can reach. Enables `GET /_status/alerts` (this namespace's firing alerts, served by nginx even while the backend is down); empty answers `[]`. `namespace` (default: release namespace) and `resolver` (default: the pod's resolv.conf nameservers) are optional. See `docs/features/cluster_alerts_endpoint.md`. |
| `backend.corsOrigins` | derived | Defaults to one origin per `ingress.hosts` entry (https when covered by `ingress.tls`). |
| `backend.googleClientId` | `""` | Enables Google sign-in verification on the API. |
| `backend.migrations.enabled` | `true` | Init container runs Alembic before each backend pod starts, retrying until the database is reachable. |
| `backend.modelCache.persistence.enabled` | `false` | PVC for the sentence-transformers model download (`HF_HOME`). Needs `ReadWriteMany` for more than one replica across nodes. |
| `backend.resources` | 1Gi/3Gi memory | torch + the embedding model need the headroom. |
| `postgres.enabled` | `true` | Bundled single-replica pgvector StatefulSet with an 8Gi PVC. |
| `postgres.auth.password` | generated | Kept across upgrades like the signing key. Or `postgres.auth.existingSecret`. |
| `backup.enabled` | `false` | Nightly `pg_dump` CronJob `<fullname>-backup`; see [Backups](#backups). |
| `externalDatabase.url` | `""` | `postgresql+asyncpg://...` for a managed database when `postgres.enabled=false`. The database must allow `CREATE EXTENSION vector`. Or `externalDatabase.existingSecret`. |
| `ingress.routeApiToBackend` | `true` | Adds a `/api` path straight to the backend Service. |
| `global.imagePullSecrets` | `[]` | Applied to every pod. |
| `monitoring.prometheusRule.enabled` | `false` | Renders a `PrometheusRule` with alerts for this release (see below). |

See `values.yaml` for the full list; `values.schema.json` rejects unknown keys
and wrong types at install time.

## Alerting

`monitoring.prometheusRule.enabled=true` adds a `PrometheusRule`
(`monitoring.coreos.com/v1`) named after the release, for prometheus-operator
/ kube-prometheus-stack. It needs the CRD, a Prometheus whose rule selectors
match it (add labels with `monitoring.prometheusRule.labels`, e.g.
`release: kps`), and **kube-state-metrics** scraped by that Prometheus; all
expressions read `kube_*` series.

| Alert | Fires when (defaults) |
|-------|-----------------------|
| `LyricsCanvasPodNotReady` | A release pod is Pending / not Ready / Unknown for 10m (catches pods stuck in the `migrate` init container). |
| `LyricsCanvasContainerRestarting` | A container or init container restarted more than 3 times in 1h. |
| `LyricsCanvasContainerWaiting` | A container or init container sits in CrashLoopBackOff, ImagePullBackOff, ... for 5m. |
| `LyricsCanvasDeploymentUnavailable` / `LyricsCanvasStatefulSetUnavailable` | Unavailable replicas for 10m. |
| `LyricsCanvasPodMetricsAbsent` | No kube-state-metrics series for the release's pods for 15m (the other alerts are blind). |
| `LyricsCanvasBackupStale` | A `*-backup` CronJob in the namespace has not succeeded for 36h (`backup.maxAgeHours`). |
| `LyricsCanvasBackupJobFailed` | A Job of a `*-backup` CronJob failed and no later run succeeded. |

Each alert block (`podNotReady`, `containerRestarts`, `containerWaiting`,
`workloadUnavailable`, `metricsAbsent`, `backup`) has `enabled`, a `for`
duration and `severity`; restart `threshold`/`window`, waiting `reasons` and
backup `maxAgeHours` are tunable too. Descriptions say which `kubectl`
commands to run; set `kubectlCommand` (homelab: `microk8s kubectl`).
Notification routing is configured in Alertmanager, not in this chart. Details:
[docs/features/alerting.md](../../../docs/features/alerting.md).

## Runtime notes

- First request to the similarity or t-SNE endpoints downloads
  `all-MiniLM-L6-v2` from huggingface.co into `HF_HOME` (`/cache`). Pods need
  egress to reach it unless the cache PVC is already warm.
- The backend image bakes NLTK data for g2p_en at build time, so no network is
  needed for rhyme features.
- The bundled PostgreSQL is one replica. Enable `backup` for nightly dumps,
  or use `externalDatabase` with the provider's backups.

## Backups

`backup.enabled=true` adds a CronJob `<fullname>-backup` that runs
`pg_dump --format=custom` against the bundled PostgreSQL (it refuses to
render with `postgres.enabled=false`). Each run writes
`lyrics_canvas-<UTC timestamp>.dump` under a hidden temp name, checks it with
`pg_restore --list`, renames it into place and keeps the newest
`backup.retain` (14) dumps. The password comes from the postgres Secret via
`secretKeyRef`; the image defaults to `postgres.image`.

| Value | Default | Notes |
|-------|---------|-------|
| `backup.schedule` / `timeZone` | `15 3 * * *` / `""` | Cron or `@daily`-style macro; `timeZone` (IANA, Kubernetes 1.27+) defaults to the controller's zone. |
| `backup.retain` | `14` | Dumps kept in the destination. |
| `backup.destination.type` | `hostPath` | Or `persistentVolumeClaim`. |
| `backup.destination.hostPath.path` | `""` | Required for hostPath. Must already exist on the node (`type: Directory`) and be writable by `runAsUser`; pin the pod with `backup.nodeSelector`/`affinity`. |
| `backup.destination.persistentVolumeClaim.*` | 10Gi, RWO | `existingClaim`, or a chart claim `<fullname>-backup` kept on uninstall. |
| `backup.runAsUser` / `runAsGroup` | `999` | Owner of the dump files (mode 600). |
| `backup.image.*` | `postgres.image` | Override only if a different `pg_dump` is needed (at least the server's major version). |
| `backup.backoffLimit`, `activeDeadlineSeconds`, `startingDeadlineSeconds` | `2`, `3600`, `3600` | Job retries and time limits. `concurrencyPolicy` is always `Forbid`. |
| `backup.successfulJobsHistoryLimit` / `failedJobsHistoryLimit` | `3` / `3` | |
| `backup.resources`, `nodeSelector`, `tolerations`, `affinity`, `podAnnotations`, `podLabels` | | As for the other workloads. |

Run one now with
`kubectl -n <ns> create job --from=cronjob/<fullname>-backup <name>`.
The homelab laptop pulls node1's dumps with a systemd user timer from
`deploy/homelab/backup/install.sh`. The restore procedure is in
`docs/features/postgres_backup.md`.

## Tests

```bash
python3 deploy/helm/tests/test_chart.py      # needs helm and PyYAML
python3 deploy/homelab/backup/tests/test_backup_pull.py   # needs bash and rsync
python3 deploy/homelab/tests/test_deploy_script.py   # deploy script, stdlib only
```

With `promtool` on PATH (or `PROMTOOL` set to a command), the suite also runs
`promtool check rules` and the rule unit tests in `tests/alerts/`.
