# Homelab TLS (ingress + local CA)

Browsers only allow microphone access (voice takes) in a secure context, so
the web app needs HTTPS on the home microk8s cluster. There is no public DNS
name or ACME setup, so the cluster serves a certificate from a small local CA
that each device trusts once:

- `deploy/homelab/tls/issue-cert.sh` creates the CA (once, outside git),
  issues and renews the server certificate, and applies it as a
  `kubernetes.io/tls` Secret.
- The chart's Ingress (enabled in `homelab.yaml`) routes through the microk8s
  `ingress` addon (ingress-nginx, class `public`, hostPort 80/443 on every
  node).
- ingress-nginx serves that Secret as its default certificate, which is what
  IP URLs get.

After setup, the app is at `https://10.1.1.69/` (LAN, node0),
`https://10.1.1.68/` (LAN, node1) and `https://100.64.0.4/` (tailnet, node1).
The plain-HTTP NodePort `http://10.1.1.69:32443` keeps working for the Android
app and anything else that does not need a secure context.

## Scope

- One-time local CA in `~/.config/lyrics-canvas/tls/` (directory mode 700,
  `ca.key` mode 600). The script never overwrites it without `--force`.
- Server certificate with SANs `IP:10.1.1.69`, `IP:10.1.1.68`,
  `IP:100.64.0.4`, `DNS:lyrics.s8.gay`. It is valid for 397 days (Chrome's
  limit), has the `serverAuth` EKU, and is reissued automatically within 30
  days of expiry, when its SANs change, or when it no longer matches the CA or
  its key.
- The `lyrics-canvas/lyrics-canvas-tls` Secret, applied over
  `ssh node0 microk8s kubectl apply ... -f -` with the manifest on stdin.
- A chart Ingress with a host-less (catch-all) rule, the TLS Secret, a 26 MB
  body limit, 180 s proxy timeouts and an HTTP to HTTPS redirect.
- A 26 MB `client_max_body_size` on the frontend nginx `/api/` location. The
  NodePort path goes through it, and so does the Ingress when
  `routeApiToBackend` is off.
- Instructions for trusting the CA on Linux Chrome/Firefox and on Android.

## Non-scope

