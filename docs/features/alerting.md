# Alerting

Prometheus alerting rules shipped with the Helm chart as a `PrometheusRule`
(`monitoring.coreos.com/v1`), so a prometheus-operator stack such as
kube-prometheus-stack picks them up. Added after the backend was down for
about two days (the `migrate` init container looped on PyPI DNS errors) and
nobody noticed.

## Scope

- One `PrometheusRule` named `<fullname>`, rendered only when
  `monitoring.prometheusRule.enabled=true` (default `false`; on in
  `environments/homelab.yaml`).
- Alerts on this release's pods and workloads in the release namespace:
  pod not Ready, container (including init container) restart loops and bad
  waiting reasons, Deployment / StatefulSet replicas unavailable.
- Alerts on backup CronJobs (`*-backup` in the release namespace): last
  success too old, a failed run with no later success. They do nothing when
  no such CronJob exists, and have their own toggle.
- A meta-alert that fires when kube-state-metrics has no series for the
  release's pods, because then every other alert is silently inert.
- Every alert has a `severity` label and `summary` / `description`
  annotations; descriptions name the `kubectl` commands to run, using
  `monitoring.prometheusRule.kubectlCommand` (`microk8s kubectl` on homelab).
- Per-alert tunables (`for` durations, severities, restart threshold and
  window, waiting reasons, backup max age), validated by the values schema.

## Non-scope

- **Receivers and routing.** Where alerts go (email, ntfy, Discord, ...) and
  grouping or inhibition are set in the kube-prometheus-stack Alertmanager
  config (release `kps`, namespace `monitoring`), not in this chart. Without a
  receiver the alerts are visible in Prometheus, Alertmanager and Grafana but
  nobody is notified.
- Installing or configuring kube-state-metrics or Prometheus. The chart
  assumes both exist.
- Application metrics (request rates, latency, errors). The backend exposes
  no `/metrics` endpoint, and there is no ServiceMonitor.
- The backup CronJob itself (sibling `feat/postgres-backup` work).
- Grafana dashboards.

## Prerequisites on the cluster

1. The `PrometheusRule` CRD (installed with kube-prometheus-stack). Rendering
   with `enabled=true` without the CRD makes `helm install` fail.
2. A Prometheus whose `ruleSelector` / `ruleNamespaceSelector` match the
   rule. On homelab both are `{}` (every rule in every namespace). Elsewhere,
   put the labels the selector needs in `monitoring.prometheusRule.labels`
   (kube-prometheus-stack defaults to `release: <kps release name>`).
3. **kube-state-metrics, scraped by that Prometheus.** Every expression reads
   `kube_*` series. On homelab, kube-prometheus-stack was installed with
   `kubeStateMetrics.enabled: false` (checked 2026-10-28: there is no KSM
   Deployment, ServiceMonitor or `kube_*` series), so until that is turned
   on only `LyricsCanvasPodMetricsAbsent` can fire, and it does.

## Alerts

All selectors carry `namespace="<release namespace>"`. "Release pods" means
`pod=~"<fullname>-.*"`; workloads match `deployment=~` / `statefulset=~` the
same prefix. Defaults in brackets.

| Alert | Group | Fires when | for | severity |
|-------|-------|-----------|-----|----------|
| `LyricsCanvasPodNotReady` | pods | A release pod has Ready condition `false`/`unknown`, or phase `Pending`/`Unknown`, and is not `Succeeded`/`Failed` | `podNotReady.for` [10m] | [critical] |
| `LyricsCanvasContainerRestarting` | pods | `increase(restarts_total[window]) > threshold` for any container or init container | `containerRestarts.for` [0m]; window [1h], threshold [3] | [warning] |
| `LyricsCanvasContainerWaiting` | pods | A container or init container is waiting with one of `containerWaiting.reasons` [CrashLoopBackOff, ImagePullBackOff, ErrImagePull, CreateContainerConfigError, CreateContainerError] | [5m] | [warning] |
| `LyricsCanvasPodMetricsAbsent` | pods | `absent(kube_pod_info{namespace, pod=~prefix})`: KSM missing, or the release has no pods | [15m] | [warning] |
| `LyricsCanvasDeploymentUnavailable` | workloads | `kube_deployment_status_replicas_unavailable > 0` | `workloadUnavailable.for` [10m] | [critical] |
| `LyricsCanvasStatefulSetUnavailable` | workloads | `kube_statefulset_replicas - kube_statefulset_status_replicas_ready > 0` | same | [critical] |
| `LyricsCanvasBackupStale` | backup | `time() - last_successful_time > maxAgeHours*3600` [36h], or created that long ago with no success yet | `backup.staleFor` [15m] | `backup.severity` [warning] |
| `LyricsCanvasBackupJobFailed` | backup | A Job owned by a `*-backup` CronJob has `status.failed > 0`, and the CronJob has not succeeded since that Job started | `backup.failedFor` [0m] | same |

