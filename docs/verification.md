# Verification

Release verification is in progress. The public APK is built with an empty service URL and device credential. It requires setup before article generation.

CI checks the signed, non-debuggable release and exercises the exact artifact on Android 15. The public suite intentionally skips the opt-in live provider test. Live processing is checked separately with access supplied only in a private test environment.

The final release notes and evidence identify the tested commit, APK digest, test results and remaining limitations. No physical-handset installation or personal acceptance is claimed.
