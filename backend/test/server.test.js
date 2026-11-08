import assert from "node:assert/strict";
import http from "node:http";
import { test } from "node:test";
import { once } from "node:events";
import { spawn } from "node:child_process";
import { mkdtemp, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createLibreReadServer } from "../server.mjs";

const env = { DEVICE_TOKEN: "personal-device-test-token-123456789", OPENROUTER_API_KEY: "provider-test-credential" };

test("Node adapter starts through the release symlink used by systemd", async t => {
  const temporary = await mkdtemp(join(tmpdir(), "libreread-start-"));
  const link = join(temporary, "current");
  await symlink(resolve(fileURLToPath(new URL("..", import.meta.url))), link);
  const reservation = http.createServer();
  reservation.listen(0, "127.0.0.1");
  await once(reservation, "listening");
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const child = spawn(process.execPath, [join(link, "server.mjs")], {
    env: { ...process.env, ...env, PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(async () => {
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await once(child, "exit");
    }
    await rm(temporary, { recursive: true });
  });
  await Promise.race([
    once(child.stdout, "data"),
    once(child, "exit").then(() => { throw new Error("Service exited before listening"); }),
    new Promise((_, reject) => setTimeout(() => reject(new Error("Service did not start")), 5000).unref()),
  ]);
  const response = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, "ok");
});

async function listening(t, handler) {
  const server = createLibreReadServer({ env, handler });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => { server.closeAllConnections(); server.close(); });
  return `http://127.0.0.1:${server.address().port}`;
}

test("Node adapter serves the actual health and authentication handlers", async t => {
  const base = await listening(t);
  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 200);
  assert.equal((await health.json()).status, "ok");
  const denied = await fetch(`${base}/v1/articles`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  assert.equal(denied.status, 401);
  assert.equal((await denied.json()).code, "UNAUTHORIZED");
});

test("Node adapter transfers request bodies, headers, and response status", async t => {
  const base = await listening(t, { async fetch(request, configuration) {
    assert.equal(configuration.DEVICE_TOKEN, env.DEVICE_TOKEN);
    assert.equal(request.headers.get("authorization"), "Bearer test");
    assert.equal(request.method, "POST");
    assert.equal(new URL(request.url).pathname, "/v1/articles");
    assert.deepEqual(await request.json(), { transcript: "source text" });
    return new Response(JSON.stringify({ received: true }), { status: 201, headers: { "Content-Type": "application/json", "X-Result": "saved" } });
  } });
  const response = await fetch(`${base}/v1/articles`, { method: "POST", headers: { Authorization: "Bearer test", "Content-Type": "application/json" }, body: JSON.stringify({ transcript: "source text" }) });
  assert.equal(response.status, 201);
  assert.equal(response.headers.get("X-Result"), "saved");
  assert.deepEqual(await response.json(), { received: true });
});

test("Node adapter rejects declared oversized bodies before invoking the handler", async t => {
  let called = false;
  const base = await listening(t, { async fetch() { called = true; return new Response("unexpected"); } });
  const response = await new Promise((resolve, reject) => {
    const call = http.request(`${base}/v1/articles`, { method: "POST", headers: { "Content-Length": "1200001" } }, incoming => {
      const chunks = [];
      incoming.on("data", chunk => chunks.push(chunk));
      incoming.on("end", () => resolve({ status: incoming.statusCode, body: Buffer.concat(chunks).toString() }));
    });
    call.on("error", reject);
    call.end();
  });
  assert.equal(response.status, 413);
  assert.equal(JSON.parse(response.body).code, "REQUEST_TOO_LARGE");
  assert.equal(called, false);
});

test("Node adapter propagates a disconnected client to the handler signal", async t => {
  let observedAbort;
  const aborted = new Promise(resolve => { observedAbort = resolve; });
  const base = await listening(t, { async fetch(request) {
    await request.text();
    await new Promise(resolve => request.signal.addEventListener("abort", () => { observedAbort(); resolve(); }, { once: true }));
    return new Response("cancelled");
  } });
  const call = http.request(`${base}/v1/articles`, { method: "POST" });
  call.on("error", () => {});
  call.end("body");
  await new Promise(resolve => setTimeout(resolve, 20));
  call.destroy();
  await Promise.race([aborted, new Promise((_, reject) => setTimeout(() => reject(new Error("Abort did not propagate")), 1_000))]);
});

test("Node adapter bounds streamed bodies without Content-Length", async t => {
  const base = await listening(t, { async fetch(request) {
    await request.text();
    return new Response("unexpected");
  } });
  const response = await new Promise((resolve, reject) => {
    const call = http.request(`${base}/v1/articles`, { method: "POST", headers: { "Transfer-Encoding": "chunked" } }, incoming => {
      const chunks = [];
      incoming.on("data", chunk => chunks.push(chunk));
      incoming.on("end", () => resolve({ status: incoming.statusCode, body: Buffer.concat(chunks).toString() }));
    });
    call.on("error", reject);
    call.end("x".repeat(1_200_001));
  });
  assert.equal(response.status, 413);
  assert.equal(JSON.parse(response.body).code, "REQUEST_TOO_LARGE");
});

test("Node adapter does not expose handler exception details", async t => {
  const base = await listening(t, { async fetch() { throw new Error("private secret source content"); } });
  const response = await fetch(`${base}/health`);
  assert.equal(response.status, 500);
  const body = await response.text();
  assert.match(body, /INTERNAL_ERROR/);
  assert.doesNotMatch(body, /private secret source content/);
});
