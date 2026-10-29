# Cluster Alerts Endpoint

`GET /_status/alerts` on the frontend origin returns the Alertmanager alerts
that are currently firing for the release's namespace. The production
frontend nginx answers it by proxying to the cluster's Alertmanager, so it
keeps working while the backend is down. The web SPA and the Android app
poll it to show an outage banner (sibling features; this doc covers only the
server side).

## Scope

- One read-only nginx endpoint, `/_status/alerts`, in
  `frontend/nginx/default.conf.template`.
- Container-start validation of its settings and construction of the single
  upstream URL (`frontend/nginx/entrypoint/16-cluster-alerts.envsh`).
- Chart values `frontend.clusterAlerts.*` and the homelab setting.
- A docker-based behavior test suite against a stub Alertmanager.

## Non-scope

- Client polling and banner UI (web and Android branches).
- Alert rules, routing, silences: owned by the cluster's
  kube-prometheus-stack.
- Authentication: the endpoint is public, like the SPA itself. It only
  exposes alert names, labels and annotations for this namespace.
- Any other Alertmanager API (status, silences, receivers). Never proxied.

## Contract

Clients depend on this exactly.

| Situation | Status | Body |
|-----------|--------|------|
| Configured, Alertmanager answered 2xx | 200 | Alertmanager v2 `GET /api/v2/alerts` JSON array, byte for byte |
| Not configured (`ALERTMANAGER_URL` empty) | 200 | `[]` |
| Alertmanager unreachable, unresolvable, timed out, non-2xx, or settings invalid | 503 | `{"error":"alertmanager_unavailable"}` |
| Method other than GET/HEAD | 405 | `{"error":"method_not_allowed"}`, `Allow: GET, HEAD` |

