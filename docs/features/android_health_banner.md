# Android Health Banner

An app-wide banner in the Android client that says when the backend is down or unreachable and
shows the cluster's firing Alertmanager alerts, while the app is open. It exists because the
backend was down for about two days without anyone noticing. The web client gets the same
behaviour on a sibling branch (`feat/web-health-banner`): the same thresholds and timings, the same
state machine, alert parsing rules and incident keys.

## Scope

- Backend health: polling `GET /api/health` while the activity is visible, plus passive health
  signals from every regular `ApiClient` call (502/503/504 and IO errors), without changing any
  caller.
- A pure, debounced health state machine with backoff and a short "back online" notice.
- Cluster alerts: polling `GET /_status/alerts` (served by the frontend nginx, see
  `cluster_alerts_endpoint.md` on `feat/status-alerts-endpoint`) and parsing it defensively into
  typed alerts.
- The banner above every screen: backend item first, alerts critical first, per-incident dismiss,
  expandable descriptions, live-region announcements.
- Build configuration for where the alerts endpoint lives (`-PstatusAlertsUrl`).

## Non-scope

- Notifications or polling while the app is in the background (no WorkManager, no push).
- Retrying or queueing failed saves. The banner only says changes may not be saving; screens keep
  their own error handling.
- Alert rules, Alertmanager routing, and the `/_status/alerts` endpoint itself (other branches).
- Persisting dismissals across process death: they last for the activity's ViewModel lifetime.

## Where the endpoints live (base URL resolution)

`BuildConfig.API_BASE_URL` (`-PapiBaseUrl`) is the base every API path is appended to
(`/api/...`). It can be either:

| API base | Example | `/api/*` | `/_status/alerts` |
|---|---|---|---|
| Frontend nginx origin | `http://10.1.1.69:32443` (production NodePort) | proxied to the backend; nginx answers 502/504 while the backend is dead | served by nginx from Alertmanager |
| Backend directly | `http://10.0.2.2:8000` (dev default), `http://100.64.0.10:8000` (release variable today) | the backend itself; connection refused or timeout while it is dead | does not exist (FastAPI answers 404) |

Today both the dev default and the release `ANDROID_API_BASE_URL` point at the backend directly,
so alerts need the explicit setting. `-PstatusAlertsUrl` (`BuildConfig.STATUS_ALERTS_URL`,
release workflow variable `ANDROID_STATUS_ALERTS_URL`) decides, through
`AlertsEndpoint.resolve(raw, apiBase)`:

| `statusAlertsUrl` | Alerts URL |
|---|---|
| blank (default) | `<API_BASE_URL>/_status/alerts`: right for an nginx-origin base; for a backend-direct base the backend answers 404, which is `Absent` and stays quiet (checked again every 10 minutes) |
| `http(s)://...` | used as given, e.g. `http://10.1.1.69:32443/_status/alerts` for a backend-direct API base |
| `none` | alerts off, never fetched |
| anything else | the Gradle build fails; `AppContainer` also refuses it at start |

The client never guesses an nginx origin from a backend URL. The health probe always goes to
`<API_BASE_URL>/api/health`, which both kinds of base serve.

Both status calls use their own OkHttp client derived from the app's (10 s call timeout) and go
around `ApiClient`, so probes are not also counted as passive signals and the alerts URL can be
on another origin. Neither sends the bearer token.

## Types

`:core:model` `dev.nymph.lyrics.model.status`:

- `HealthSignal` = `Up` | `Failed(Outage)`.
- `Outage` = `BackendDown(DownCause)` | `Unreachable(UnreachableReason)`;
  `DownCause` = `Http(status)` | `InvalidBody`; `UnreachableReason` = `OFFLINE` | `NETWORK` | `TIMEOUT`.
- `ClusterAlert(key: IncidentKey, name, severity, summary?, description?, startsAt: Instant)`: only
  firing alerts with a non-blank name and a valid start exist (`init` rejects a blank name).
