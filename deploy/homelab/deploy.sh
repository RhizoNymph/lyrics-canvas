#!/usr/bin/env bash
# One-command deploy of the committed tree to the home microk8s cluster.
#
# Ships `git archive HEAD` to a build host over ssh, builds and pushes the
# backend and frontend images tagged with the short commit SHA to the
# microk8s registry addon, then runs `helm upgrade --install` with the
# homelab values file and `helm test`. See docs/features/homelab_deploy.md.
#
# Nothing runs on this machine except git and ssh.

set -euo pipefail

# ---------------------------------------------------------------- exit codes
readonly EXIT_USAGE=2         # bad flag, flag combination or configuration
readonly EXIT_GIT=3           # not a repo, dirty tree, chart/values not committed
readonly EXIT_SSH=4           # build host unreachable over ssh
readonly EXIT_SHIP=5          # copying the tree to the build host failed
readonly EXIT_REGISTRY=6      # registry query failed, or --skip-build with a missing image
readonly EXIT_BUILD=7         # docker build or push failed
readonly EXIT_HELM_UPGRADE=8  # helm upgrade --install failed (existing release rolled back)
readonly EXIT_HELM_TEST=9     # helm test failed (new revision stays deployed)

# ------------------------------------------------------------- configuration
BUILD_HOST=${LYRICS_BUILD_HOST:-node0}
RELEASE=${LYRICS_RELEASE:-lyrics}
NAMESPACE=${LYRICS_NAMESPACE:-lyrics-canvas}
CHART=${LYRICS_CHART:-deploy/helm/lyrics-canvas}
VALUES=${LYRICS_VALUES:-deploy/helm/environments/homelab.yaml}
APP_HOST=${LYRICS_APP_HOST:-10.1.1.69}
APP_PORT=${LYRICS_APP_PORT:-32443}
REGISTRY=${LYRICS_REGISTRY:-localhost:32000}
BACKEND_REPO=${LYRICS_BACKEND_REPO:-lyrics-canvas-backend}
FRONTEND_REPO=${LYRICS_FRONTEND_REPO:-lyrics-canvas-frontend}
HELM_TIMEOUT=${LYRICS_HELM_TIMEOUT:-10m}
HELM_TEST_TIMEOUT=${LYRICS_HELM_TEST_TIMEOUT:-5m}
REMOTE_BASE=${LYRICS_REMOTE_BASE-lyrics-canvas-deploy}
VITE_GOOGLE_CLIENT_ID=${LYRICS_VITE_GOOGLE_CLIENT_ID:-}

DRY_RUN=0
SKIP_BUILD=0
REBUILD=0
ALLOW_DIRTY=0

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10)

# Set once the remote build directory may exist; the EXIT trap removes it.
REMOTE_DIR=""
REMOTE_DIR_CREATED=0

usage() {
  cat <<EOF
Usage: deploy/homelab/deploy.sh [--dry-run] [--skip-build | --rebuild] [--allow-dirty] [-h]

Builds lyrics-canvas images from the committed tree (HEAD) on a build host,
pushes them to the microk8s registry tagged with the short commit SHA, and
deploys them with helm using the homelab values file.

Flags:
  --dry-run      Print every remote command instead of running it. No ssh.
  --skip-build   Do not build; the SHA-tagged images must already be in the registry.
  --rebuild      Build and push even if the SHA tag already exists in the registry.
  --allow-dirty  Proceed with uncommitted changes (they are NOT deployed; HEAD is).
  -h, --help     Show this help.

Environment (default):
  LYRICS_BUILD_HOST             ssh destination of the build host ($BUILD_HOST)
  LYRICS_RELEASE                helm release name ($RELEASE)
  LYRICS_NAMESPACE              Kubernetes namespace ($NAMESPACE)
  LYRICS_VALUES                 values file, relative to the repo root ($VALUES)
  LYRICS_CHART                  chart directory, relative to the repo root ($CHART)
  LYRICS_APP_HOST               host shown in the app URL ($APP_HOST)
  LYRICS_APP_PORT               frontend NodePort ($APP_PORT)
  LYRICS_REGISTRY               registry as seen from the build host ($REGISTRY)
  LYRICS_BACKEND_REPO           backend image repository ($BACKEND_REPO)
  LYRICS_FRONTEND_REPO          frontend image repository ($FRONTEND_REPO)
  LYRICS_HELM_TIMEOUT           helm upgrade --timeout ($HELM_TIMEOUT)
  LYRICS_HELM_TEST_TIMEOUT      helm test --timeout ($HELM_TEST_TIMEOUT)
  LYRICS_REMOTE_BASE            build directory parent, relative to ~ on the build host ($REMOTE_BASE)
  LYRICS_VITE_GOOGLE_CLIENT_ID  VITE_GOOGLE_CLIENT_ID build arg for the frontend (empty)

Exit codes:
  0 deployed   $EXIT_USAGE usage   $EXIT_GIT git preflight   $EXIT_SSH ssh   $EXIT_SHIP ship
  $EXIT_REGISTRY registry   $EXIT_BUILD build/push   $EXIT_HELM_UPGRADE helm upgrade   $EXIT_HELM_TEST helm test
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
  # In a dry run nothing remote happened, whatever the event name says.
  if ((DRY_RUN)); then line+=" dry_run=1"; fi
  printf '%s\n' "$line" >&2
}

