# PostgreSQL Backup

Nightly logical backups of the bundled PostgreSQL, written by a CronJob in
the Helm chart, plus an optional systemd user timer that pulls those dumps to
a second machine (the homelab laptop).

## Scope

- `backup` values section and a `<fullname>-backup` CronJob that runs
  `pg_dump --format=custom` against the bundled `<fullname>-postgres`
  Service.
- Destination: a pre-existing hostPath directory on a pinned node, or a PVC
  (chart-managed, or an existing claim).
- Atomic file publication (hidden temp name, then rename) and retention of
  the newest `backup.retain` dumps.
- Homelab wiring: dumps on node1 at `/home/nymph/backups/lyrics-canvas`,
  owned by nymph (1000:1000).
- `deploy/homelab/backup/`: a pull script, systemd user service + timer
  templates and an installer that copies the dumps over ssh/rsync to the
  laptop (`~/Backups/lyrics-canvas`).
- Restore procedure (below).

## Non-scope

- External databases. `backup.enabled=true` with `postgres.enabled=false`
  fails to render with
  `backup.enabled requires postgres.enabled=true ...`. A managed database
  has its own backup tooling, and the chart only holds its URL in asyncpg form
  (`postgresql+asyncpg://...`, with asyncpg-style query options), which is not
  a reliable libpq connection string.
- Point-in-time recovery, WAL archiving, physical (`pg_basebackup`) backups.
- Encryption of dumps at rest (files are mode 600 in a mode 700 directory).
- Alerting on missing or failed backups. The sibling alert rules match
  CronJobs named `*-backup`, which is why the CronJob name is fixed.
- Automated restore. Restoring is a deliberate manual step.
- Off-site copies beyond the laptop pull.

## Data and control flow

### Nightly dump (cluster)

1. At `backup.schedule` (homelab: 03:15 America/Los_Angeles) the CronJob
   controller creates a Job. `concurrencyPolicy: Forbid` skips a slot while
   a previous Job is still running; `startingDeadlineSeconds` drops a slot
   that could not start in time (for example, the control plane was down).
2. The pod is scheduled per `backup.nodeSelector` / `affinity` /
   `tolerations` (homelab: `kubernetes.io/hostname: node1`). It runs as
   `backup.runAsUser:runAsGroup` with `runAsNonRoot`, a read-only root
   filesystem, no capabilities and an emptyDir at `/tmp`.
3. The container (`backup.image`, defaulting to `postgres.image` so
   `pg_dump` matches the server major version) runs the script from
   `templates/backup/_script.tpl` via `/bin/sh -c`. Inputs are env vars:
   `BACKUP_DIR=/backups` (the destination volume), `BACKUP_RETAIN`, and libpq's
   `PGHOST` (`<fullname>-postgres`), `PGPORT`, `PGUSER`, `PGDATABASE`,
   `PGCONNECT_TIMEOUT=30`, and `PGPASSWORD` from a `secretKeyRef` to the same
   Secret/key the backend and StatefulSet use
   (`lyrics-canvas.postgres.secretName` +
   `postgres.auth.existingSecretPasswordKey`).
4. The script (`set -eu`, `umask 077`, `LC_ALL=C`):
   1. validates `BACKUP_DIR`, `BACKUP_RETAIN` (integer >= 1) and
      `PGPASSWORD`, and that the directory exists and is writable;
   2. deletes `.lyrics_canvas-*.dump.partial` files older than a day (left
      by a pod killed before its EXIT trap ran);
   3. runs `pg_dump --format=custom --no-password` into
      `.lyrics_canvas-<ts>.dump.partial`, where `<ts>` is
      `date -u +%Y%m%dT%H%M%SZ`;
   4. checks the file is non-empty and that `pg_restore --list` can read it,
      fsyncs it (`sync FILE`), and renames it to
      `lyrics_canvas-<ts>.dump`. The rename is atomic within the directory,
      so a reader never sees a partial file under the final name;
   5. globs the strict `lyrics_canvas-<8 digits>T<6 digits>Z.dump` pattern
      (C collation, so lexical = chronological) and deletes the oldest until
      `BACKUP_RETAIN` remain. Other files in the directory are untouched.
   Any failure exits non-zero; an EXIT trap removes the temp file. Pruning
   runs only after a successful dump, so a failing database never erodes the
   existing backups. Logs are `level=... msg="..." key=value` lines (errors
   to stderr).
5. A failed pod is retried up to `backup.backoffLimit` times, bounded by
   `backup.activeDeadlineSeconds`; then the Job is marked failed and kept per
   `failedJobsHistoryLimit`.

### Laptop pull

