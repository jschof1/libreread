#!/usr/bin/env bash
set -euo pipefail

apk=app/build/outputs/apk/release/app-release.apk
test -z "${LIBREREAD_API_URL:-}"
test -z "${LIBREREAD_DEVICE_TOKEN:-}"
: "${ANDROID_HOME:?Android SDK is required}"
: "${LIBREREAD_KEYSTORE_PATH:?The retained signing identity is required}"
signer_tool=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -1)
analyzer=$(find "$ANDROID_HOME/cmdline-tools" -name apkanalyzer -type f | sort -V | tail -1)

expected=$(keytool -exportcert -keystore "$LIBREREAD_KEYSTORE_PATH" -storepass "${LIBREREAD_KEYSTORE_PASSWORD:-android}" -alias "${LIBREREAD_KEY_ALIAS:-androiddebugkey}" | sha256sum | cut -d ' ' -f 1)
"$signer_tool" verify --print-certs "$apk" > "${apk%/*}/signing-certificate.txt"
actual=$(sed -n 's/.* certificate SHA-256 digest: //p' "${apk%/*}/signing-certificate.txt" | sort -u)
test "$actual" = "$expected"
key_hash=$(sha256sum "$LIBREREAD_KEYSTORE_PATH" | cut -d ' ' -f 1)
printf 'Signing key file SHA-256: %s\n' "$key_hash" >> "${apk%/*}/signing-certificate.txt"

test "$("$analyzer" manifest debuggable "$apk")" = false
app_id=$("$analyzer" manifest application-id "$apk")
version=$("$analyzer" manifest version-name "$apk")
version_code=$("$analyzer" manifest version-code "$apk")
min_sdk=$("$analyzer" manifest min-sdk "$apk")
test "$app_id" = net.aspectstudio.libreread
test "$version" = 1.0.0
test "$version_code" = 73

python3 - "$apk" "$app_id" "$version" "$version_code" "$min_sdk" "$actual" "$key_hash" <<'PY'
import hashlib
import json
import pathlib
import sys

apk, app_id, version, version_code, min_sdk, certificate, key_hash = sys.argv[1:]
path = pathlib.Path(apk)
metadata = {
    "applicationId": app_id,
    "versionName": version,
    "versionCode": int(version_code),
    "minSdk": int(min_sdk),
    "debuggable": False,
    "signatureVerified": True,
    "embeddedServiceConfigured": False,
    "certificateSha256": certificate,
    "keystoreFileSha256": key_hash,
    "apkSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    "apkBytes": path.stat().st_size,
}
path.with_name("release-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps(metadata, indent=2))
PY
