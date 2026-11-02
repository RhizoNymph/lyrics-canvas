#!/usr/bin/env bash
# Local CA + server certificate for HTTPS on the home microk8s cluster.
#
# Creates a small CA once (kept outside git, in ~/.config/lyrics-canvas/tls by
# default), issues or renews the ingress server certificate signed by it, and
# applies it as a kubernetes.io/tls Secret by piping the manifest to
# `kubectl apply -f -` over ssh (nothing is written to the node's disk).
# Every device trusts the CA once; renewing the server certificate needs no
# device changes. See docs/features/homelab_tls.md.

set -euo pipefail

# ---------------------------------------------------------------- exit codes
readonly EXIT_USAGE=2       # bad flag or configuration value
readonly EXIT_DEPENDENCY=3  # openssl / base64 / mktemp / ssh missing
readonly EXIT_CA_STATE=4    # existing CA incomplete, invalid or key/cert mismatch
readonly EXIT_ISSUE=5       # openssl failed creating the CA or issuing the server cert
readonly EXIT_SSH=6         # kube host unreachable over ssh
readonly EXIT_APPLY=7       # kubectl apply of the Secret failed

# ------------------------------------------------------------- configuration
TLS_DIR=${LYRICS_TLS_DIR-${HOME:?HOME must be set}/.config/lyrics-canvas/tls}
SANS=${LYRICS_TLS_SANS-IP:10.1.1.69,IP:10.1.1.68,IP:100.64.0.4,DNS:lyrics.s8.gay}
DAYS=${LYRICS_TLS_DAYS-397}
CA_DAYS=${LYRICS_TLS_CA_DAYS-3650}
RENEW_BEFORE_DAYS=${LYRICS_TLS_RENEW_BEFORE_DAYS-30}
KUBE_HOST=${LYRICS_KUBE_HOST-node0}
NAMESPACE=${LYRICS_NAMESPACE-lyrics-canvas}
SECRET=${LYRICS_TLS_SECRET-lyrics-canvas-tls}

readonly CA_SUBJECT="/O=Lyrics Canvas/CN=Lyrics Canvas Homelab CA"
readonly SERVER_SUBJECT="/O=Lyrics Canvas/CN=Lyrics Canvas"
readonly FIELD_MANAGER=lyrics-canvas-tls

DRY_RUN=0
RENEW=0
FORCE=0
NO_APPLY=0

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10)

CA_KEY="" CA_CRT="" SERVER_KEY="" SERVER_CRT=""
# Set when the CA was (or, in a dry run, would be) created or replaced.
CA_CHANGED=0
# Temporary files in TLS_DIR, removed on exit whatever happens.
TEMP_FILES=()

usage() {
  cat <<EOF
Usage: deploy/homelab/tls/issue-cert.sh [--dry-run] [--renew] [--force] [--no-apply] [-h]

Creates a local CA once, issues (or renews) the ingress server certificate
signed by it, and applies it to the cluster as a kubernetes.io/tls Secret over
ssh. Safe to re-run: an existing CA is always kept, and a valid server
certificate is only replaced when it is close to expiry, its SANs changed, or
--renew is given. The Secret is (re)applied on every run.

Flags:
  --dry-run   Report what would be created, issued and applied. Writes nothing, no ssh.
  --renew     Issue a new server certificate (and key) even if the current one is valid.
  --force     Replace the CA (old files kept as *.replaced-<timestamp>). Every device
              that trusted the old CA must then install the new one.
  --no-apply  Create/issue locally only; do not touch the cluster.
  -h, --help  Show this help.

Environment (default):
  LYRICS_TLS_DIR                directory for CA and server files ($TLS_DIR)
  LYRICS_TLS_SANS               server cert SANs, comma separated IP:/DNS: ($SANS)
  LYRICS_TLS_DAYS               server cert validity in days, 1-397 ($DAYS)
  LYRICS_TLS_CA_DAYS            CA validity in days for a new CA, 1-7300 ($CA_DAYS)
  LYRICS_TLS_RENEW_BEFORE_DAYS  reissue when the cert expires within this many days ($RENEW_BEFORE_DAYS)
  LYRICS_KUBE_HOST              ssh destination running microk8s kubectl ($KUBE_HOST)
  LYRICS_NAMESPACE              namespace of the Secret ($NAMESPACE)
  LYRICS_TLS_SECRET             Secret name ($SECRET)

Exit codes:
  0 ok   $EXIT_USAGE usage   $EXIT_DEPENDENCY missing tool   $EXIT_CA_STATE CA state   $EXIT_ISSUE openssl failure
  $EXIT_SSH ssh   $EXIT_APPLY kubectl apply
EOF
}