1. `lyrics-canvas-backup-pull.timer` (user unit) fires daily at
   `--on-calendar` (default 04:30 local, plus up to 10 minutes of random
   delay). `Persistent=true` runs a missed slot as soon as the laptop is awake
   again.
2. It starts `lyrics-canvas-backup-pull.service` (`Type=oneshot`), whose
   `Environment=` lines carry `BACKUP_SOURCE` (`<host>:<path>`),
   `BACKUP_DEST` and `BACKUP_KEEP`, and whose `ExecStart` is the installed
   copy of `pull.sh`.
3. `pull.sh` creates `BACKUP_DEST` (mode 700) and runs
   `rsync --archive --partial-dir=.rsync-partial` over
   `ssh -o BatchMode=yes -o ConnectTimeout=15`, including only the strict
   final dump pattern (hidden `.partial` files never match). There is no
   `--delete`: dumps pruned on node1 stay on the laptop.
4. With `BACKUP_KEEP=N > 0`, the oldest local dumps beyond N are deleted;
   `0` (the default) keeps everything.
5. On failure (laptop away from home, node1 down) the service exits non-zero
   and `Restart=on-failure` retries every 30 minutes
   (`StartLimitIntervalSec=0`, so no retry cap) until a pull succeeds.

## Files

| File | Role |
|------|------|
| `deploy/helm/lyrics-canvas/values.yaml` (`backup:`) | Defaults: disabled, `15 3 * * *`, retain 14, uid/gid 999, hostPath destination with empty path, 10Gi PVC settings, deadlines, history limits, resources. |
| `deploy/helm/lyrics-canvas/values.schema.json` (`backup`) | `additionalProperties: false` throughout; cron/macro pattern for `schedule`; `retain >= 1`; `runAsUser`/`runAsGroup >= 1`; `destination.type` enum `hostPath` / `persistentVolumeClaim`; `hostPath.path` absolute and (via `if`/`then`) required when enabled with a hostPath destination. |
| `deploy/helm/lyrics-canvas/templates/_helpers.tpl` | `lyrics-canvas.backup.fullname` (`<fullname>-backup`, truncated to 52 chars, the CronJob name limit), `backup.image`, `backup.imagePullPolicy`, `backup.claimName`. |
| `deploy/helm/lyrics-canvas/templates/backup/_script.tpl` | `lyrics-canvas.backup.script`: the dump/verify/rename/prune shell script. |
| `deploy/helm/lyrics-canvas/templates/backup/cronjob.yaml` | The CronJob. Fails rendering when postgres is disabled or a hostPath destination has no path. |
| `deploy/helm/lyrics-canvas/templates/backup/pvc.yaml` | `<fullname>-backup` claim for `destination.type=persistentVolumeClaim` without `existingClaim`; `helm.sh/resource-policy: keep` so uninstall does not delete dumps. |
| `deploy/helm/environments/homelab.yaml` (`backup:`) | Enabled, America/Los_Angeles, node1 hostPath `/home/nymph/backups/lyrics-canvas`, uid/gid 1000. |
| `deploy/helm/tests/test_chart.py` | `BackupDisabledTest`, `BackupHostPathTest`, `BackupPvcTest`, `BackupValidationTest`, `BackupScriptTest` (runs the rendered command with `pg_dump`/`pg_restore` test doubles against a temp dir), `HomelabBackupTest`. |
| `deploy/helm/tests/values/backup-hostpath.yaml`, `backup-pvc.yaml` | Fixtures. |
| `deploy/homelab/backup/pull.sh` | rsync pull + local retention. Env: `BACKUP_SOURCE`, `BACKUP_DEST`, `BACKUP_KEEP`. |
| `deploy/homelab/backup/install.sh` | Options `--host` (`node1`), `--path` (`/home/nymph/backups/lyrics-canvas`), `--dest` (`~/Backups/lyrics-canvas`), `--keep` (`0`), `--on-calendar` (`*-*-* 04:30:00`), `--unit-dir` (`~/.config/systemd/user`), `--lib-dir` (`~/.local/libexec/lyrics-canvas`), `--no-enable`. Validates, copies `pull.sh` to `<lib-dir>/backup-pull.sh`, renders the templates, `daemon-reload`, `enable --now` the timer. |
| `deploy/homelab/backup/lyrics-canvas-backup-pull.service.in`, `.timer.in` | Unit templates with `@SOURCE@`, `@DEST@`, `@KEEP@`, `@PULL_SCRIPT@`, `@ON_CALENDAR@`. |
| `deploy/homelab/backup/tests/test_backup_pull.py` | Pull against a local source dir (filtering, no-delete history, keep-N), installer rendering/validation, `systemd-analyze verify`, shellcheck when installed. |