- `AlertSeverity` = `Critical` | `Warning` | `Other(label?)` with `rank` and `id`.
- `IncidentKey`: `<fingerprint or sorted labels>@<startsAt epoch ms>#<severity id>` (same as web).
- `AlertsFetch` = `Alerts(alerts, malformed)` | `Unavailable(status)` | `Absent` | `Disabled` |
  `Failed(AlertsFetchError)`; `AlertsFetchError` = `Http(status)` | `Network(IOException)` |
  `Timeout(IOException)`.

`:core:logic` `dev.nymph.lyrics.logic.status`:

- `HealthState` = `Unknown` | `Healthy` | `Suspect(outage, failures)` |
  `Down(outage, failures, since)` | `BackOnline(at, downSince)`. `Suspect` requires
  `1 ≤ failures < FAILURE_THRESHOLD`, `Down` requires `failures ≥ FAILURE_THRESHOLD`.
- `HealthEvent` = `Observed(signal, source: PROBE | PASSIVE, now)` | `ConnectivityLost(now)` |
  `Settle(at)`.
- `AlertsState` = `Idle` | `Absent` | `Known(alerts, fetchedAt)` | `Stale(lastKnown, reason: UNAVAILABLE | FAILED)`.

## Health state machine

| State | Up (probe or passive) | Probe failure | Passive failure | ConnectivityLost | Settle(at) |
|---|---|---|---|---|---|
| `Unknown`, `Healthy` | `Healthy` | `Suspect(1)` | `Suspect(1)` | `Down(OFFLINE, 2, now)` | no-op |
| `Suspect(n)` | `Healthy` (no "back online") | n+1 ≥ 2: `Down(outage, n+1, since = now)` | unchanged (a burst of parallel calls is one observation) | `Down(OFFLINE, 2, now)` | no-op |
| `Down` | `BackOnline(at = now, downSince = since)` | failures+1, latest outage, `since` kept | unchanged | outage = `OFFLINE`, `since` kept | no-op |
| `BackOnline(at)` | unchanged | `Suspect(1)` (a new debounce) | `Suspect(1)` | `Down(OFFLINE, 2, now)` | `Healthy` if `at` matches |

A transition into `Suspect` from a passive failure wakes the probe loop, so the confirming probe
runs at once instead of after up to 15 s.