All responses carry `Content-Type: application/json`,
`Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. HEAD gets
the same status and headers without a body. The client's query string is
ignored entirely.

Upstream filtering: active, unsilenced, uninhibited alerts with label
`namespace="<namespace>"`. Fields clients use: `labels.alertname`,
`labels.severity`, `annotations.summary`, `annotations.description`,
`startsAt`, `status.state`.

Paths under `/_status/` other than exactly `/_status/alerts` are 404.

## Flow

### Container start

1. The nginx image entrypoint (`/docker-entrypoint.sh`) runs
   `/docker-entrypoint.d/*` in version order. `16-cluster-alerts.envsh` is
   sourced (it must be executable; `Dockerfile.prod` copies it with
   `--chmod=0755`) before `20-envsubst-on-templates.sh`.
2. The script reads `ALERTMANAGER_URL`, `ALERTS_NAMESPACE`, `ALERTS_RESOLVER`
   and exports three values:
   - `ALERTS_RESOLVER_ADDRS`: valid addresses from `ALERTS_RESOLVER`
     (IPv4 or `[IPv6]`, optional `:port`), else the `nameserver` lines of
     `/etc/resolv.conf` (kube-dns in a pod, `10.152.183.10` on the homelab
     cluster), else `127.0.0.1` with a warning. Invalid tokens are dropped
     with a warning.
   - `ALERTS_STATE`: `disabled` when `ALERTMANAGER_URL` is empty; `enabled`
     when it is exactly `http(s)://host[:port][/]` and the namespace is a
     valid Kubernetes name; otherwise `misconfigured` (logged at
     `level=error`).
   - `ALERTS_UPSTREAM` (only when enabled):
     `<scheme>://<host>[:<port>]/api/v2/alerts?active=true&silenced=false&inhibited=false&filter=namespace%3D%22<ns>%22`.
     A host ending in `.svc` gets the cluster domain appended, taken from the
     `svc.<domain>` entry of the resolv.conf `search` line, because nginx's
     resolver does not apply search domains
     (`kps-alertmanager.monitoring.svc` becomes
     `kps-alertmanager.monitoring.svc.cluster.local`).
   It logs one `level=info` line with the state, namespace, upstream and
   resolver, and always returns 0.
3. envsubst renders the template. Only validated strings reach the config,
   so no setting can break its syntax.

### Request

```
client -> nginx :8080
  location = /_status/alerts
    method not GET/HEAD           -> 405
    $alerts_state = disabled      -> 200 []
    $alerts_state != enabled      -> 503
    proxy_pass $alerts_upstream   (variable: resolved per request via `resolver`,
                                   cached 30s, resolver_timeout 2s)
      proxy_method GET, no client headers or body, Accept: application/json
      connect 2s / send 3s / read 5s
      2xx                         -> body and Content-Type passed through
      3xx/4xx/5xx, refused,
      DNS failure, timeout        -> error_page -> @alerts_unavailable -> 503
  location ^~ /_status/           -> 404
```

Because `proxy_pass` uses a variable that already contains a URI, nginx sends
exactly that URI and does not append the client's path or arguments.
Upstream `X-Accel-*`, `Set-Cookie`, `Cache-Control`, `Expires` and `Vary` are
ignored or hidden.

## Files

| File | Role |
|------|------|
| `frontend/nginx/default.conf.template` | `resolver` directive, `/_status/` reserve, `= /_status/alerts` location, `@alerts_unavailable`. `/healthz`, `/api/`, `/assets/` and the SPA fallback are unchanged. |
| `frontend/nginx/entrypoint/16-cluster-alerts.envsh` | Validates inputs; exports `ALERTS_STATE`, `ALERTS_UPSTREAM`, `ALERTS_RESOLVER_ADDRS`. POSIX sh (busybox), safe under the entrypoint's `set -eu`. `ALERTS_RESOLV_CONF` overrides the resolv.conf path (tests only). |
| `frontend/Dockerfile.prod` | Copies the script to `/docker-entrypoint.d/`; `ENV ALERTMANAGER_URL="" ALERTS_NAMESPACE="" ALERTS_RESOLVER=""`. |
| `frontend/.dockerignore` | Excludes `nginx/tests` from the build context. |
| `deploy/helm/lyrics-canvas/values.yaml` | `frontend.clusterAlerts.alertmanagerUrl` (default empty), `namespace` (default empty = release namespace), `resolver` (default empty = resolv.conf). |
| `deploy/helm/lyrics-canvas/values.schema.json` | Patterns: URL is `http(s)://host[:port][/]` only, namespace is a DNS label, resolver is a list of IP[:port]. |
| `deploy/helm/lyrics-canvas/templates/frontend/deployment.yaml` | Env `ALERTMANAGER_URL`, `ALERTS_NAMESPACE` (`namespace` or `.Release.Namespace`), `ALERTS_RESOLVER`. |
| `deploy/helm/environments/homelab.yaml` | `alertmanagerUrl: http://kps-alertmanager.monitoring.svc:9093`. |
| `deploy/helm/tests/test_chart.py` | `FrontendClusterAlertsTest`, homelab assertion, schema rejections. |
| `frontend/nginx/tests/stub_alertmanager.py` | Stub Alertmanager: records every request; `/api/v2/status` returns a canary; control port 9094 switches modes (ok, error500/400/404, redirect, slow). |
| `frontend/nginx/tests/test_alerts_endpoint.py` | Docker behavior suite (below). |

## Running the behavior tests

The suite runs the image named in the last `FROM` of `Dockerfile.prod`
(`nginxinc/nginx-unprivileged:1.27-alpine`) with the template and script
bind-mounted where the Dockerfile copies them, plus a `python:3.12-alpine`
stub, on a throwaway docker network. It needs a docker daemon that can mount
the checkout (snap docker only mounts under `$HOME`) and whose container IPs
are reachable from the host. On the homelab, from a laptop checkout:

```bash
ssh node0 'mkdir -p ~/lyrics-nginx-test/frontend'
rsync -a frontend/nginx frontend/Dockerfile.prod node0:lyrics-nginx-test/frontend/
ssh node0 'cd ~/lyrics-nginx-test && python3 frontend/nginx/tests/test_alerts_endpoint.py; rm -rf ~/lyrics-nginx-test'
```

Or directly where docker works: `python3 frontend/nginx/tests/test_alerts_endpoint.py`.

It covers: pass-through body and headers; the upstream filter; HEAD; client
query strings and headers (`Authorization`, `Cookie`, `Host`, ...) not
forwarded; 405 for other methods; traversal and lookalike paths
(`/_status/alerts/../status`, encoded `%2F..`, `/_status/alertsX`,
`/_status/alerts%3F...`, ...) never reaching Alertmanager; encoded forms of
the endpoint still using the fixed URL; upstream 5xx/4xx/redirect/timeout,
refused connection, unresolvable host and dead resolver all giving 503 while
`/`, `/healthz` keep working; unconfigured giving `[]`; invalid URL or
namespace giving 503 with no upstream call; `.svc` short names; resolver
overrides. After every case it asserts the stub only ever saw the one fixed
target.

## Deploying

Both halves ship together: the frontend image must be rebuilt from
`frontend/Dockerfile.prod` (template and entrypoint hook are baked in) and the
release upgraded so the pod gets `ALERTMANAGER_URL`. An old image with the new
chart ignores the env vars (the endpoint falls through to the SPA); a new image
with the old chart answers `[]`. Check the pod log line
`16-cluster-alerts.envsh: level=info ... state=enabled` after rollout.

The frontend pod needs network access to Alertmanager. With a
default-deny NetworkPolicy in the `monitoring` namespace, allow ingress from
the release namespace to port 9093.

`https://` upstreams are accepted, with SNI, but the certificate is not
verified (`proxy_ssl_verify` is off); use it only over a trusted network.

## Invariants and constraints

- **One upstream URL.** nginx may request nothing from Alertmanager except
  `ALERTS_UPSTREAM`. Alertmanager is unauthenticated and endpoints such as
  `/api/v2/status` return its full config, including notification
  credentials. Never add `$uri`, `$args`, `$request_uri` or client headers
  to this proxy, and keep `proxy_pass_request_headers off`.
- **Validated inputs only.** `ALERTMANAGER_URL` must stay `scheme://host[:port]`
  (no path, query, userinfo); the namespace must stay a Kubernetes name so it
  cannot widen the filter. Enforced by both the chart schema and the script;
  the script never trusts the schema.
- **No namespace, no proxy.** A URL with an empty or invalid namespace is
  `misconfigured` (503), never an unfiltered query.
- **nginx must start regardless.** The Alertmanager host is only resolved at
  request time through a variable `proxy_pass`; the script cannot fail. A
  missing or broken Alertmanager affects only this endpoint. Do not turn it
  into a static `proxy_pass` or an `upstream` block with a hostname.
- **503 means "can't tell".** Clients rely on 200 `[]` meaning "nothing is
  firing" and 503 meaning "unknown"; every failure path must end in
  `@alerts_unavailable`, never in a 200.
- **No caching.** `Cache-Control: no-store` on every answer; upstream caching
  headers are hidden.
- **Short timeouts.** DNS 2s, connect 2s, read 5s, so a hung Alertmanager
  answers clients within seconds.
- The template's other locations (`/healthz`, `/api/`, `/assets/`, `/`) are
  unchanged, and `/api/` still uses the static `${BACKEND_URL}` proxy.
