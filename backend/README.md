# Run the LibreRead backend

This dependency-free Node service supplies the authenticated API used by the Android app. It keeps provider credentials on your server.

## Configure and start

Use Node 22 or newer. Supply these environment variables through your host's secret manager or protected service environment:

| Variable | Purpose |
| --- | --- |
| `OPENROUTER_API_KEY` | Your OpenRouter provider credential. |
| `DEVICE_TOKEN` | A random access credential of at least 24 characters. Enter the same value in the app. |
| `GEMINI_MODEL` | Optional article model. The default is `google/gemini-3.8-flash`. |
| `TRANSCRIPT_MODEL` | Optional video transcription model. The default is `google/gemini-3.8-flash`. |
| `PORT` | Optional loopback port, default 3172. |

Run from the repository root:

```sh
node backend/server.mjs
```

The listener binds to `127.0.0.1`. Put it behind an HTTPS reverse proxy. Enter that HTTPS address and your `DEVICE_TOKEN` in the Android reader settings. Keep `OPENROUTER_API_KEY` on the server. AI processing consumes your provider account's credits.

The portable handler in `public/_worker.js` can also run on Cloudflare Workers using the supplied alternative Wrangler configuration. Adapt the deployment configuration and secrets to your account.

## API reference

`GET /health` checks configuration without contacting the provider.

Both POST routes require `Authorization: Bearer <device credential>` and JSON.

`POST /v1/transcripts` accepts `videoId`. It sends the canonical public YouTube URL to Gemini through OpenRouter's Google AI Studio route. The response includes `title`, `creator`, `transcript`, `language`, `source` (`ai-transcription`) and `model`.

`POST /v1/articles` accepts `videoId`, `title`, `creator`, `transcript`, `style` and `detail`. Detail is `full`, `balanced` or `brief`, default `full`. Style is limited to 2,000 characters and the transcript to 180,000 characters. Oversized sources fail without truncation.

The response includes `article` and `model`. The article contains `title`, `sections` (heading and paragraph arrays) and `visualNotes`. Render these values as text, never executable HTML. Errors contain user-safe `error` text and a stable `code`.

## Operation and limits

The service bounds uploaded bytes, provider output and network waiting. Provider requests have a 90-second deadline. Invalid or incomplete provider output fails rather than being silently shortened. These checks do not prove factual accuracy.

The service stores no articles or transcripts and does not intentionally log request content or credentials. Your proxy and AI providers process requests under their own policies. The process-local limiter permits two concurrent requests and 60 requests per hour. It is not a durable global quota or a billing limit.

Run tests without installing dependencies:

```sh
npm test --prefix backend
```