`HealthPolicy` (identical to the web's `machine.ts`): `FAILURE_THRESHOLD = 2`, probe every
`15 s` while unknown / healthy / back online, `3 s` while suspect, while down `5 s` doubling per
further failure up to `60 s`, "back online" shown for `5 s`.

Signal classification:

| Source | Up | BackendDown | Unreachable |
|---|---|---|---|
| `/api/health` probe (`healthSignalFromProbe`) | 200 `{"status":"ok"}` | any other status (`Http`), 200 with another body (`InvalidBody`) | IO error (`InterruptedIOException` = `TIMEOUT`, else `NETWORK`) |
| Regular call (`healthSignalOf`) | 2xx, 4xx, 500, 501, undecodable 2xx | 502 / 503 / 504 | `ApiError.Network` (same timeout rule) |
| `ConnectivityManager` default network lost with no other | | | `OFFLINE`, confirmed at once |

## Flow

```
MainActivity.onCreate
  lifecycleScope + repeatOnLifecycle(STARTED)
    -> container.status.runInForeground(connectivityChanges(context))
         HealthMonitor.run()   probe now; then wait nextProbeDelayMillis() or a wake
         AlertsMonitor.run()   fetch now; then 30 s (Absent: 10 min; Disabled: stop) or a wake
         connectivity: Available -> both wakeUp(); Lost -> health.connectivityLost()
  leaving STARTED cancels all three; the next start probes and fetches at once

Screen -> repository -> ApiClient.call
  -> result unchanged for the caller
  -> observer.onCallOutcome(healthSignalOf(result))   (not for cancelled calls)
       = HealthMonitor: reduce(Observed(signal, PASSIVE)); entering Suspect wakes the probe loop

HealthMonitor.state (StateFlow<HealthState>)   AlertsMonitor.state (StateFlow<AlertsState>)
  \                                            /     applyAlertsFetch: list -> Known (sorted),
   StatusBannerViewModel: combine + dismissed + expanded      Absent/Disabled -> Absent,
     -> buildBannerModel (backend item, then alerts)          5xx -> Stale(UNAVAILABLE),
     -> visibleItems(dismissed), pruneDismissed               failure -> Stale(FAILED), keeps last list
     -> StatusBannerUiState(rows, alertsStale)
  -> AppRoot: Column { StatusBanner; Box(weight 1) { AppNavHost } }
```

Alert parsing (`parseAlerts`, `interpretAlertsResponse`), same rules as the web's `parse.ts`:

- 200 + JSON array: each entry must be an object with a `labels` object, a non-blank string
  `labels.alertname`, an RFC 3339 `startsAt`, and a `status` object with a string `state`;
  anything else is counted as malformed and dropped. `state == "active"` is kept; other states
  (`suppressed`, `unprocessed`) are skipped without counting. Severity, summary and description
  fall back to `Other(null)` / null. Unknown fields are ignored.
- 200 that is not an array (HTML SPA fallback, an object, empty) and 404: `Absent`.
- Any 5xx (503 `alertmanager_unavailable`, proxy errors): `Unavailable(status)`.
- Any other status: `Failed(Http(status))`; no answer: `Failed(Network | Timeout)`.

## Banner

- Backend item first: down (`DANGER`, assertive): "Server is down" / "The server answered HTTP
  502. Changes may not be saving." / "Since 14:03"; invalid body: "Server is not responding
  correctly"; offline: "You're offline"; unreachable / timeout: "Can't reach the server". Back
  online (`SUCCESS`, polite): "The server is reachable again. Check that recent changes were saved."
- Then alerts in `displayOrder`: severity rank, newest start first, then name. Title is the
  summary (detail: the alert name) or the name; label "Critical" / "Warning" / the other label
  capitalised / "Alert"; tone danger / warning / info; critical announces assertively, others
  politely. Descriptions expand behind a chevron.
- While alerts are `Stale` the last known ones stay, with a note that alert status may be out of
  date.
- Every item has a dismiss button. Keys: `backend:<down|unreachable|offline>@<since>`,
  `backend:recovered@<at>`, `alert:<IncidentKey>`; a new outage or re-fired alert has a new key and
  shows again. Dismissals of keys no longer present are pruned.
- Accessibility: each item's text is merged into one node with a content description and a live
  region (assertive or polite); the chevron has a state description and both buttons say which
  item they act on.
- Layout: the banner takes the status bar inset and the content below consumes it, so the
  screens' top bars do not pad twice. It is capped at 240 dp and scrolls; it never overlays or
  blocks the screen.

## Files

Paths under `android/`.

| File | Role / key exports |
|---|---|
| `core/model/.../model/status/Health.kt` | `HealthSignal`, `Outage`, `DownCause`, `UnreachableReason` |
| `core/model/.../model/status/ClusterAlert.kt` | `ClusterAlert`, `AlertSeverity`, `IncidentKey` |
| `core/model/.../model/status/AlertsFetch.kt` | `AlertsFetch`, `AlertsFetchError` |
| `core/data/.../data/ApiCallObserver.kt` | `ApiCallObserver` (`None`), `healthSignalOf`, `unreachableReasonOf`, `GATEWAY_STATUSES` |
| `core/data/.../data/ApiClient.kt` | `observer` constructor parameter; `report` after every completed call; `Call.await` now `internal` (shared with the status repository) |
| `core/data/.../data/status/AlertsJson.kt` | `parseAlerts` -> `AlertsParse` (`Parsed`, `NotAnArray`), `interpretAlertsResponse` |
| `core/data/.../data/status/StatusRepository.kt` | `AlertsEndpoint` (`Disabled`, `At`, `resolve`), `StatusRepository`, `HttpStatusRepository`, `healthSignalFromProbe` |
| `core/logic/.../logic/status/HealthMachine.kt` | `HealthPolicy`, `ObservationSource`, `HealthState`, `HealthEvent`, `reduce`, `nextProbeDelayMillis`, `becameSuspectFrom` |
| `core/logic/.../logic/status/Alerts.kt` | `AlertsState`, `StaleReason`, `displayOrder`, `sortAlerts`, `shown`, `applyAlertsFetch` |
| `app/.../app/status/HealthMonitor.kt` | `HealthMonitor` (`state`, `run`, `wakeUp`, `connectivityLost`, implements `ApiCallObserver`) |
| `app/.../app/status/AlertsMonitor.kt` | `AlertsMonitor` (`state`, `run`, `wakeUp`, `POLL_MILLIS`, `ABSENT_POLL_MILLIS`) |
| `app/.../app/status/StatusMonitors.kt` | `StatusMonitors` (`health`, `alerts`, `runInForeground`) |
| `app/.../app/status/Connectivity.kt` | `ConnectivityChange`, `connectivityChanges(context)` (default network callback flow) |
| `app/.../app/status/BannerModel.kt` | `BannerKey`, `BannerItem`, `BannerModel`, `BannerFormat`, `buildBannerModel`, `visibleItems`, `pruneDismissed`, `severityLabel` |
| `app/.../app/status/StatusBannerViewModel.kt` | `StatusBannerViewModel` (`state`, `dismiss`, `toggleExpanded`), `StatusBannerUiState`, `BannerRow` |
| `app/.../app/status/StatusBanner.kt` | `StatusBanner` composable |
| `app/.../app/nav/AppRoot.kt` | `AppRoot`: banner above `AppNavHost`, status-bar inset hand-off |
| `app/.../app/MainActivity.kt` | starts `runInForeground` under `repeatOnLifecycle(STARTED)` |
| `app/.../app/di/AppContainer.kt` | builds `HttpStatusRepository`, `StatusMonitors` (`status`), passes `status.health` as the `ApiClient` observer |
| `app/build.gradle.kts` | `-PstatusAlertsUrl` -> `BuildConfig.STATUS_ALERTS_URL` (validated) |
| `app/src/main/AndroidManifest.xml` | `ACCESS_NETWORK_STATE` for the connectivity callback |
| `.github/workflows/android-release.yml` | optional `ANDROID_STATUS_ALERTS_URL` variable -> `-PstatusAlertsUrl` |

Tests: `core/data` `ApiClientObserverTest`, `status/AlertsJsonTest`, `status/HttpStatusRepositoryTest`
(MockWebServer); `core/logic` `status/HealthMachineTest`, `status/AlertsTest`; `app`
`status/HealthMonitorTest`, `status/AlertsMonitorTest` (virtual time via `runTest`),
`status/BannerModelTest`, `status/StatusBannerViewModelTest`.

## Invariants and constraints

- No exception escapes the status path: probes and fetches return typed results; JSON errors are
  caught as `SerializationException` only; IO errors as `IOException` only. Cancellation propagates.
- Regular callers see exactly the results they saw before; the observer only reads them. A
  cancelled call reports nothing.
- An outage is shown only after `FAILURE_THRESHOLD` consecutive failures and at least one of them
  from a probe, or at once when the system reports no network. Passive failures alone never
  confirm.
- `Down.since` is fixed for the whole incident, so a dismissal lasts exactly that incident.
- Polling happens only between `ON_START` and `ON_STOP`; nothing runs in the background.
- `/_status/alerts` 404 or a non-array 200 is silent; 503 never clears alerts already shown.
- Alerts are fetched only from the configured URL; the client never derives an nginx origin from a
  backend-direct base.
- The banner never blocks input: it sits above the content, not over it.
- Thresholds, timings, parsing rules and incident keys match the web client; change both together.