die() {
  local code=$1 event=$2
  shift 2
  log error "$event" "exit_code=$code" "$@"
  exit "$code"
}

# -------------------------------------------------------------------- remote
# Quote each argument for the remote bash and join with spaces.
shell_join() {
  local out="" arg
  for arg in "$@"; do
    out+="$(printf '%q' "$arg") "
  done
  printf '%s' "${out% }"
}

# Run a command string on the build host, streaming its output.
remote() {
  local cmd=$1
  if ((DRY_RUN)); then
    log info remote.dry_run "host=$BUILD_HOST" "cmd=$cmd"
    return 0
  fi
  log info remote.run "host=$BUILD_HOST" "cmd=$cmd"
  # shellcheck disable=SC2029 # cmd is built locally and quoted with shell_join on purpose
  ssh -n "${SSH_OPTS[@]}" "$BUILD_HOST" "$cmd"
}

# Run a command string on the build host with this script's stdin attached.
remote_stdin() {
  local cmd=$1
  if ((DRY_RUN)); then
    log info remote.dry_run "host=$BUILD_HOST" "cmd=$cmd" stdin=git-archive
    return 0
  fi
  log info remote.run "host=$BUILD_HOST" "cmd=$cmd" stdin=1
  # shellcheck disable=SC2029 # cmd is built locally and quoted with shell_join on purpose
  ssh "${SSH_OPTS[@]}" "$BUILD_HOST" "$cmd"
}

# ------------------------------------------------------------------- cleanup
cleanup() {
  local status=$?
  if ((REMOTE_DIR_CREATED)); then
    REMOTE_DIR_CREATED=0
    if remote "$(shell_join rm -rf "$REMOTE_DIR")"; then
      log info cleanup.done "host=$BUILD_HOST" "dir=~/$REMOTE_DIR"
    else
      log warn cleanup.failed "host=$BUILD_HOST" "dir=~/$REMOTE_DIR"
    fi
  fi
  exit "$status"
}

on_signal() {
  log warn deploy.interrupted "signal=$1"
  exit "$2"
}

# ------------------------------------------------------------------- phases
parse_args() {
  while (($#)); do
    case $1 in
      --dry-run) DRY_RUN=1 ;;
      --skip-build) SKIP_BUILD=1 ;;
      --rebuild) REBUILD=1 ;;
      --allow-dirty) ALLOW_DIRTY=1 ;;
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
  if ((SKIP_BUILD && REBUILD)); then
    die "$EXIT_USAGE" usage.conflicting_flags flags="--skip-build --rebuild"
  fi
}

