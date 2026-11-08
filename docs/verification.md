# LibreRead 1.0.0 verification

The public APK is signed, non-debuggable and configurable. It contains neither the personal article-service credential nor its hostname. The source defaults for both values are empty.

## Exact release artifact

- Build source: `89652e2e33c6fcef7da2588509704542277c62df`.
- [Public build and test run](https://github.com/jschof1/libreread/actions/runs/42502416854): fully green.
- APK SHA-256: `420c886a32442842ac8d4a2fd1e17369a8ac91487f0c016eaaca0127bd961e88`.
- Package `net.aspectstudio.libreread`, version 1.0.0, versionCode 73, minimum SDK 26, non-debuggable.
- The signing certificate is checked against the retained release identity. The app and instrumentation APK hashes are verified before installation.

The release tag adds only documentation and screenshots to the tested build source. App, backend, build tooling and workflows are unchanged. The downloadable APK remains the exact tested artifact.

## Results

All 56 JVM results, 27 backend tests and seven instrumentation-result parser regressions pass. Gradle may restore identical-input unit results from its cache.

The public Android 15 suite records 22 cases, with 21 passed and only the opt-in live provider case skipped. No personal access is supplied to public CI.

The exact same APK was then installed in private CI, configured only at runtime and exercised through all 22 cases with no failures or skips. This includes real public-video conversion, saved reading, transcript display, restored position, setup recovery and disabled-playback routing. Credentials and raw logs remain private. Collected private test artifacts were scanned for the credential after redaction.

The result parser rejects incomplete or duplicate results, failures, unexpected skips and missing process-completion markers. It does not substitute a successful build for a successful device run.

The finished live article and setup screenshots were inspected. Text, source labels, original URL and settings access are readable without horizontal clipping. [Machine-readable receipt](release-verification.json) records the APK and screenshot hashes.

## Review and limits

An independent cross-provider release review approved the bounded public configuration at 8/10. Its required deployment-template cleanup is applied. The test-APK hash check, runtime shell quoting, log redaction, signing-secret scope and parser crash/skip regressions are also applied. Upstream licence and attribution remain.

No physical handset installation or personal acceptance is claimed. AI transcription and rewriting can contain errors, and these checks do not establish universal source fidelity. Google login and the exact personalised YouTube Home feed are outside this version. The public APK requires a compatible HTTPS service and device credential; it includes no hosted AI account.
