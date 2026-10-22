#!/usr/bin/env bash
# Assert a built APK carries the expected version and signer.
#
#   android/scripts/verify_release_apk.sh <apk> <versionName> <versionCode> <debug|keystore> [--api-base-url URL]
#
# Uses aapt2 and apksigner from the newest build-tools in $ANDROID_HOME (or sdk.dir in
# android/local.properties). Exit 1 with a message naming the first mismatch.
set -euo pipefail

usage() { sed -n '2,7p' "$0" >&2; exit 2; }

apk=${1:-}; want_name=${2:-}; want_code=${3:-}; want_signer=${4:-}
[ -n "$apk" ] && [ -n "$want_name" ] && [ -n "$want_code" ] && [ -n "$want_signer" ] || usage
shift 4
want_url=""
while [ $# -gt 0 ]; do
  case "$1" in
    --api-base-url) want_url=${2:-}; shift 2 ;;
    *) usage ;;
  esac
done
case "$want_signer" in debug|keystore) ;; *) echo "signer must be debug or keystore, got '$want_signer'" >&2; exit 2 ;; esac

here=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] && [ -f "$here/local.properties" ]; then
  sdk=$(sed -n 's/^sdk.dir=//p' "$here/local.properties")
fi
[ -d "$sdk/build-tools" ] || { echo "no Android build-tools found (set ANDROID_HOME)" >&2; exit 2; }
tools=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)
aapt2="${tools}aapt2"; apksigner="${tools}apksigner"
[ -x "$aapt2" ] && [ -x "$apksigner" ] || { echo "aapt2/apksigner missing in $tools" >&2; exit 2; }
[ -f "$apk" ] || { echo "apk not found: $apk" >&2; exit 1; }

fail() { echo "FAIL: $*" >&2; exit 1; }

badging=$("$aapt2" dump badging "$apk")
got_name=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)
got_code=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)
got_pkg=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)
[ "$got_pkg" = "dev.nymph.lyrics" ] || fail "package is '$got_pkg'"
[ "$got_name" = "$want_name" ] || fail "versionName is '$got_name', want '$want_name'"
[ "$got_code" = "$want_code" ] || fail "versionCode is '$got_code', want '$want_code'"

# apksigner on recent JDKs prints native-access warnings on stderr; only its exit status and stdout matter.
"$apksigner" verify "$apk" >/dev/null 2>&1 || fail "apksigner verify failed (unsigned or broken signature)"
certs=$("$apksigner" verify --print-certs "$apk" 2>/dev/null)
dn=$(sed -n 's/^Signer #1 certificate DN: //p' <<<"$certs" | head -1)
case "$want_signer" in
  debug)    [[ "$dn" == *"CN=Android Debug"* ]] || fail "expected the debug key, signer DN is '$dn'" ;;
  keystore) [[ "$dn" != *"CN=Android Debug"* ]] || fail "expected a release keystore, but the APK is debug-signed" ;;
esac

if [ -n "$want_url" ]; then
  # BuildConfig.API_BASE_URL is a compile-time constant; it survives as a string in one of the dex
  # files. grep -c reads all input (grep -q would SIGPIPE unzip under pipefail).
  hits=$(unzip -p "$apk" 'classes*.dex' | grep -acF -- "$want_url" || true)
  [ "${hits:-0}" -gt 0 ] || fail "API_BASE_URL '$want_url' not found in the dex"
fi

echo "OK: $apk versionName=$got_name versionCode=$got_code signer=$want_signer (${dn:-?})${want_url:+ apiBaseUrl=$want_url}"
