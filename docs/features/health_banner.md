# Health Banner

A banner in the web app that says when the backend is down or unreachable, and lists firing cluster
alerts. In production nginx serves the SPA and keeps answering when the backend is dead (then `/api/*`
returns 502/504), so the browser has to notice by itself: it polls `GET /api/health`, watches the outcome
of ordinary API calls, and polls `GET /_status/alerts` for Alertmanager alerts.

## Scope

- Backend health from `GET /api/health` (200 `{"status":"ok"}` is healthy), polled every 15 s while the tab
  is visible, immediately on start, on the tab becoming visible and on the `online` event; exponential
  backoff while down.
- Passive signal from every call made through `api/client.ts`: 502/503/504 = down, no response =
  unreachable, 2xx/4xx = backend answered. Callers see unchanged responses and errors.
- Three distinctions: backend down (the server answered with a 5xx or a bad health body), unreachable
  (no answer: offline device, network error, timeout), ok.
- Debounce: two consecutive failures before "down" is shown; the browser's `offline` event confirms at once.
  A "Back online" note for 5 s after recovery.
- The outage text says changes may not be saving.
- Cluster alerts from `GET /_status/alerts` (nginx in front of Alertmanager's v2 API, sibling branch
  `feat/status-alerts-endpoint`), validated at runtime; firing alerts shown critical first, then warning,
  then other, newest first, with summary, start time and an expandable description.
- Dismissal per incident: a dismissed card returns when the incident changes (new outage, down vs
  unreachable, an alert re-firing or changing severity).
