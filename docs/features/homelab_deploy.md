# Homelab Deploy

`deploy/homelab/deploy.sh` deploys the committed tree to the home microk8s
cluster in one command: it builds the backend and frontend images on a build
host, pushes them to the microk8s registry addon tagged with the short commit
SHA, and runs `helm upgrade --install` with
`deploy/helm/environments/homelab.yaml`, then `helm test`.

```bash
deploy/homelab/deploy.sh               # build what is missing, deploy HEAD
deploy/homelab/deploy.sh --dry-run     # print every remote command, run none
deploy/homelab/deploy.sh --skip-build  # redeploy images already in the registry
deploy/homelab/deploy.sh --rebuild     # rebuild and push even if the tag exists
```

The app is then at `http://10.1.1.69:32443`. The node names (node0, node1,
node2) are ssh aliases on the laptop, not DNS names, so URLs use IPs.

## Scope

- Preflight: clean git tree (tracked changes, staged changes and untracked
  files all count), values file and chart committed at HEAD, build host
  reachable over ssh (`BatchMode=yes`, 10 s connect timeout).
- Image tag = `git rev-parse --short=12 HEAD`. The fixed length keeps the tag
  for a commit stable as the repository grows, so registry skip checks keep
  matching.
- Shipping exactly the committed tree (`git archive HEAD`) to a per-run
  directory under `~` on the build host, removed on exit, including on failure
  and on INT/TERM.
- Building both images with `docker build --network host` and pushing them,
  skipping an image whose tag is already in the registry unless `--rebuild`.
- `helm upgrade --install` with the homelab values file and the two image
  tags, `--wait --timeout 10m`, `--atomic` when the release already exists,
  then `helm test`, then printing the URL and the deployed revision.
- Structured `key=value` log lines on stderr and one exit code per failure
  class.

## Non-scope

- Deploying uncommitted work. `--allow-dirty` only lifts the clean-tree check;
  HEAD is still what ships, and the run logs a warning saying so.
- Credential overrides (`homelab.secret.yaml`). It is gitignored, so it is not
  in `git archive HEAD`; generated credentials are kept across upgrades by the
  chart. Pass it with a manual helm run if ever needed.
- Pruning old image tags from the registry, CI, other clusters.
- Running anything locally beyond `git` and `ssh` (no local docker, kubectl or
  helm).

## Flow

```
laptop                                   build host (node0, over ssh)
------                                   ----------------------------
parse flags, validate env
git status --porcelain     (exit 3 if dirty, unless --allow-dirty)
git rev-parse --short=12 HEAD -> TAG
git cat-file -e HEAD:<values>, HEAD:<chart>/Chart.yaml
trap cleanup EXIT
ssh true  ------------------------------> (exit 4 if unreachable)
git archive HEAD | ssh -----------------> mkdir -p ~/lyrics-canvas-deploy/<TAG>-<pid>
                                          tar -xf - -C <dir>          (exit 5)
for backend, frontend:
  unless --rebuild: ssh ---------------> curl localhost:32000/v2/<repo>/tags/list
                                          200 + "<TAG>" in body -> present
                                          200 without it, 404   -> absent
                                          anything else         -> exit 6
  present -> skip; absent + --skip-build -> exit 6
  absent  -> ssh ----------------------> docker build --network host -t localhost:32000/<repo>:<TAG> <dir>/<component>
                                          docker push ...               (exit 7)
ssh ------------------------------------> microk8s helm3 status <release>   (exists? -> --atomic)
ssh ------------------------------------> microk8s helm3 upgrade --install <release> <dir>/<chart>
                                            -n <ns> --create-namespace -f <dir>/<values>
                                            --set backend.image.tag=<TAG> --set frontend.image.tag=<TAG>
                                            --wait --timeout 10m [--atomic]  (exit 8)
ssh ------------------------------------> microk8s helm3 test <release> -n <ns> --logs   (exit 9)
ssh ------------------------------------> microk8s helm3 status <release>   -> REVISION
print "Deployed <release> revision N (tag TAG): http://<app host>:<port>"
EXIT trap: ssh ------------------------> rm -rf ~/lyrics-canvas-deploy/<TAG>-<pid>
```

Every remote step goes through one of two functions: `remote` (`ssh -n`, no
stdin) and `remote_stdin` (only the archive upload). Under `--dry-run` both
log the command (`event=remote.dry_run cmd=...`) and return success without
calling ssh; the registry check then reports every image as absent, so the
dry run shows the full build. Remote commands are assembled locally with
`printf %q` per argument (`shell_join`) for the build host's bash.

### Why `--atomic`, and only on upgrade

The cluster runs Helm 3.17 (`microk8s helm3`), which has `--atomic`;
`--rollback-on-failure` is its Helm 4 name. On an upgrade, `--atomic` rolls
back to the last good revision if the new pods do not become ready within the
timeout, so a broken image never stays live. On a first install it would
instead uninstall the release, deleting the generated JWT and database
Secrets while the postgres PVC (from `volumeClaimTemplates`, which uninstall
leaves behind) keeps data initialised with the old password; the next install
would generate a new password that no longer matches. So the script checks
`helm status` first and adds `--atomic` only for an existing release.