### How the outage case is caught

A backend pod looping in `migrate` is `Pending` with Ready=False
(`Init:0/1` or `Init:CrashLoopBackOff`). `PodNotReady` fires after 10m.
`ContainerRestarting` fires once the init container has restarted more than
3 times in an hour: the script retries for about 150s, then the kubelet
back-off caps at 5m, so that is about 8 restarts an hour. `ContainerWaiting`
fires if it sits in CrashLoopBackOff for 5m. `DeploymentUnavailable` fires
after 10m. A pod on a lost node (Ready=`unknown`) and an unschedulable pod
(Pending with no Ready condition yet) are caught by the phase branch.

## Data and control flow

```
values.yaml / environments/*.yaml
  monitoring.prometheusRule.*  --(values.schema.json validates)-->
templates/monitoring/prometheusrule.yaml
  builds selectors from Release.Namespace + lyrics-canvas.fullname,
  interpolates tunables, emits only enabled rules, omits empty groups
      |
      v  helm upgrade
PrometheusRule <fullname> in the release namespace
      |  prometheus-operator (kps-operator) watches PrometheusRules matching
      |  the Prometheus ruleSelector / ruleNamespaceSelector and writes them
      |  into the Prometheus rule files
      v
Prometheus kps-prometheus evaluates every 15s against kube-state-metrics
series (kube_pod_*, kube_deployment_*, kube_statefulset_*, kube_cronjob_*,
kube_job_*)
      |  firing alerts
      v
Alertmanager kps-alertmanager -> receivers per its own config (not managed here)
```

Template details:

- `$pod` is `namespace="<ns>", pod=~"<fullname>-.*"`. Pod names are matched by
  prefix, not via `kube_pod_labels`, because KSM only exports `label_*` on
  `kube_pod_labels` for labels allowlisted with `--metric-labels-allowlist`
  (empty by default). Every pod of this chart (Deployments, the StatefulSet,
  the helm test pod, future Jobs) is named `<fullname>-...`.
- `PodNotReady` drops pods in `Succeeded`/`Failed`, so a completed
  `helm test` pod or backup Job pod does not count. Evicted (Failed) pods are
  left to `DeploymentUnavailable`.
- Each expression aggregates with `max by (...)` to a fixed label set
  (`namespace` plus `pod`/`container`/`reason`, `deployment`, `statefulset`,
  `cronjob`, `job_name`), so the result does not depend on scrape labels
  and stays stable if KSM runs with more than one replica.
- `BackupJobFailed` uses `kube_job_owner{owner_kind="CronJob",
  owner_name=~".*-backup"}` (`label_replace` turns `owner_name` into
  `cronjob`) to find backup Jobs. Manual runs created with
  `kubectl create job --from=cronjob/...` are owned by the CronJob too. A
  failed Job stops alerting once its CronJob's `last_successful_time` is
  after the Job's `start_time`, or when the Job is deleted. Otherwise it keeps
  firing until the CronJob history limit removes it.
- `BackupStale` has two branches because KSM emits
  `kube_cronjob_status_last_successful_time` only after a first success. A
  suspended backup CronJob counts as stale; that is intended.
- Prometheus template text in annotations (`{{ $labels.pod }}`) is written as
  Helm raw strings (``{{`...`}}``), so Helm passes it through unchanged.

## Seeing alerts

