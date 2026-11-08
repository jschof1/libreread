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
assert hashlib.sha256((folder / 'app-release-androidTest.apk').read_bytes()).hexdigest() == metadata['instrumentationApkSha256']
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
remote_command=$(python3 - "${arguments[@]}" <<'PYQUOTE'
import shlex, sys
print(shlex.join(['am', 'instrument', *sys.argv[1:], 'net.aspectstudio.libreread.test/androidx.test.runner.AndroidJUnitRunner']))
PYQUOTE
)
set +e
adb shell "$remote_command" > "$output/instrumentation.txt" 2>&1
runner_status=$?
set -e
adb logcat -d > "$output/logcat.txt"
python3 - "$output" <<'PYREDACT'
import os, pathlib, sys
for name in ['instrumentation.txt', 'logcat.txt']:
    path = pathlib.Path(sys.argv[1]) / name
    text = path.read_text(errors='replace')
    for key in ['LIBREREAD_TEST_TOKEN', 'LIBREREAD_TEST_URL']:
        value = os.environ.get(key)
        if value:
            text = text.replace(value, '[REDACTED]')
    path.write_text(text)
PYREDACT
cat "$output/instrumentation.txt"
adb pull /sdcard/Android/data/net.aspectstudio.libreread/files/test-artifacts "$output/screenshots" || true
if [[ "$runner_status" -ne 0 ]]; then
  exit "$runner_status"
fi
python3 scripts/check-instrumentation.py "$output/instrumentation.txt" "$live"
find "$output/screenshots" -type f -name '*.png' -print -quit | grep -q .
