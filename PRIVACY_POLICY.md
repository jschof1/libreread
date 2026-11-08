# LibreRead privacy policy

Updated 8 November 2026.

LibreRead is an independent Android reader derived from LibreTube. The public APK includes no article-service account or access credential.

## Browsing

Browsing uses LibreTube's content access model. Requests go directly to YouTube or to a Piped instance according to your settings. Those services can see requests that reach them. Google login is not supported.

Local subscriptions and playlists follow LibreTube's settings. Optional Piped accounts send the records needed for their enabled features to that instance. A third-party instance's privacy practices depend on its operator.

## Article processing

You choose the HTTPS article service and enter its device credential in reader settings. The app asks you to acknowledge AI processing before the first transfer. Generation sends the video identifier, title, creator, transcript, writing instructions and requested length to that service.

If captions are unavailable, the service can ask an AI provider to transcribe the public YouTube video. The app labels that source as AI transcription. AI transcripts and rewrites can contain errors.

The included backend uses OpenRouter and a selected model provider, initially Gemini through Google AI Studio. Your service operator and providers may process and retain request data under their own policies. LibreRead cannot promise the practices of a custom service. Choose a service you trust with the source and instructions you submit.

Google passwords, account sessions, subscriptions and browsing history are not part of the article-generation request.

## Saved reading and credentials

Articles, transcripts, source metadata, writing settings, reading position and the device credential are stored in the app's private device storage. Reopening a saved article does not require another AI request. Rewriting sends its saved transcript and current instructions again.

Articles, transcripts and reader credentials are excluded from Android backup and LibreTube's settings export. Local storage is not an off-device backup. Uninstalling or losing the device can remove saved reading.

Provider API keys belong on the backend. Do not enter a provider key as the app's device credential.

## Removing data

Delete saved articles in the app, or clear app storage in Android settings. These actions do not retract requests already processed by a service or provider. Piped account records require the relevant instance's controls.

The included backend stores no article or transcript content and does not intentionally log it. Hosting proxies and providers can retain operational information under their own policies.
