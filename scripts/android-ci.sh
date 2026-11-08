#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-/tmp/libreread-built-release}
output=app/build/outputs/release-instrumentation
mkdir -p "$output"
python3 - "$artifacts" <<'PY'
import hashlib, json, pathlib, sys
folder = pathlib.Path(sys.argv[1])
metadata = json.loads((folder / 'release-metadata.json').read_text())
assert hashlib.sha256((folder / 'app-release.apk').read_bytes()).hexdigest() == metadata['apkSha256']
assert metadata['debuggable'] is False and metadata['signatureVerified'] is True
PY
adb install -r "$artifacts/app-release.apk"
adb install -r "$artifacts/app-release-androidTest.apk"
adb logcat -c
live=${LIBREREAD_LIVE_TEST:-false}
arguments=(-w -r -e libreread.live "$live" -e libreread.videoId jNQXAC9IVRw)
if [[ "$live" == true ]]; then
  : "${LIBREREAD_TEST_URL:?Private live test service is required}"
  : "${LIBREREAD_TEST_TOKEN:?Private live test credential is required}"
  arguments+=(-e libreread.serviceUrl "$LIBREREAD_TEST_URL" -e libreread.deviceToken "$LIBREREAD_TEST_TOKEN")
fi
set +e
adb shell am instrument "${arguments[@]}" net.aspectstudio.libreread.test/androidx.test.runner.AndroidJUnitRunner > "$output/instrumentation.txt" 2>&1
runner_status=$?
set -e
cat "$output/instrumentation.txt"
adb logcat -d > "$output/logcat.txt"
adb pull /sdcard/Android/data/net.aspectstudio.libreread/files/test-artifacts "$output/screenshots" || true
if [[ "$runner_status" -ne 0 ]]; then
  exit "$runner_status"
fi
python3 scripts/check-instrumentation.py "$output/instrumentation.txt" "$live"
find "$output/screenshots" -type f -name '*.png' -print -quit | grep -q .
