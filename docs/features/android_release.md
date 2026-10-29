# Android Release Build

A tagged release of the repository produces an installable Android APK attached to a GitHub
Release. The build is reproducible from the command line too; CI only supplies the version, the
backend URL and (optionally) the signing key.

## Scope

- Gradle: version name and code from properties, release signing from the environment with a
  typed, all-or-nothing configuration, and an explicit fallback to the debug key.
- `.github/workflows/android-release.yml`: test, build, verify and publish on `v*` tags, or build
  only via manual dispatch.
- `android/scripts/verify_release_apk.sh`: asserts the produced APK's package, version, signer
  and baked-in backend URL. CI runs it; use it locally after any build.
- Documentation of the required repository variable and the optional signing secrets.

## Non-scope

- Play Store or other store publishing, App Bundles (`.aab`), ProGuard/R8 shrinking (release
  keeps `isMinifyEnabled = false` as before).
- Hosting the APK anywhere other than the GitHub Release (the chart's frontend does not serve it).
- Backend API versioning or compatibility checks between APK and server.

## Control flow

### Gradle (`android/app/build.gradle.kts`)

1. `releaseVersionName` comes from `-PversionName` (default `0.1.0`), `releaseVersionCode` from
   `-PversionCode` (default `1`; a non-integer fails the configuration with a message).
2. `releaseSigningFromEnv()` reads `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` and returns one of two states:
   - `ReleaseSigning.Keystore` when all four are set and the file exists;
   - `ReleaseSigning.DebugKey` when none are set.
   Any other combination throws `GradleException` naming the missing variables, so a release can
   never be silently debug-signed because one secret was mistyped.
3. `signingConfigs.release` is created only in the `Keystore` state; `buildTypes.release` picks it
   or the built-in `debug` config through an exhaustive `when`.
4. When `:app:assembleRelease` is in the task graph a lifecycle log line states the version and
   which key is used.

### Workflow

```
tag v1.2.3 pushed (or manual dispatch)
  -> meta:    version = tag without "v" (dispatch: 0.0.0-<sha7>), code = run number,
              url = input api_base_url || vars.ANDROID_API_BASE_URL (fail if empty or not http(s)),
              alerts_url = vars.ANDROID_STATUS_ALERTS_URL (optional; fail unless empty, none or http(s))
  -> signing: secret ANDROID_KEYSTORE_BASE64 present ? decode to $RUNNER_TEMP/release.jks,
              export ANDROID_KEYSTORE_FILE, mode=keystore : mode=debug
  -> JDK 21 (temurin) + gradle/actions cache
  -> ./gradlew test :app:assembleRelease -PversionName -PversionCode -PapiBaseUrl -PstatusAlertsUrl
  -> rename to lyrics-canvas-<version>.apk, verify_release_apk.sh <apk> <version> <code> <mode> --api-base-url <url>
  -> sha256 sidecar, upload as workflow artifact
  -> on tags only: softprops/action-gh-release attaches both files, generated notes + a body that
     records the backend URL and the signing mode
```

`versionCode` is the workflow run number: it is monotonic across runs, which is what Android
needs for upgrades. A debug-signed APK cannot upgrade a keystore-signed install (and vice versa);
the release body says which one it is.

### Verification script

`verify_release_apk.sh` uses `aapt2 dump badging` for package / `versionName` / `versionCode`,
`apksigner verify --print-certs` for the signature (debug key has `CN=Android Debug`), and a
string search over the `classes*.dex` files for the `BuildConfig.API_BASE_URL` constant. It exits
non-zero on the first mismatch and prints an `OK:` line with the resolved values on success.

## Configuration

| Where | Name | Purpose |
|-------|------|---------|
| Repository variable | `ANDROID_API_BASE_URL` | Backend URL baked into the APK. Set to `http://100.64.0.10:8000` (the tailnet address the hand-built APK used); change it when the backend moves, e.g. to the Kubernetes NodePort or an ingress host. |
| Repository variable (optional) | `ANDROID_STATUS_ALERTS_URL` | Cluster alerts URL for the status banner ([android_health_banner.md](./android_health_banner.md)). Empty derives `<ANDROID_API_BASE_URL>/_status/alerts`, which only exists when the API base is the frontend nginx origin; with the backend-direct `http://100.64.0.10:8000` set it to the nginx origin's `http://<host>:32443/_status/alerts`, or `none` to turn alerts off. |
| Secret (optional) | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| Secret (optional) | `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Keystore and key credentials |

Creating a release keystore once (keep it; losing it means users must uninstall to upgrade):

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias lyrics-canvas \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Lyrics Canvas"
gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 release.jks)
gh secret set ANDROID_KEYSTORE_PASSWORD   # prompts
gh secret set ANDROID_KEY_ALIAS --body lyrics-canvas
gh secret set ANDROID_KEY_PASSWORD        # prompts
```

Cutting a release:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

Local equivalent of what CI does:

```bash
cd android
ANDROID_KEYSTORE_FILE=... ANDROID_KEYSTORE_PASSWORD=... ANDROID_KEY_ALIAS=... ANDROID_KEY_PASSWORD=... \
  ./gradlew test :app:assembleRelease -PversionName=0.2.0 -PversionCode=42 -PapiBaseUrl=http://host:8000
scripts/verify_release_apk.sh app/build/outputs/apk/release/app-release.apk 0.2.0 42 keystore --api-base-url http://host:8000
```

## Files

| File | Role |
|------|------|
| `android/app/build.gradle.kts` | Version properties, `ReleaseSigning` sealed interface, `releaseSigningFromEnv()`, signing config and build type wiring, release log line |
| `.github/workflows/android-release.yml` | The release pipeline (tags `v*`, `workflow_dispatch`) |
| `android/scripts/verify_release_apk.sh` | APK assertions used by CI and locally |
| `docs/features/android_app.md` | App documentation; points here for releases |

## Invariants and constraints

- Signing is all-or-nothing: four variables set and the file present, or none set. Anything else
  fails configuration. Tests: partial env, missing file and non-integer `versionCode` each fail
  with the documented message; a build with no signing env verifies as `debug`, one with a
  keystore verifies as `keystore`.
- `versionCode` must increase between releases that users upgrade between; the run number
  guarantees it as long as releases are built by this workflow.
- `VITE`-style baked configuration: the backend URL is a compile-time constant, so changing the
  backend address needs a new APK.
- Pinned action versions (`actions/checkout@v4.2.2`, `actions/setup-java@v4.7.1`,
  `gradle/actions/setup-gradle@v4.4.1`, `actions/upload-artifact@v4.6.2`,
  `softprops/action-gh-release@v2.3.2`) were all verified to exist as tags.
- The workflow needs `contents: write` (declared) to create the Release; nothing else.