- Public certificates (ACME / Let's Encrypt), cert-manager, automatic
  renewal. Re-run the script about once a year (it says when a renewal
  happens).
- Changing cluster-wide ingress configuration. Pointing the controller's
  default certificate at the Secret is a documented one-time command the user
  runs (below).
- The out-of-chart `tailnet-lyrics` Ingress (`lyrics.k8s.ts.s8.gay`, plain
  HTTP). It is untouched and keeps ingress-nginx's 1 MB default body limit, so
  voice uploads do not work through that host.
- Moving the Android app to HTTPS (see [Android app over HTTPS later](#android-app-over-https-later)).
- Routing any internal component, in particular the speech-to-text service,
  through the Ingress or nginx. Only the frontend and backend Services are
  ever routed, and tests enforce this.
- IPv6 SANs.

## How ingress-nginx picks the certificate for IP URLs

Browsers send no SNI for IP-literal URLs (RFC 6066 forbids IP addresses in
SNI). ingress-nginx picks the certificate in `certificate.lua`: with no SNI
hostname it uses the certificate of the catch-all server `_`, and an unknown
SNI name falls back to that same certificate. The catch-all server's
certificate is the controller's default certificate: `--default-ssl-certificate`
if set, otherwise the built-in "Kubernetes Ingress Controller Fake
Certificate". This was checked read-only on the cluster, where
`openssl s_client -connect 10.1.1.69:443` returns the fake certificate today.

An Ingress rule cannot replace the catch-all server's certificate. Ingress
rule hosts cannot be IP addresses, and a host-less rule with a `tls` entry
does not override the default certificate, because the `_` server already
has one. So the design is:

- **Routing:** the chart renders a rule without `host` when `ingress.hosts`
  is empty. ingress-nginx merges it into the `_` server, which matches any
  `Host` header, including `10.1.1.69` and `100.64.0.4`.
- **Certificate:** start the controller with
  `--default-ssl-certificate=lyrics-canvas/lyrics-canvas-tls`. Then every
  request without SNI, and every request with a server name no other Ingress
  claims (for example `lyrics.s8.gay`), gets our certificate.
- The Ingress still lists `tls: [{secretName: lyrics-canvas-tls}]`. That ties
  the chart to the Secret name (a test checks it against the script) and
  makes the HTTP to HTTPS redirect apply to the rule.

## Setup (one time)

Order matters: the Secret must exist before the controller is pointed at it,
and the `lyrics-canvas` namespace must already exist (it does once the app has
been deployed).

1. **Create the CA, issue the certificate, apply the Secret** (on the laptop):

   ```bash
   deploy/homelab/tls/issue-cert.sh --dry-run   # shows what it would do
   deploy/homelab/tls/issue-cert.sh
   ```

2. **Make the certificate the ingress default.** Do this once per cluster,
   from any node. `microk8s enable` skips an addon that is already enabled,
   so disable it first. This briefly takes down all Ingresses, including the
   tailnet ones. The `ingress` namespace is recreated, and nothing in it is
   customised today.

   ```bash
   ssh node0 microk8s disable ingress
   ssh node0 microk8s enable ingress:default-ssl-certificate=lyrics-canvas/lyrics-canvas-tls
   ```

   No-downtime alternative: patch the DaemonSet's placeholder argument (index
   5, `' '`; the `test` op makes the patch fail safely if the layout ever
   differs). A later `microk8s enable ingress` without the argument reverts
   it, so prefer the command above when downtime is acceptable.

   ```bash
   ssh node0 "microk8s kubectl -n ingress patch daemonset nginx-ingress-microk8s-controller --type=json -p '[{\"op\":\"test\",\"path\":\"/spec/template/spec/containers/0/args/5\",\"value\":\" \"},{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/args/5\",\"value\":\"--default-ssl-certificate=lyrics-canvas/lyrics-canvas-tls\"}]'"
   ```

   Check it:

   ```bash
   ssh node0 "microk8s kubectl -n ingress get ds nginx-ingress-microk8s-controller -o jsonpath='{.spec.template.spec.containers[0].args}'"
   ```

3. **Deploy** with `deploy/homelab/deploy.sh`. `homelab.yaml` enables the
   Ingress.

4. **Verify** from the laptop:

   ```bash
   curl --cacert ~/.config/lyrics-canvas/tls/ca.crt https://10.1.1.69/healthz      # ok
   curl --cacert ~/.config/lyrics-canvas/tls/ca.crt https://100.64.0.4/healthz     # ok (tailnet)
   openssl s_client -connect 10.1.1.69:443 </dev/null 2>/dev/null | openssl x509 -noout -issuer
   # issuer=O=Lyrics Canvas, CN=Lyrics Canvas Homelab CA
   curl -sI http://10.1.1.69/ | head -1                                              # 308 redirect
   ```

5. **Trust the CA on each device** ([below](#installing-the-ca)).

### Renewal

Re-run `deploy/homelab/tls/issue-cert.sh`. It keeps the CA, reissues the
server certificate only when needed (or with `--renew`), and reapplies the
Secret every time. ingress-nginx watches the Secret and reloads the
certificate on its own. If it does not, run
`ssh node0 microk8s kubectl -n ingress rollout restart ds/nginx-ingress-microk8s-controller`.
Devices need no changes because they trust the CA, not the server
certificate.

`--force` replaces the CA. The old files are kept as `*.replaced-<UTC>`.
Every device then has to trust the new CA, so use it only if the CA key may
have leaked or the CA has expired (10 years).

## Request flow

```
browser https://10.1.1.69/ (no SNI)
  -> node0:443 hostPort -> ingress-nginx (class public)
       TLS: certificate.lua -> no SNI -> "_" -> --default-ssl-certificate
            = Secret lyrics-canvas/lyrics-canvas-tls (signed by the local CA)
       server "_" (host-less rule of Ingress lyrics-lyrics-canvas)
         /api/* -> backend Service :8000   (proxy-body-size 26m, timeouts 180 s)
         /*     -> frontend Service :80 -> nginx :8080 (SPA, /_status/alerts)
browser http://10.1.1.69/      -> 308 to https://10.1.1.69/ (force-ssl-redirect)
android http://10.1.1.69:32443 -> frontend NodePort -> nginx :8080
                                    /api/ (client_max_body_size 26m, read timeout 120 s) -> backend
```

## The script

`deploy/homelab/tls/issue-cert.sh [--dry-run] [--renew] [--force] [--no-apply] [-h]`

| Flag | Effect |
|------|--------|
| `--dry-run` | Logs what would be created, issued and applied. Writes nothing and makes no ssh call. |
| `--renew` | Issues a new server key and certificate even if the current one is valid. |
| `--force` | Replaces the CA (backs up the old pair) and therefore the server certificate. |
| `--no-apply` | Local files only; no cluster access. |

| Environment | Default |
|-------------|---------|
| `LYRICS_TLS_DIR` | `~/.config/lyrics-canvas/tls` (must be absolute) |
| `LYRICS_TLS_SANS` | `IP:10.1.1.69,IP:10.1.1.68,IP:100.64.0.4,DNS:lyrics.s8.gay` (`IP:<ipv4>` / `DNS:<name>` only) |
| `LYRICS_TLS_DAYS` | `397` (1-397) |
| `LYRICS_TLS_CA_DAYS` | `3650` for a new CA (1-7300) |
| `LYRICS_TLS_RENEW_BEFORE_DAYS` | `30` |
| `LYRICS_KUBE_HOST` | `node0` (ssh destination with `microk8s kubectl`) |
| `LYRICS_NAMESPACE` | `lyrics-canvas` |
| `LYRICS_TLS_SECRET` | `lyrics-canvas-tls` |

| Exit | Meaning |
|------|---------|
| 0 | OK |
| 2 | Bad flag or configuration value |
| 3 | Missing tool (`openssl`, `base64`, `mktemp`, `ssh`) |
| 4 | CA state problem: only one of `ca.key`/`ca.crt` present, cert not a CA or expired, key/cert mismatch |
| 5 | openssl failed creating the CA or issuing the server certificate (previous files untouched) |
| 6 | ssh to the kube host failed (exit 255); local files are kept |
| 7 | `kubectl apply` failed (for example, the namespace is missing) |

### Control flow

1. `parse_args`, `validate_config` (SANs, day ranges, DNS-1123 names,
   absolute dir), `check_dependencies` (openssl first; ssh only when
   applying). Then `umask 077` and an EXIT trap that deletes every temporary
   file.
2. `ensure_dir`: creates the directory and forces mode 700.
3. `ensure_ca`:
   - `--force` moves the old pair aside and creates a new CA.
   - If both files exist, they are checked: a loose key mode is fixed to 600
     with a warning, the cert must be `CA:TRUE` and not expired, and its
     public key must match `ca.key`. A warning is logged if the CA expires
     before a new server certificate would.
   - If only one file exists, the script exits 4 and touches nothing.
   - If neither exists, it creates the CA: EC P-256, SHA-256,
     `basicConstraints=critical,CA:TRUE,pathlen:0`,
     `keyUsage=critical,keyCertSign,cRLSign`, from a generated openssl config.
     It is written to temporary files in the directory, then `mv`'d into place.
4. `ensure_server`: `server_issue_reason` returns the first that applies, in
   this order: `missing`, `ca_replaced`, `renew_requested`,
   `not_signed_by_ca` (`openssl verify -purpose sslserver`), `key_mismatch`,
   `sans_changed`, `expiring` (`-checkend`). With no reason, the cert is
   kept. Otherwise `issue_server`:
   - generates a new EC P-256 key, a CSR, and a random 128-bit serial;
   - signs the CSR with `basicConstraints=critical,CA:FALSE`,
     `keyUsage=critical,digitalSignature`, `extendedKeyUsage=serverAuth`,
     `subjectAltName`, SKI/AKI, `-days $LYRICS_TLS_DAYS`;
   - verifies the result against the CA for every SAN (`-verify_ip` /
     `-verify_hostname`) before `mv`'ing key and cert into place.
5. `apply_secret` builds the Secret manifest in memory: base64 `tls.crt` /
   `tls.key`, part-of and managed-by labels, and expiry/fingerprint
   annotations. It pipes the manifest to
   `ssh -o BatchMode=yes node0 microk8s kubectl apply --server-side --field-manager=lyrics-canvas-tls --force-conflicts -f -`.
   Server-side apply avoids the `last-applied-configuration` annotation,
   which would hold a second copy of the key.
6. `report` prints the CA path and fingerprint, the server cert expiry, and
   the default-certificate commands.

Logs go to stderr, one `ts=... level=... event=... key=value` line per
event, the same format as `deploy.sh`. Key material never reaches the logs or
stdout.

## Installing the CA

The file to install is `~/.config/lyrics-canvas/tls/ca.crt`, the public CA
certificate. Never copy `ca.key` anywhere. Before trusting the CA on a
device, check that the SHA-256 fingerprint the script printed matches
`openssl x509 -in ~/.config/lyrics-canvas/tls/ca.crt -noout -fingerprint -sha256`.

### Linux: Chrome / Chromium

Chrome on Linux trusts certificates in the user's NSS database
(`~/.pki/nssdb`).

- UI: open `chrome://certificate-manager`. Under **Local certificates**, open
  **Installed by you**, then **Trusted certificates** → **Import**, and pick
  `ca.crt`. On older versions: Settings → Privacy and security → Security →
  Manage certificates → **Authorities** → Import → tick *Trust this
  certificate for identifying websites*.
- CLI (needs `libnss3-tools`):

  ```bash
  certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "Lyrics Canvas Homelab CA" \
    -i ~/.config/lyrics-canvas/tls/ca.crt
  certutil -d sql:$HOME/.pki/nssdb -L | grep "Lyrics Canvas"
  ```

Restart Chrome, then open `https://10.1.1.69/`.

### Linux: Firefox

Firefox has its own trust store per profile. Go to Settings → Privacy &
Security → Certificates → **View Certificates…** → **Authorities** →
**Import…**, pick `ca.crt`, and tick *Trust this CA to identify websites*.
This works the same for the snap and deb builds.

### Linux: command-line tools (optional)

For `curl`, Python and similar tools that use the system store:

```bash
sudo cp ~/.config/lyrics-canvas/tls/ca.crt /usr/local/share/ca-certificates/lyrics-canvas-homelab-ca.crt
sudo update-ca-certificates
```

### Android

1. Copy `ca.crt` to the phone, for example
   `adb push ~/.config/lyrics-canvas/tls/ca.crt /sdcard/Download/lyrics-canvas-ca.crt`
   or any file transfer.
2. Settings → Security & privacy → More security settings → Encryption &
   credentials → **Install a certificate** → **CA certificate** → *Install
   anyway* → pick the file. Menu names vary by vendor and version; searching
   Settings for "CA certificate" finds it. A screen lock is required.
3. Check it under Encryption & credentials → **Trusted credentials** →
   **User**.

Chrome on Android trusts user-installed CAs, so `https://10.1.1.69/` (or
`https://100.64.0.4/` on the tailnet) then works, microphone included.
Firefox for Android ignores user CAs by default. To change that, open
Settings → About Firefox, tap the logo five times, go back to Settings →
Secret Settings, and turn on **Use third party CA certificates**.

## Android app over HTTPS later

Not implemented. The app currently talks to `http://10.1.1.69:32443`
(`-PapiBaseUrl`, with `android:usesCleartextTraffic="true"`), which keeps
working. To switch it to `https://10.1.1.69`:

- Apps targeting API 24+ do not trust user-installed CAs. Add
  `res/xml/network_security_config.xml` and reference it from the manifest
  (`android:networkSecurityConfig`). Either:
  - trust user CAs too (`<base-config><trust-anchors><certificates
    src="system"/><certificates src="user"/></trust-anchors></base-config>`),
    which needs the CA installed on the device as above; or
  - bundle the public `ca.crt` as `res/raw/lyrics_ca` and trust only it, in a
    `<domain-config>` for the cluster addresses
    (`<certificates src="@raw/lyrics_ca"/>`). That needs no device setup, but
    the APK must be rebuilt if the CA is ever replaced.
- Build with `-PapiBaseUrl=https://10.1.1.69`. `/_status/alerts` is then on
  the same origin, so `-PstatusAlertsUrl` can stay blank.
- Once nothing uses cleartext, drop `usesCleartextTraffic` (or limit it with
  `cleartextTrafficPermitted` in the same config).
- Upload limits already match: Ingress `proxy-body-size` 26m, backend
  `VOICE_MAX_UPLOAD_BYTES` 25 MiB.

## Files

| File | Role |
|------|------|
| `deploy/homelab/tls/issue-cert.sh` | The script. Functions: `parse_args`, `validate_config`, `check_dependencies`, `ensure_dir`, `ensure_ca` (`create_ca`, `replace_ca`, `check_existing_ca`), `ensure_server` (`server_issue_reason`, `issue_server`, `verify_server_cert`), `apply_secret` (`secret_manifest`), `report`; `log` / `die` structured logging; `cleanup` EXIT trap for temporary files. |
| `deploy/homelab/tls/tests/test_issue_cert.py` | `unittest` suite. It runs the script with a temporary `HOME` and real openssl, then inspects the certificates with openssl (SANs, EKU, key usage, CA constraints, validity ≤ 397 days, verification for every IP and name, key/cert match, file modes). It uses the recording ssh stub from `deploy/homelab/tests/stubs/ssh` to check the exact `kubectl apply` command and the Secret manifest on stdin. It also covers reissue reasons, CA safety (incomplete, mismatched, non-CA, loose mode, `--force` backups), dry runs, ssh/kubectl failures, openssl failures (wrapper stub), `bash -n` and shellcheck. Run: `python3 deploy/homelab/tls/tests/test_issue_cert.py`. |
| `deploy/helm/lyrics-canvas/templates/ingress.yaml` | One rule per host, or a single host-less rule when `ingress.hosts` is empty. Paths come from `lyrics-canvas.ingress.paths` in `_helpers.tpl`. |
| `deploy/helm/lyrics-canvas/values.schema.json` | `ingress.tls[]` entries require a DNS-1123 `secretName`; only `secretName` and `hosts` are allowed. |
| `deploy/helm/environments/homelab.yaml` | `ingress`: enabled, class `public`, `hosts: []`, `tls: [{secretName: lyrics-canvas-tls}]`, annotations `proxy-body-size: 26m`, `proxy-read-timeout`/`proxy-send-timeout: "180"`, `force-ssl-redirect: "true"`. |
| `deploy/helm/tests/test_chart.py` | `IngressHostlessTest`, `IngressBackendsTest` (only frontend/backend Services routed), `HomelabIngressTest` (class, host-less rule, Secret name matches the script, annotations, NodePort kept), `CommittedValuesSafetyTest.test_no_key_material_in_committed_values`, and the `SchemaTest` TLS rejections. |
| `deploy/helm/tests/values/ingress-hostless.yaml` | Fixture for the host-less rendering. |
| `frontend/nginx/default.conf.template` | `client_max_body_size 26m` in `location /api/` only. |
| `frontend/nginx/tests/test_api_body_limit.py`, `stub_backend.py` | Real nginx image in front of a byte-counting stub backend. A 25.5 MiB upload and an exactly-26 MiB upload reach the backend in full; 26 MiB + 1 gets 413 on every `/api/` route; other locations keep the 1 MB default. Static checks: the only `proxy_pass` targets are `$BACKEND_URL` and the Alertmanager upstream. |
| `.gitignore` | `*.key`, `*.pem`, `*.csr`, so stray key material is never committed. |

## Invariants and constraints

- No key material in git. Committed values only name the Secret, and a test
  rejects `-----BEGIN` in any committed values file. The CA and server keys
  live only in `~/.config/lyrics-canvas/tls` and in the cluster Secret.
- `ca.key` is mode 600 in a mode-700 directory. Keys are created under
  `umask 077` in temporary files and moved into place, so a failed run never
  leaves a partial CA or replaces a working server certificate.
- The CA is never overwritten or regenerated without `--force`, and `--force`
  keeps a backup.
- The server certificate is at most 397 days, `serverAuth`, `CA:FALSE`, and
  verified against the CA for every SAN before it is installed.
- The Secret reaches the cluster only through ssh stdin. Nothing is written
  to a node's disk, and server-side apply keeps no second copy of the key in
  annotations.
- The Secret name/namespace defaults in the script must equal the
  `homelab.yaml` TLS `secretName` and the `--default-ssl-certificate`
  argument. The chart test checks the first pair.
- Ingress routes only to the app's frontend and backend Services, and nginx
  proxies only to the backend and Alertmanager. The STT service is never
  exposed.
- Upload limits: the Ingress (26m) and frontend nginx `/api/` (26m) are both
  above the backend's 25 MiB voice limit plus multipart overhead. Read
  timeouts are Ingress 180 s and nginx `/api/` 120 s; both must stay at or
  above the backend's STT timeout.
- The NodePort 32443 stays: plain-HTTP clients (the Android app) keep
  working.