## Invariants and constraints

- The CronJob is named exactly `<fullname>-backup`; alert rules depend on
  the `*-backup` suffix.
- The password is only ever a `secretKeyRef`; no rendered manifest contains
  it (tested with an explicit `postgres.auth.password`).
- A file named `lyrics_canvas-<ts>.dump` is always a complete archive that
  `pg_restore --list` accepted. In-progress files are hidden
  (`.lyrics_canvas-*.dump.partial`) and never match the final pattern used by
  pruning and by the laptop's rsync filter.
- Pruning only happens after a successful dump and only touches files
  matching the strict pattern.
- The hostPath uses `type: Directory`: the directory must exist and be
  writable by `runAsUser`, otherwise the pod fails to start (visible as a
  failed Job) instead of silently creating a root-owned directory.
- `backup.image` (default `postgres.image`) must carry a `pg_dump` at least as
  new as the server major version, and must already be present on the backup
  node or pullable when the Job runs.
- Two runs in the same second would collide; the second fails rather than
  overwrite (`concurrencyPolicy: Forbid` prevents this for scheduled runs).
- `timeZone` needs Kubernetes 1.27+; leave it empty on older clusters.
- The laptop pull never deletes a dump because it disappeared upstream.
- Values rendered into unit files may not contain `%`, `$`, `"`, `\` or
  newlines (the installer refuses them rather than escaping).
- The pull runs `ssh` with `BatchMode=yes` from the systemd user manager: the
  key for `node1` must be usable without a prompt (an unencrypted key, or an
  agent whose `SSH_AUTH_SOCK` is in the user manager's environment, e.g.
  `systemctl --user import-environment SSH_AUTH_SOCK`).

## Operating

Deploy (homelab): pre-create the directory on node1 (owner 1000:1000, mode
700), make sure node1 has the postgres image (see below), then upgrade the
release with `homelab.yaml` as usual.

```bash
# image: node1 has no pgvector image cached; either let the first Job pull it
# from Docker Hub, or pre-pull it there:
ssh node1 'microk8s ctr image pull docker.io/pgvector/pgvector:pg16'

# run a backup now instead of waiting for 03:15
microk8s kubectl -n lyrics-canvas create job --from=cronjob/lyrics-lyrics-canvas-backup lyrics-backup-manual-$(date +%s)
microk8s kubectl -n lyrics-canvas logs -f job/lyrics-backup-manual-<id>
ssh node1 ls -l /home/nymph/backups/lyrics-canvas

# laptop
deploy/homelab/backup/install.sh            # defaults: node1, the path above, ~/Backups/lyrics-canvas
systemctl --user start lyrics-canvas-backup-pull.service   # first pull now
journalctl --user -u lyrics-canvas-backup-pull.service
```

## Restore

Dumps are custom-format archives; restore them with `pg_restore` inside the
postgres pod, whose local socket needs no password. From the laptop the dump
is streamed through ssh (`kubectl` lives on node0); from node1 replace the
`< ~/Backups/...` path with the file in `/home/nymph/backups/lyrics-canvas`.

```bash
NS=lyrics-canvas; PG=lyrics-lyrics-canvas-postgres-0
DUMP=~/Backups/lyrics-canvas/lyrics_canvas-20261029T101500Z.dump
k() { ssh node0 microk8s kubectl -n "$NS" "$@"; }

# 1. Sanity-check the archive and restore it into a scratch database first.
k exec "$PG" -- sh -c 'createdb -U "$POSTGRES_USER" lyrics_canvas_restore'
k exec -i "$PG" -- sh -c 'pg_restore -U "$POSTGRES_USER" -d lyrics_canvas_restore --no-owner --exit-on-error' < "$DUMP"
k exec "$PG" -- sh -c 'psql -U "$POSTGRES_USER" -d lyrics_canvas_restore -c "select count(*) from snippets"'

# 2. Stop writers, then replace the live database's contents in one transaction.
k scale deploy/lyrics-lyrics-canvas-backend --replicas=0
k exec -i "$PG" -- sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --single-transaction --exit-on-error' < "$DUMP"
k scale deploy/lyrics-lyrics-canvas-backend --replicas=1

# 3. Drop the scratch database.
k exec "$PG" -- sh -c 'dropdb -U "$POSTGRES_USER" lyrics_canvas_restore'
```

The dump includes `alembic_version`, so the backend's migrate init container
sees the schema version of the dump and upgrades it if the image is newer.
To restore into a fresh install (empty PVC), install the release, wait for
the postgres pod, scale the backend to 0 and run step 2.