A failing `helm test` does not roll back: the pods are already ready, and the
error line includes the `helm3 rollback` command.

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `LYRICS_BUILD_HOST` | `node0` | ssh destination (alias, `user@ip`, ...). |
| `LYRICS_RELEASE` | `lyrics` | Helm release. |
| `LYRICS_NAMESPACE` | `lyrics-canvas` | Namespace. |
| `LYRICS_VALUES` | `deploy/helm/environments/homelab.yaml` | Values file, repo-relative, must be committed. |
| `LYRICS_CHART` | `deploy/helm/lyrics-canvas` | Chart directory, repo-relative. |
| `LYRICS_APP_HOST` | `10.1.1.69` | Host in the printed URL. |
| `LYRICS_APP_PORT` | `32443` | Frontend NodePort in the printed URL. |
| `LYRICS_REGISTRY` | `localhost:32000` | Registry as seen from the build host. |
| `LYRICS_BACKEND_REPO` / `LYRICS_FRONTEND_REPO` | `lyrics-canvas-backend` / `lyrics-canvas-frontend` | Image repositories; must match the values file. |
| `LYRICS_HELM_TIMEOUT` / `LYRICS_HELM_TEST_TIMEOUT` | `10m` / `5m` | Helm timeouts. |
| `LYRICS_REMOTE_BASE` | `lyrics-canvas-deploy` | Parent of the build directory, relative to `~`. |
| `LYRICS_VITE_GOOGLE_CLIENT_ID` | empty | `VITE_GOOGLE_CLIENT_ID` frontend build arg. |

### Exit codes

| Code | Failure class |
|------|---------------|
| 0 | Deployed (or dry run completed). |
| 2 | Usage: unknown flag, `--skip-build` with `--rebuild`, invalid configuration. |
| 3 | Git preflight: not a repo, dirty tree, values or chart not committed. |
| 4 | Build host unreachable over ssh. |
| 5 | Shipping the archive failed. |
| 6 | Registry query failed, or `--skip-build` with an image tag missing. |
| 7 | `docker build` or `docker push` failed. |
| 8 | `helm upgrade --install` failed (an existing release was rolled back). |
| 9 | `helm test` failed (the new revision stays deployed). |

## Files

| File | Role |
|------|------|
| `deploy/homelab/deploy.sh` | The script. Functions per phase: `parse_args`, `validate_config`, `preflight_git`, `preflight_ssh`, `ship_tree`, `images` / `ensure_image` / `image_state` / `build_image`, `helm_deploy`, `helm_test`, `report`; `remote` / `remote_stdin` / `shell_join` for ssh; `log` / `die` for structured logs; `cleanup` on EXIT. |
| `deploy/homelab/tests/test_deploy_script.py` | `unittest` suite (stdlib only). Each test makes a throwaway git repo containing a copy of the script, runs it with the ssh stub first on PATH, and asserts on the recorded remote commands, exit codes and logs. Also checks the script's registry defaults against `homelab.yaml`, that no tracked doc shows a `http://nodeN` URL, `bash -n`, and `shellcheck` when installed. |
| `deploy/homelab/tests/stubs/ssh` | Recording ssh stand-in: logs flags, host, remote command and saved stdin per call to `$STUB_SSH_LOG`; answers from regex rules in `$STUB_SSH_RULES`. |
| `deploy/helm/environments/homelab.yaml` | Values the script deploys with; its `dev` tags are only the fallback for a manual helm run. |

Run the tests with `python3 deploy/homelab/tests/test_deploy_script.py`.

## Invariants and constraints

- The deployed images are built from exactly the commit their tag names:
  only `git archive HEAD` is shipped, never the working tree, and the tag is
  that commit's SHA.
- Values always come from `-f <values file>` plus the two `--set` image tags;
  the script never passes `--reuse-values` (or `--reset-values`), so a value
  removed from `homelab.yaml` is removed from the release.
- No remote command runs under `--dry-run`; every remote command goes through
  `remote` or `remote_stdin`.
- The remote build directory is always under `~` (snap docker cannot read the
  host's `/tmp`), is unique per run (`<TAG>-<pid>`), and is removed by the EXIT
  trap whenever the upload was attempted. `LYRICS_REMOTE_BASE` must be a
  relative path without `..`, because it reaches `rm -rf`.
- `docker build` always uses `--network host` (DNS on the node network fails
  for some names inside the default bridge).
- A cleanup failure is logged (`event=cleanup.failed`) but never changes the
  exit code of the run.
- The skip check matches the exact quoted tag in the registry's tag list, so
  a longer or shorter SHA never counts as present. It does not know the build
  args an image was built with: after changing
  `LYRICS_VITE_GOOGLE_CLIENT_ID` for an already built commit, use `--rebuild`.
- `LYRICS_REGISTRY/LYRICS_*_REPO` must name the same images as the values
  file's `image.repository`; the test suite checks the defaults.