- Silent when the alerts endpoint does not exist (404, or the SPA's `index.html` fallback with 200).

## Non-scope

- Retrying or queueing saves that failed while the backend was down. The banner only warns.
- Alert silencing/acknowledging, or links to Grafana/Alertmanager.
- Android client (it has its own networking).
- Persisting dismissals across reloads (in memory only).
- Server-side work: `/_status/alerts` lives in nginx on the sibling branch; `/api/health` already exists.

## Contracts

| Request | Answer | Meaning |
|---|---|---|
| `GET /api/health` | 200 `{"status":"ok"}` | ok |
| | 200, other body | down (`invalid_body`) |
| | any other status | down (`http`, status) |
| | no response | unreachable (`offline` if `navigator.onLine` is false, `timeout` on ECONNABORTED/ETIMEDOUT, else `network`) |
| `GET /_status/alerts` | 200 JSON array | alert list (`[]` = none, or Alertmanager not configured) |
| | 200 not a JSON array | endpoint absent (SPA fallback) |
| | 404 | endpoint absent (older deployment, Vite dev) |
| | 5xx (503 `{"error":"alertmanager_unavailable"}`) | unavailable: last known alerts kept, marked stale |
| | other status / no response | failed: last known alerts kept, marked stale |

Alert entry fields used: `labels.alertname` (required, non-empty), `labels.severity` (`critical`,
`warning`, anything else = `other`), `annotations.summary`, `annotations.description` (optional),
`startsAt` (required, parseable date), `status.state` (required string; only `active` is shown;
`suppressed`/`unprocessed` are skipped), `fingerprint` (optional; identity, else the sorted string labels).
Entries failing the required checks are dropped and counted; the poller logs the count once when it changes.

Passive classification of a failed `api/client.ts` call: 502/503/504 down; no response unreachable;
4xx ok; 500 and other 5xx no signal (a backend bug, or the Vite dev proxy failing to connect); cancellations
no signal. A success is ok.

## Data and control flow

```
App
 ├─ useStatusMonitoring()  (mount once)
 │    ├─ createHealthMonitor({ probe: probeHealth, onChange: statusStore.setHealth })
 │    │     timers: nextProbeDelay(state); settle after RECOVERY_DISPLAY_MS
 │    ├─ apiHealthChannel.subscribe(o => monitor.observe(o, 'passive'))
 │    │     ▲ published by installHealthInterceptors(api, …) in api/client.ts
 │    ├─ createAlertsPoller({ fetch: fetchClusterAlerts,
 │    │     onResult: r => statusStore.setAlerts(applyAlertFetch(alerts, r, now)) })
 │    └─ document visibilitychange → pause/resume both; window online → probe/poll now;
 │       window offline → monitor.browserOffline()
 └─ <StatusBanner/>  reads statusStore → buildBannerModel(health, alerts) → visibleItems(model, dismissed)
                     → <StatusBannerView/> (dismiss → statusStore.dismiss(key))
```

### Health state machine (`lib/health/machine.ts`, pure)

| From | Event | To |
|---|---|---|
| unknown / ok / recovered | failure (probe or passive) | suspect (failures 1) |
| suspect | probe failure | failing (failures 2, `since` = now) |
| suspect | passive failure | suspect (unchanged: a burst of parallel failures is one observation) |
| failing | probe failure | failing (failures + 1, latest problem, same `since`) |
| failing | passive failure | unchanged |
| any | browser `offline` | failing (unreachable/offline; `since` kept if already failing) |
| failing | ok (probe or passive) | recovered (`at`, `downSince`) |
| unknown / suspect | ok | ok |
| ok / recovered | ok | unchanged (same object) |
| recovered | settle | ok |

Probe delay per state (`nextProbeDelay`):

| State | Delay |
|---|---|
| unknown, ok, recovered | `HEALTHY_PROBE_MS` = 15 s |
| suspect | `SUSPECT_PROBE_MS` = 3 s |
| failing | `DOWN_BACKOFF_BASE_MS` (5 s) × 2^(failures − 2), capped at `DOWN_BACKOFF_MAX_MS` (60 s) |

So an outage that starts between probes is shown after at most ~18 s (15 s + 3 s), or ~3 s after a failing
API call. "Back online" stays `RECOVERY_DISPLAY_MS` = 5 s.

The monitor (`lib/health/monitor.ts`) runs one probe at a time, reschedules when a passive observation
changes the phase (so a passive failure pulls the confirming probe forward to 3 s), never polls while
paused, and ignores results after `stop`. A probe that rejects (a bug) is logged and counted as unreachable.

Alerts are polled every `ALERTS_POLL_MS` = 30 s, or `ALERTS_ABSENT_POLL_MS` = 5 min once the endpoint is
absent, paused while the tab is hidden.

### Banner model (`lib/statusBanner/items.ts`, pure)

- Backend item first (only for `failing` and `recovered`), then alert items by severity and newest start.
  The backend item takes priority but alerts stay visible under it.
- `failing` → role `alert`, tone danger, key `backend:<down|unreachable>@<since>`; text by cause
  ("Server is down" + HTTP status, "You're offline", "Can't reach the server"), always ending
  "Changes you make now may not be saving."
- `recovered` → role `status`, tone success, key `backend:recovered@<at>`.
- Alert → key `alert:<fingerprint|labels>@<startsAt>#<severity>`; critical = role `alert`/danger,
  warning = `status`/warning, other = `status`/info. Title is the summary (else the alert name).
- `alertsStale` is true when the alert list is the last known one and non-empty; the view then adds an
  "out of date" note.

### Dismissal (`stores/statusStore.ts`)

`dismiss(key)` adds a currently shown key. `setHealth`/`setAlerts` prune dismissed keys to the keys still
shown, so an ended incident's dismissal is forgotten, and a new incident (new key) is shown again.

## Files

| File | Role | Key exports |
|---|---|---|
| `frontend/src/lib/health/types.ts` | Health domain types | `HealthState`, `HealthObservation`, `HealthProblem`, `DownCause`, `UnreachableReason`, `HealthEvent`, `ObservationSource` |
| `frontend/src/lib/health/machine.ts` | Pure transition + probe delay | `transition`, `nextProbeDelay`, `INITIAL_HEALTH_STATE`, timing constants |
| `frontend/src/lib/health/classify.ts` | HTTP outcome → observation | `observationFromProbeResponse`, `observationFromProbeError`, `passiveObservationFromError` |
| `frontend/src/lib/health/channel.ts` | Client → monitor pub/sub | `createObservationChannel`, `apiHealthChannel` |
| `frontend/src/lib/health/interceptors.ts` | Pass-through axios response interceptor | `installHealthInterceptors` |
| `frontend/src/lib/health/monitor.ts` | Timers around the machine | `createHealthMonitor`, `HealthMonitor` |
| `frontend/src/lib/alerts/types.ts` | Alert domain + fetch result types | `ClusterAlert`, `AlertSeverity`, `AlertFetchResult`, `AlertFetchError`, `AlertsState` |
| `frontend/src/lib/alerts/parse.ts` | Runtime validation and folding | `parseAlerts`, `interpretAlertsResponse`, `alertFetchFailure`, `applyAlertFetch` |
| `frontend/src/lib/alerts/poller.ts` | Alerts polling loop | `createAlertsPoller`, `ALERTS_POLL_MS`, `ALERTS_ABSENT_POLL_MS` |
| `frontend/src/lib/statusBanner/types.ts` | View model | `BannerItem`, `BannerModel`, `BannerTone` |
| `frontend/src/lib/statusBanner/items.ts` | State → ordered items, dismissal helpers | `buildBannerModel`, `visibleItems`, `pruneDismissed` |
| `frontend/src/api/status.ts` | Separate axios instance for the two probes (no auth, no 401 redirect, every status resolves, 8 s timeout) | `probeHealth`, `fetchClusterAlerts` |
| `frontend/src/api/client.ts` | Installs the health interceptor after the 401 interceptor | — |
| `frontend/src/stores/statusStore.ts` | Zustand store: health, alerts, dismissed | `useStatusStore` |
| `frontend/src/hooks/useStatusMonitoring.ts` | Wires monitor, poller, channel and browser events | `useStatusMonitoring` |
| `frontend/src/components/status/StatusBanner.tsx` | Store-connected banner | default `StatusBanner` |
| `frontend/src/components/status/StatusBannerView.tsx` | Presentational cards | `StatusBannerView` |
| `frontend/src/App.tsx` | Mounts the hook and the banner outside the routes | — |
| `frontend/vite.config.ts` | Dev proxy for `/_status` (dev backend answers 404 → quiet) | — |
| `frontend/vitest.config.ts` | Also runs `*.test.tsx` (banner render tests via `react-dom/server`) | — |

Tests: `lib/health/{machine,classify,channel,interceptors,monitor}.test.ts`,
`lib/alerts/{parse,poller}.test.ts`, `lib/statusBanner/items.test.ts`, `stores/statusStore.test.ts`,
`components/status/StatusBannerView.test.tsx`.

## Invariants and constraints

- Nothing about health is shown before two consecutive failures, at least one of them a probe (or an
  `offline` event). Passive failures alone never confirm.
- `failing.since` is set when the incident is confirmed and kept while it lasts; it is the incident identity.
- The health interceptor never changes a response or error seen by `api/client.ts` callers and runs after
  the 401 interceptor.
- Probes never go through `api/client.ts`, so they cannot trigger the 401 redirect or feed themselves back
  as passive observations.
- At most one health probe and one alerts fetch are in flight; no polling while the tab is hidden.
- A missing alerts endpoint (404 or HTML 200) is never shown to the user; an unreachable Alertmanager keeps
  the last known alerts with a stale note and shows nothing when none were known.
- The banner overlays the bottom centre with `pointer-events: none` outside its cards, so editing is not
  blocked.
- Outages and critical alerts use `role="alert"`; warnings, other alerts and "Back online" use
  `role="status"`. Each card has a labelled dismiss button.
