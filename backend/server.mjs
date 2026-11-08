import http from "node:http";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { pathToFileURL } from "node:url";
import { realpathSync } from "node:fs";
import worker from "./public/_worker.js";

const MAX_REQUEST_BYTES = 1_200_000;

function sendError(response, status, code, message) {
  if (response.destroyed) return;
  if (response.headersSent) {
    response.destroy();
    return;
  }
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Connection": "close",
  });
  response.end(JSON.stringify({ error: message, code }));
}

export function createLibreReadServer({ env = process.env, handler = worker } = {}) {
  const serviceEnv = {
    DEVICE_TOKEN: env.DEVICE_TOKEN,
    OPENROUTER_API_KEY: env.OPENROUTER_API_KEY,
    GEMINI_MODEL: env.GEMINI_MODEL,
    TRANSCRIPT_MODEL: env.TRANSCRIPT_MODEL,
  };
  const server = http.createServer({ headersTimeout: 10_000, requestTimeout: 15_000 }, async (incoming, outgoing) => {
    const controller = new AbortController();
    const abort = () => controller.abort();
    const close = () => { if (!outgoing.writableEnded) abort(); };
    incoming.once("aborted", abort);
    incoming.once("error", abort);
    outgoing.once("close", close);
    let bytes = 0;
    const count = chunk => {
      bytes += chunk.length;
      if (bytes > MAX_REQUEST_BYTES) {
        abort();
        incoming.pause();
        outgoing.once("finish", () => incoming.destroy());
        sendError(outgoing, 413, "REQUEST_TOO_LARGE", "The request exceeds the size limit.");
      }
    };
    incoming.on("data", count);
    try {
      const declared = incoming.headers["content-length"];
      if (declared !== undefined && Number(declared) > MAX_REQUEST_BYTES) {
        sendError(outgoing, 413, "REQUEST_TOO_LARGE", "The request exceeds the size limit.");
        return;
      }
      const headers = new Headers();
      for (const [name, value] of Object.entries(incoming.headers)) {
        if (value !== undefined) headers.set(name, Array.isArray(value) ? value.join(", ") : value);
      }
      const method = incoming.method || "GET";
      const hasBody = !["GET", "HEAD"].includes(method);
      const request = new Request(new URL(incoming.url || "/", "http://127.0.0.1"), {
        method,
        headers,
        signal: controller.signal,
        ...(hasBody ? { body: Readable.toWeb(incoming), duplex: "half" } : {}),
      });
      const response = await handler.fetch(request, serviceEnv);
      if (outgoing.destroyed || outgoing.writableEnded) {
        await response.body?.cancel();
        return;
      }
      outgoing.writeHead(response.status, Object.fromEntries(response.headers));
      if (!response.body || method === "HEAD") {
        await response.body?.cancel();
        outgoing.end();
      } else {
        await pipeline(Readable.fromWeb(response.body), outgoing);
      }
    } catch {
      sendError(outgoing, 500, "INTERNAL_ERROR", "The request could not be completed. Try again.");
    } finally {
      incoming.off("data", count);
      incoming.off("aborted", abort);
      incoming.off("error", abort);
      outgoing.off("close", close);
    }
  });
  server.keepAliveTimeout = 5_000;
  server.maxHeadersCount = 100;
  server.maxRequestsPerSocket = 100;
  server.on("clientError", (_, socket) => {
    if (!socket.destroyed) socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  const port = Number(process.env.PORT || 3172);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error("Invalid service port.");
  const server = createLibreReadServer();
  server.listen(port, "127.0.0.1", () => console.log("LibreRead API is listening on its configured loopback port."));
  const shutdown = () => {
    server.close(() => process.exit(0));
    server.closeIdleConnections();
    setTimeout(() => server.closeAllConnections(), 10_000).unref();
  };
  process.once("SIGTERM", shutdown);
  process.once("SIGINT", shutdown);
}