- Prometheus: <http://10.1.1.69:30900/alerts> (filter `LyricsCanvas`) shows
  inactive, pending and firing rules; `/rules` shows the loaded group
  `lyrics-canvas.pods` etc. with their last evaluation error, if any. Node
  names are not in DNS; use the IP.
- Grafana: <http://10.1.1.69:30300>, Alerting -> Alert rules (data source
  rules for the Prometheus data source) or Explore with the query
  `ALERTS{alertname=~"LyricsCanvas.*"}`.
- Alertmanager (silences, what was sent):
  `microk8s kubectl -n monitoring port-forward svc/kps-alertmanager 9093`.
- From a shell:
  `curl -s http://10.1.1.69:30900/api/v1/alerts | jq '.data.alerts[] | select(.labels.alertname | startswith("LyricsCanvas"))'`.

## Files

| File | Role |
|------|------|
| `deploy/helm/lyrics-canvas/templates/monitoring/prometheusrule.yaml` | The PrometheusRule: three groups (`lyrics-canvas.pods`, `.workloads`, `.backup`), each rendered only when it has an enabled rule; `groups: []` when none. |
| `deploy/helm/lyrics-canvas/values.yaml` (`monitoring.prometheusRule`) | `enabled`, `labels`, `kubectlCommand`, and one block per alert: `podNotReady`, `containerRestarts` (`threshold`, `window`), `containerWaiting` (`reasons`), `workloadUnavailable`, `metricsAbsent`, `backup` (`maxAgeHours`, `staleFor`, `failedFor`). Each has `enabled`, `for` and `severity` (backup: one `severity` for both alerts). |
| `deploy/helm/lyrics-canvas/values.schema.json` | `monitoring` section, plus `promDuration` (the operator's duration pattern), `promRange` (a single non-zero `<n><unit>` for range selectors), `severity` (`critical`/`warning`/`info`) and `alert` definitions. `kubectlCommand` only allows words of `[A-Za-z0-9_./-]`, so it cannot inject template syntax. |
| `deploy/helm/environments/homelab.yaml` | Enables the rule with `kubectlCommand: microk8s kubectl`. |
| `deploy/helm/tests/test_chart.py` | `PrometheusRule*` classes: off by default, alert set, namespace and name-prefix scoping of every selector, default and overridden tunables, labels, toggles, schema rejections, homelab. `PrometheusRulePromtoolTest` runs `promtool check rules` and the unit tests when `promtool` is on PATH or `PROMTOOL` is set (a command prefix is allowed, run from a temp dir with relative paths); it is skipped otherwise. |
| `deploy/helm/tests/values/alerts.yaml` | Fixture overriding every tunable. |
| `deploy/helm/tests/alerts/rules_test.yaml` | `promtool test rules` scenarios with synthetic KSM series for release `lc` / namespace `songs` at default tunables: stuck in init (the outage), Ready unknown, unschedulable, healthy and out-of-scope pods, completed test pod, metrics absent, stale / never-run / fresh backups, failed backup Jobs with and without a later success. Annotations are stripped before the run, so they are not compared. |

## Invariants and constraints

- Every selector in every expression carries `namespace="<release
  namespace>"`, and every pod/workload selector carries the
  `<fullname>-.*` prefix. Tests count selectors to enforce this. A new alert
  must keep this.
- Workload and pod names of this chart must start with `<fullname>-`. A new
  component named differently would be invisible to these alerts.
- The fullname is used in a regex without escaping. Release names are
  DNS-1123 (`[a-z0-9-.]`), so the only metacharacter possible is `.`, which
  would also match any one character: harmless.
- Rule results have fixed label sets (see above); the promtool unit tests
  assert exact labels, so changing a `by (...)` clause means updating
  `rules_test.yaml`.
- `PodNotReady.for` must stay longer than a normal backend start (init
  container plus the `startupTimeoutSeconds` probe window, about 3 to 4
  minutes), or every rollout pages.
- The backup alerts depend only on the `*-backup` CronJob naming, not on any
  values of the backup feature.
- Nothing here sends notifications by itself; that needs an Alertmanager
  receiver in the kps config.