validate_config() {
  # REMOTE_BASE ends up in `rm -rf`, so keep it a plain relative path under ~.
  if [[ -z $REMOTE_BASE || $REMOTE_BASE == /* || ! $REMOTE_BASE =~ ^[A-Za-z0-9._/-]+$ || /$REMOTE_BASE/ == */../* ]]; then
    die "$EXIT_USAGE" config.invalid key=LYRICS_REMOTE_BASE "value=$REMOTE_BASE" \
      reason="must be a relative path under the build host's home without .."
  fi
  if [[ -z $BUILD_HOST || -z $RELEASE || -z $NAMESPACE || -z $VALUES || -z $CHART ]]; then
    die "$EXIT_USAGE" config.invalid reason="build host, release, namespace, values and chart must be non-empty"
  fi
}

REPO_ROOT=""
TAG=""

preflight_git() {
  local script_dir status
  script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  REPO_ROOT=$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null) ||
    die "$EXIT_GIT" preflight.not_a_repo "dir=$script_dir"

  status=$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal) ||
    die "$EXIT_GIT" preflight.git_status_failed "repo=$REPO_ROOT"
  if [[ -n $status ]]; then
    if ((ALLOW_DIRTY)); then
      log warn preflight.dirty_tree_allowed "repo=$REPO_ROOT" \
        "changed_paths=$(wc -l <<<"$status" | tr -d ' ')" \
        note="UNCOMMITTED CHANGES ARE NOT DEPLOYED; only HEAD is shipped"
    else
      log error preflight.dirty_tree "repo=$REPO_ROOT" \
        "changed_paths=$(wc -l <<<"$status" | tr -d ' ')" \
        hint="commit or stash, or pass --allow-dirty to deploy HEAD anyway"
      exit "$EXIT_GIT"
    fi
  fi

  TAG=$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD) ||
    die "$EXIT_GIT" preflight.no_head "repo=$REPO_ROOT"

  git -C "$REPO_ROOT" cat-file -e "HEAD:$VALUES" 2>/dev/null ||
    die "$EXIT_GIT" preflight.values_not_committed "values=$VALUES"
  git -C "$REPO_ROOT" cat-file -e "HEAD:$CHART/Chart.yaml" 2>/dev/null ||
    die "$EXIT_GIT" preflight.chart_not_committed "chart=$CHART"

  REMOTE_DIR="$REMOTE_BASE/$TAG-$$"
  log info preflight.ok "tag=$TAG" "repo=$REPO_ROOT"
}

preflight_ssh() {
  remote true ||
    die "$EXIT_SSH" preflight.ssh_unreachable "host=$BUILD_HOST"
  log info preflight.ssh_ok "host=$BUILD_HOST"
}

ship_tree() {
  REMOTE_DIR_CREATED=1
  local cmd
  cmd="$(shell_join mkdir -p "$REMOTE_DIR") && $(shell_join tar -xf - -C "$REMOTE_DIR")"
  if ((DRY_RUN)); then
    remote_stdin "$cmd"
  else
    git -C "$REPO_ROOT" archive --format=tar HEAD | remote_stdin "$cmd" ||
      die "$EXIT_SHIP" ship.failed "host=$BUILD_HOST" "dir=~/$REMOTE_DIR"
  fi
  log info ship.done "host=$BUILD_HOST" "dir=~/$REMOTE_DIR" "commit=$TAG"
}

# Prints "present" or "absent" for $REGISTRY/<repo>:$TAG, queried on the build host.
image_state() {
  local repo=$1 out body code
  local url="http://$REGISTRY/v2/$repo/tags/list"
  if ((DRY_RUN)); then
    remote "$(shell_join curl -s -o - -w '\n%{http_code}' "$url")"
    printf 'absent'
    return 0
  fi
  out=$(remote "$(shell_join curl -s -o - -w '\n%{http_code}' "$url")") ||
    die "$EXIT_REGISTRY" registry.unreachable "url=$url" "host=$BUILD_HOST"
  code=${out##*$'\n'}
  body=${out%$'\n'*}
  case $code in
    200)
      if [[ $body == *"\"$TAG\""* ]]; then printf 'present'; else printf 'absent'; fi
      ;;
    404) printf 'absent' ;;
    *) die "$EXIT_REGISTRY" registry.unexpected_status "url=$url" "http_status=$code" ;;
  esac
}

build_image() {
  local repo=$1 image=$2
  shift 2
  local -a build_args=("$@")
  remote "$(shell_join docker build --network host "${build_args[@]}" -t "$image" "$REMOTE_DIR/${repo}")" ||
    die "$EXIT_BUILD" image.build_failed "image=$image"
  remote "$(shell_join docker push "$image")" ||
    die "$EXIT_BUILD" image.push_failed "image=$image"
  log info image.pushed "image=$image"
}

ensure_image() {
  local component=$1 repo=$2
  shift 2
  local image="$REGISTRY/$repo:$TAG" state=absent
  if ((!REBUILD)); then
    state=$(image_state "$repo") || exit $?
  fi
  if [[ $state == present ]]; then
    log info image.exists "image=$image" action=skip_build
    return 0
  fi
  if ((SKIP_BUILD)); then
    die "$EXIT_REGISTRY" image.missing "image=$image" hint="drop --skip-build to build it"
  fi
  log info image.build "image=$image" "rebuild=$REBUILD"
  build_image "$component" "$image" "$@"
}

images() {
  ensure_image backend "$BACKEND_REPO"
  ensure_image frontend "$FRONTEND_REPO" \
    -f "$REMOTE_DIR/frontend/Dockerfile.prod" \
    --build-arg "VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID"
}

release_exists() {
  if ((DRY_RUN)); then
    remote "$(shell_join microk8s helm3 status "$RELEASE" -n "$NAMESPACE")"
    return 0
  fi
  remote "$(shell_join microk8s helm3 status "$RELEASE" -n "$NAMESPACE")" >/dev/null 2>&1
}

helm_deploy() {
  local -a args=(
    microk8s helm3 upgrade --install "$RELEASE" "$REMOTE_DIR/$CHART"
    -n "$NAMESPACE" --create-namespace
    -f "$REMOTE_DIR/$VALUES"
    --set "backend.image.tag=$TAG"
    --set "frontend.image.tag=$TAG"
    --wait --timeout "$HELM_TIMEOUT"
  )
  # --atomic rolls a failed upgrade back to the last good revision. It is left
  # off for a first install, where it would uninstall the release, deleting the
  # generated Secrets while the postgres PVC keeps data initialised with the
  # old password.
  local atomic=0
  if release_exists; then
    args+=(--atomic)
    atomic=1
  fi
  log info helm.upgrade "release=$RELEASE" "namespace=$NAMESPACE" "tag=$TAG" "atomic=$atomic"
  remote "$(shell_join "${args[@]}")" ||
    die "$EXIT_HELM_UPGRADE" helm.upgrade_failed "release=$RELEASE" "namespace=$NAMESPACE" \
      "rolled_back=$atomic"
}

helm_test() {
  remote "$(shell_join microk8s helm3 test "$RELEASE" -n "$NAMESPACE" --logs --timeout "$HELM_TEST_TIMEOUT")" ||
    die "$EXIT_HELM_TEST" helm.test_failed "release=$RELEASE" "namespace=$NAMESPACE" \
      hint="new revision is live; roll back with: microk8s helm3 rollback $RELEASE -n $NAMESPACE"
  log info helm.test_passed "release=$RELEASE"
}

report() {
  local url="http://$APP_HOST:$APP_PORT" status revision=unknown
  if ((!DRY_RUN)); then
    if status=$(remote "$(shell_join microk8s helm3 status "$RELEASE" -n "$NAMESPACE")" 2>/dev/null); then
      revision=$(sed -n 's/^REVISION: *//p' <<<"$status")
      revision=${revision:-unknown}
    else
      log warn helm.status_failed "release=$RELEASE" "namespace=$NAMESPACE"
    fi
  fi
  log info deploy.done "release=$RELEASE" "namespace=$NAMESPACE" "tag=$TAG" \
    "revision=$revision" "url=$url"
  if ((DRY_RUN)); then
    printf 'Dry run: would deploy %s (tag %s) to %s\n' "$RELEASE" "$TAG" "$url"
  else
    printf 'Deployed %s revision %s (tag %s): %s\n' "$RELEASE" "$revision" "$TAG" "$url"
  fi
}

main() {
  parse_args "$@"
  validate_config
  preflight_git
  trap cleanup EXIT
  trap 'on_signal INT 130' INT
  trap 'on_signal TERM 143' TERM
  preflight_ssh
  ship_tree
  if ((SKIP_BUILD)); then
    log info image.build_skipped reason=--skip-build
  fi
  images
  helm_deploy
  helm_test
  report
}

main "$@"
