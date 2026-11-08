# LibreRead

Read YouTube as detailed articles. Browse with LibreTube's familiar interface, select a video, and read without starting video or audio playback.

**[Download for Android](https://github.com/jschof1/libreread/releases/latest)** · [Install](docs/install.md) · [Run the backend](backend/README.md) · [Privacy](PRIVACY_POLICY.md)

LibreRead is a configurable client. Article generation requires your own compatible HTTPS backend and device credential. The public APK includes neither a service credential nor a hosted AI account. You do not sign into Google.

## Read your way

- Detailed articles by default, with separate writing style and length controls.
- Original transcripts, source labels and the original video reference alongside each article.
- Offline saved reading, restored position, adjustable text size and reading width.
- Caption-first generation, with labelled AI transcription when captions are unavailable.
- Progress, cancellation, retry and recovery. A failed rewrite keeps the previous article.

## Get started

1. Install the release APK on Android 8.0 or newer.
2. Run the included backend or obtain access to a compatible service.
3. Open **Settings → LibreRead reader** and enter its HTTPS URL and device credential.
4. Find a video, acknowledge AI processing and read the article.

The first-use screen explains missing setup before making article-service requests. Saved articles open without a configured service.

LibreRead retains LibreTube's local subscriptions, import, channels, search and playlist model. Google login and the exact personalised YouTube Home feed are unavailable.

## Build

Use Java 17 and Android SDK 36. Keep Gradle workers at two. Configure your signing identity with `keystore.properties`, or with `LIBREREAD_KEYSTORE_PATH`, `LIBREREAD_KEYSTORE_PASSWORD`, `LIBREREAD_KEY_ALIAS` and `LIBREREAD_KEY_PASSWORD`.

```sh
./gradlew :app:assembleRelease -Plibreread.testBuildType=release --max-workers=2
```

The service URL and credential default to empty. Configure them on the device. Provider keys belong on your backend, never in an APK.

CI verifies the signature, package, version and non-debuggable flag, then installs the exact release and instrumentation APKs on Android. It runs the standard AndroidJUnitRunner without recompiling while the emulator is active. Pull requests run unit and backend tests without signing secrets.

## Limits and storage

AI recognition and rewriting can contain errors. Compare articles with the source transcript when details matter. Private, restricted, unavailable or oversized videos may not convert. Visual-only information can be missing from captions.

Saved articles and credentials are excluded from Android backup. Uninstalling the app or losing the device loses those local records. The earlier LibreRead Debug is a separate installation; saved articles are not migrated automatically.

[Verification](docs/verification.md) records the exact tested artifact and remaining handset limitations.

## Licence and attribution

LibreRead is an independent derivative of [LibreTube](https://github.com/libre-tube/LibreTube) v32.1, upstream commit `6a861f4282c3de73b840793365d8ac7114b5a80f`. It retains the [GPL-3.0 licence](LICENSE), copyright notices, credits and [original README](docs/upstream-readme.md). It is not affiliated with YouTube, Google or LibreTube.