# ------------------------------------------------------------------- logging
# One line per event on stderr: ts=... level=... event=... key=value ...
kv_quote() {
  local v=$1
  if [[ -z $v || $v =~ [[:space:]\"\\] ]]; then
    v=${v//\\/\\\\}
    v=${v//\"/\\\"}
    printf '"%s"' "$v"
  else
    printf '%s' "$v"
  fi
}

log() {
  local level=$1 event=$2 kv line
  shift 2
  line="ts=$(date -u +%Y-%m-%dT%H:%M:%SZ) level=$level event=$event"
  for kv in "$@"; do
    line+=" ${kv%%=*}=$(kv_quote "${kv#*=}")"
  done
  if ((DRY_RUN)); then line+=" dry_run=1"; fi
  printf '%s\n' "$line" >&2
}

die() {
  local code=$1 event=$2
  shift 2
  log error "$event" "exit_code=$code" "$@"
  exit "$code"
}

# Last non-empty line of a file, for error details from openssl.
last_line() {
  local line last=""
  while IFS= read -r line || [[ -n $line ]]; do
    [[ -n $line ]] && last=$line
  done <"$1"
  printf '%s' "$last"
}

# ------------------------------------------------------------------- cleanup
cleanup() {
  local status=$? f
  for f in "${TEMP_FILES[@]}"; do
    rm -f -- "$f"
  done
  exit "$status"
}

# Creates an owner-only temporary file in TLS_DIR and prints its path.
temp_file() {
  local f
  f=$(mktemp "$TLS_DIR/.$1.XXXXXX") ||
    die "$EXIT_ISSUE" temp.create_failed "dir=$TLS_DIR"
  printf '%s' "$f"
}

# ------------------------------------------------------------- validation
parse_args() {
  while (($#)); do
    case $1 in
      --dry-run) DRY_RUN=1 ;;
      --renew) RENEW=1 ;;
      --force) FORCE=1 ;;
      --no-apply) NO_APPLY=1 ;;
      -h | --help)
        usage
        exit 0
        ;;
      *)
        usage >&2
        die "$EXIT_USAGE" usage.unknown_argument "arg=$1"
        ;;
    esac
    shift
  done
}

invalid() {
  die "$EXIT_USAGE" config.invalid "key=$1" "value=$2" "reason=$3"
}

check_int_range() {
  local key=$1 value=$2 min=$3 max=$4
  [[ $value =~ ^[0-9]+$ ]] || invalid "$key" "$value" "must be an integer"
  ((10#$value >= min && 10#$value <= max)) || invalid "$key" "$value" "must be between $min and $max"
}

valid_ipv4() {
  local ip=$1 octet
  [[ $ip =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || return 1
  local -a parts
  IFS=. read -ra parts <<<"$ip"
  for octet in "${parts[@]}"; do
    ((10#$octet <= 255)) || return 1
  done
}

valid_dns_name() {
  local label='[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?'
  [[ ${#1} -le 253 && $1 =~ ^($label\.)*$label$ ]]
}

# DNS-1123 label: Kubernetes namespace and Secret names used here.
valid_k8s_name() {
  [[ ${#1} -le 63 && $1 =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]]
}

validate_config() {
  local san
  [[ $TLS_DIR == /* ]] || invalid LYRICS_TLS_DIR "$TLS_DIR" "must be an absolute path"
  check_int_range LYRICS_TLS_DAYS "$DAYS" 1 397
  check_int_range LYRICS_TLS_CA_DAYS "$CA_DAYS" 1 7300
  check_int_range LYRICS_TLS_RENEW_BEFORE_DAYS "$RENEW_BEFORE_DAYS" 0 397
  [[ -n $SANS ]] || invalid LYRICS_TLS_SANS "$SANS" "at least one SAN is required"
  IFS=, read -ra SAN_LIST <<<"$SANS"
  ((${#SAN_LIST[@]})) || invalid LYRICS_TLS_SANS "$SANS" "at least one SAN is required"
  for san in "${SAN_LIST[@]}"; do
    case $san in
      IP:*) valid_ipv4 "${san#IP:}" || invalid LYRICS_TLS_SANS "$SANS" "bad IPv4 address in $san" ;;
      DNS:*) valid_dns_name "${san#DNS:}" || invalid LYRICS_TLS_SANS "$SANS" "bad DNS name in $san" ;;
      *) invalid LYRICS_TLS_SANS "$SANS" "each SAN must be IP:<ipv4> or DNS:<name>, got $san" ;;
    esac
  done
  [[ -n $KUBE_HOST && $KUBE_HOST != -* && ! $KUBE_HOST =~ [[:space:]] ]] ||
    invalid LYRICS_KUBE_HOST "$KUBE_HOST" "must be a non-empty ssh destination"
  valid_k8s_name "$NAMESPACE" || invalid LYRICS_NAMESPACE "$NAMESPACE" "must be a DNS-1123 label"
  valid_k8s_name "$SECRET" || invalid LYRICS_TLS_SECRET "$SECRET" "must be a DNS-1123 label"

  CA_KEY=$TLS_DIR/ca.key
  CA_CRT=$TLS_DIR/ca.crt
  SERVER_KEY=$TLS_DIR/server.key
  SERVER_CRT=$TLS_DIR/server.crt
}

check_dependencies() {
  local tool
  for tool in openssl base64 mktemp; do
    command -v "$tool" >/dev/null 2>&1 || die "$EXIT_DEPENDENCY" dependency.missing "tool=$tool"
  done
  if ((!NO_APPLY && !DRY_RUN)); then
    command -v ssh >/dev/null 2>&1 || die "$EXIT_DEPENDENCY" dependency.missing tool=ssh
  fi
}

# ------------------------------------------------------------- inspection
file_mode() {
  stat -c %a -- "$1"
}

cert_not_after() {
  local out
  out=$(openssl x509 -in "$1" -noout -enddate)
  printf '%s' "${out#notAfter=}"
}

cert_fingerprint() {
  local out
  out=$(openssl x509 -in "$1" -noout -fingerprint -sha256)
  printf '%s' "${out#*=}"
}

# Exit 0 when the private key in $1 belongs to the certificate in $2.
key_matches_cert() {
  local from_key from_cert
  from_key=$(openssl pkey -in "$1" -pubout 2>/dev/null) || return 1
  from_cert=$(openssl x509 -in "$2" -noout -pubkey 2>/dev/null) || return 1
  [[ -n $from_key && $from_key == "$from_cert" ]]
}

# SANs as openssl prints them ("IP Address:1.2.3.4", "DNS:x"), one per line, sorted.
normalized_wanted_sans() {
  local san
  for san in "${SAN_LIST[@]}"; do
    case $san in
      IP:*) printf 'IP Address:%s\n' "${san#IP:}" ;;
      *) printf '%s\n' "$san" ;;
    esac
  done | sort
}

normalized_cert_sans() {
  local out body san
  out=$(openssl x509 -in "$1" -noout -ext subjectAltName 2>/dev/null) || return 0
  [[ $out == *$'\n'* ]] || return 0
  body=${out#*$'\n'}
  local -a parts
  IFS=, read -ra parts <<<"${body//$'\n'/,}"
  for san in "${parts[@]}"; do
    san=${san#"${san%%[![:space:]]*}"}
    san=${san%"${san##*[![:space:]]}"}
    [[ -n $san ]] && printf '%s\n' "$san"
  done | sort
}

# ------------------------------------------------------------------- phases
ensure_dir() {
  if [[ ! -d $TLS_DIR ]]; then
    if ((DRY_RUN)); then
      log info dir.would_create "dir=$TLS_DIR"
      return 0
    fi
    mkdir -p -- "$TLS_DIR"
    log info dir.created "dir=$TLS_DIR"
  fi
  if [[ $(file_mode "$TLS_DIR") != 700 ]]; then
    if ((DRY_RUN)); then
      log warn dir.mode_loose "dir=$TLS_DIR" "mode=$(file_mode "$TLS_DIR")"
    else
      chmod 700 -- "$TLS_DIR"
      log info dir.mode_fixed "dir=$TLS_DIR" mode=700
    fi
  fi
}

write_ca_config() {
  local cfg=$1
  cat >"$cfg" <<EOF
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no

[dn]
O = Lyrics Canvas
CN = Lyrics Canvas Homelab CA

[v3_ca]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF
}

create_ca() {
  if ((DRY_RUN)); then
    log info ca.would_create "dir=$TLS_DIR" "days=$CA_DAYS"
    CA_CHANGED=1
    return 0
  fi
  local key crt cfg err
  key=$(temp_file ca.key)
  TEMP_FILES+=("$key")
  crt=$(temp_file ca.crt)
  TEMP_FILES+=("$crt")
  cfg=$(temp_file ca.cnf)
  TEMP_FILES+=("$cfg")
  err=$(temp_file openssl.err)
  TEMP_FILES+=("$err")
  write_ca_config "$cfg"
  if ! openssl req -x509 -config "$cfg" -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
    -sha256 -days "$CA_DAYS" -subj "$CA_SUBJECT" -keyout "$key" -out "$crt" 2>"$err"; then
    die "$EXIT_ISSUE" ca.create_failed "dir=$TLS_DIR" "detail=$(last_line "$err")"
  fi
  chmod 600 -- "$key"
  chmod 644 -- "$crt"
  mv -f -- "$crt" "$CA_CRT"
  mv -f -- "$key" "$CA_KEY"
  CA_CHANGED=1
  log info ca.created "path=$CA_CRT" "not_after=$(cert_not_after "$CA_CRT")" \
    "fingerprint=$(cert_fingerprint "$CA_CRT")"
}

replace_ca() {
  local stamp
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  if ((DRY_RUN)); then
    log warn ca.would_replace "dir=$TLS_DIR" note="every device must trust the new CA"
    create_ca
    return 0
  fi
  [[ -e $CA_KEY ]] && mv -- "$CA_KEY" "$CA_KEY.replaced-$stamp"
  [[ -e $CA_CRT ]] && mv -- "$CA_CRT" "$CA_CRT.replaced-$stamp"
  log warn ca.replaced "dir=$TLS_DIR" "backup_suffix=.replaced-$stamp" \
    note="every device that trusted the old CA must install the new one"
  create_ca
}

check_existing_ca() {
  local mode
  mode=$(file_mode "$CA_KEY")
  if [[ $mode != 600 ]]; then
    if ((DRY_RUN)); then
      log warn ca.key_mode_loose "path=$CA_KEY" "mode=$mode"
    else
      chmod 600 -- "$CA_KEY"
      log warn ca.key_mode_fixed "path=$CA_KEY" "old_mode=$mode" mode=600 \
        note="the CA key was readable by others; consider --force if that matters"
    fi
  fi
  local constraints
  constraints=$(openssl x509 -in "$CA_CRT" -noout -ext basicConstraints 2>/dev/null) ||
    die "$EXIT_CA_STATE" ca.invalid "path=$CA_CRT" reason="not a readable certificate"
  [[ $constraints == *CA:TRUE* ]] ||
    die "$EXIT_CA_STATE" ca.invalid "path=$CA_CRT" reason="certificate is not a CA" \
      hint="restore the original ca.crt, or pass --force to create a new CA"
  openssl x509 -in "$CA_CRT" -noout -checkend 0 >/dev/null 2>&1 ||
    die "$EXIT_CA_STATE" ca.invalid "path=$CA_CRT" reason="CA certificate has expired" \
      hint="pass --force to create a new CA"
  key_matches_cert "$CA_KEY" "$CA_CRT" ||
    die "$EXIT_CA_STATE" ca.key_mismatch "key=$CA_KEY" "cert=$CA_CRT" \
      hint="restore the matching pair, or pass --force to create a new CA"
  if ! openssl x509 -in "$CA_CRT" -noout -checkend $((10#$DAYS * 86400)) >/dev/null 2>&1; then
    log warn ca.expiring "path=$CA_CRT" "not_after=$(cert_not_after "$CA_CRT")" \
      note="server certificates will outlive the CA; plan a --force rotation"
  fi
  log info ca.exists "path=$CA_CRT" "not_after=$(cert_not_after "$CA_CRT")" \
    "fingerprint=$(cert_fingerprint "$CA_CRT")"
}

ensure_ca() {
  if ((FORCE)); then
    replace_ca
  elif [[ -e $CA_KEY && -e $CA_CRT ]]; then
    check_existing_ca
  elif [[ -e $CA_KEY || -e $CA_CRT ]]; then
    local present=ca.key missing=ca.crt
    [[ -e $CA_CRT ]] && present=ca.crt missing=ca.key
    die "$EXIT_CA_STATE" ca.incomplete "dir=$TLS_DIR" "present=$present" "missing=$missing" \
      hint="restore the missing file, or pass --force to create a new CA (every device must then trust it again)"
  else
    create_ca
  fi
}

# Prints why the server certificate must be (re)issued, or nothing if it is fine.
server_issue_reason() {
  if [[ ! -e $SERVER_CRT || ! -e $SERVER_KEY ]]; then
    printf missing
  elif ((CA_CHANGED)); then
    printf ca_replaced
  elif ((RENEW)); then
    printf renew_requested
  elif ! openssl verify -CAfile "$CA_CRT" -purpose sslserver "$SERVER_CRT" >/dev/null 2>&1; then
    printf not_signed_by_ca
  elif ! key_matches_cert "$SERVER_KEY" "$SERVER_CRT"; then
    printf key_mismatch
  elif [[ $(normalized_cert_sans "$SERVER_CRT") != "$(normalized_wanted_sans)" ]]; then
    printf sans_changed
  elif ! openssl x509 -in "$SERVER_CRT" -noout -checkend $((10#$RENEW_BEFORE_DAYS * 86400)) >/dev/null 2>&1; then
    printf expiring
  fi
}

write_server_extensions() {
  local ext=$1
  cat >"$ext" <<EOF
[v3_server]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = $SANS
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
EOF
}

verify_server_cert() {
  local crt=$1 san
  openssl verify -CAfile "$CA_CRT" -purpose sslserver "$crt" >/dev/null 2>&1 || return 1
  for san in "${SAN_LIST[@]}"; do
    case $san in
      IP:*) openssl verify -CAfile "$CA_CRT" -verify_ip "${san#IP:}" "$crt" >/dev/null 2>&1 || return 1 ;;
      DNS:*) openssl verify -CAfile "$CA_CRT" -verify_hostname "${san#DNS:}" "$crt" >/dev/null 2>&1 || return 1 ;;
    esac
  done
}

issue_server() {
  local reason=$1 key csr crt ext err serial
  key=$(temp_file server.key)
  TEMP_FILES+=("$key")
  csr=$(temp_file server.csr)
  TEMP_FILES+=("$csr")
  crt=$(temp_file server.crt)
  TEMP_FILES+=("$crt")
  ext=$(temp_file server.ext)
  TEMP_FILES+=("$ext")
  err=$(temp_file openssl.err)
  TEMP_FILES+=("$err")
  write_server_extensions "$ext"
  if ! openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$key" 2>"$err"; then
    die "$EXIT_ISSUE" server.issue_failed step=genpkey "detail=$(last_line "$err")"
  fi
  if ! openssl req -new -key "$key" -subj "$SERVER_SUBJECT" -out "$csr" 2>"$err"; then
    die "$EXIT_ISSUE" server.issue_failed step=csr "detail=$(last_line "$err")"
  fi
  if ! serial=$(openssl rand -hex 16 2>"$err"); then
    die "$EXIT_ISSUE" server.issue_failed step=serial "detail=$(last_line "$err")"
  fi
  if ! openssl x509 -req -in "$csr" -CA "$CA_CRT" -CAkey "$CA_KEY" -set_serial "0x$serial" \
    -days "$DAYS" -sha256 -extfile "$ext" -extensions v3_server -out "$crt" 2>"$err"; then
    die "$EXIT_ISSUE" server.issue_failed step=sign "detail=$(last_line "$err")"
  fi
  verify_server_cert "$crt" ||
    die "$EXIT_ISSUE" server.issue_failed step=verify reason="issued certificate does not verify for every SAN"
  chmod 600 -- "$key"
  chmod 644 -- "$crt"
  mv -f -- "$key" "$SERVER_KEY"
  mv -f -- "$crt" "$SERVER_CRT"
  log info server.issued "reason=$reason" "path=$SERVER_CRT" "sans=$SANS" "days=$DAYS" \
    "not_after=$(cert_not_after "$SERVER_CRT")" "fingerprint=$(cert_fingerprint "$SERVER_CRT")"
}

ensure_server() {
  local reason
  if ((DRY_RUN)) && [[ ! -d $TLS_DIR ]]; then
    reason=missing
  else
    reason=$(server_issue_reason)
  fi
  if [[ -z $reason ]]; then
    if [[ $(file_mode "$SERVER_KEY") != 600 ]] && ((!DRY_RUN)); then
      chmod 600 -- "$SERVER_KEY"
      log warn server.key_mode_fixed "path=$SERVER_KEY" mode=600
    fi
    log info server.valid "path=$SERVER_CRT" "not_after=$(cert_not_after "$SERVER_CRT")"
    return 0
  fi
  if ((DRY_RUN)); then
    log info server.would_issue "reason=$reason" "sans=$SANS" "days=$DAYS"
    return 0
  fi
  issue_server "$reason"
}

secret_manifest() {
  local crt_b64 key_b64
  crt_b64=$(base64 -w0 <"$SERVER_CRT")
  key_b64=$(base64 -w0 <"$SERVER_KEY")
  cat <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: $SECRET
  namespace: $NAMESPACE
  labels:
    app.kubernetes.io/part-of: lyrics-canvas
    app.kubernetes.io/managed-by: $FIELD_MANAGER
  annotations:
    lyrics-canvas/cert-not-after: "$(cert_not_after "$SERVER_CRT")"
    lyrics-canvas/cert-sha256: "$(cert_fingerprint "$SERVER_CRT")"
type: kubernetes.io/tls
data:
  tls.crt: $crt_b64
  tls.key: $key_b64
EOF
}

apply_secret() {
  # Server-side apply: no last-applied-configuration annotation, which would
  # hold a second copy of the private key on the object.
  local cmd="microk8s kubectl apply --server-side --field-manager=$FIELD_MANAGER --force-conflicts -f -"
  if ((NO_APPLY)); then
    log info secret.apply_skipped reason=--no-apply "namespace=$NAMESPACE" "secret=$SECRET"
    return 0
  fi
  if ((DRY_RUN)); then
    log info remote.dry_run "host=$KUBE_HOST" "cmd=$cmd" stdin=secret-manifest \
      "namespace=$NAMESPACE" "secret=$SECRET"
    return 0
  fi
  local manifest rc=0
  manifest=$(secret_manifest)
  log info remote.run "host=$KUBE_HOST" "cmd=$cmd" stdin=secret-manifest
  # shellcheck disable=SC2029 # cmd is a fixed local string on purpose
  printf '%s\n' "$manifest" | ssh "${SSH_OPTS[@]}" "$KUBE_HOST" "$cmd" >&2 || rc=$?
  case $rc in
    0) ;;
    255) die "$EXIT_SSH" secret.ssh_failed "host=$KUBE_HOST" \
      hint="local files are kept; re-run once the host is reachable" ;;
    *) die "$EXIT_APPLY" secret.apply_failed "host=$KUBE_HOST" "namespace=$NAMESPACE" \
      "secret=$SECRET" "kubectl_exit=$rc" hint="does the namespace exist? deploy the app first" ;;
  esac
  log info secret.applied "host=$KUBE_HOST" "namespace=$NAMESPACE" "secret=$SECRET"
}

report() {
  if ((DRY_RUN)); then
    log info finished "dir=$TLS_DIR"
    printf 'Dry run: nothing written, nothing applied. CA directory: %s\n' "$TLS_DIR"
    return 0
  fi
  log info finished "dir=$TLS_DIR" "namespace=$NAMESPACE" "secret=$SECRET"
  cat <<EOF
CA certificate (install once on every device that opens the app):
  $CA_CRT
  SHA-256 fingerprint: $(cert_fingerprint "$CA_CRT")
Server certificate: $SERVER_CRT (valid until $(cert_not_after "$SERVER_CRT"))

Browsers send no SNI for https://<ip>/, so ingress-nginx answers IP URLs with its
default certificate. If not done yet, point it at this Secret once per cluster:
  ssh $KUBE_HOST microk8s disable ingress
  ssh $KUBE_HOST microk8s enable ingress:default-ssl-certificate=$NAMESPACE/$SECRET
(See docs/features/homelab_tls.md for a no-downtime alternative and the
per-device CA install steps.)
EOF
}

main() {
  parse_args "$@"
  validate_config
  check_dependencies
  umask 077
  trap cleanup EXIT
  ensure_dir
  ensure_ca
  ensure_server
  apply_secret
  report
}

main "$@"
