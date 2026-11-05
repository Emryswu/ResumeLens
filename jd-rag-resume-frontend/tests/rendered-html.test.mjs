import assert from "node:assert/strict";
import test from "node:test";
import { spawn } from "node:child_process";
import { createServer, request } from "node:http";
import { once } from "node:events";
import { createInterface } from "node:readline";

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  return worker.fetch(new Request(`http://localhost${path}`, { headers: { accept: "text/html" } }), {
    ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) },
  }, { waitUntil() {}, passThroughOnException() {} });
}

test("server-renders the ResumeLens product shell", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /<title>ResumeLens · RAG 简历匹配<\/title>/);
  assert.match(html, /ResumeLens/);
  assert.match(html, /用 AI 看懂你的简历/);
  assert.match(html, /src="\/brand\/login-landscape\.webp"/);
  assert.doesNotMatch(html, /Your site is taking shape|codex-preview|react-loading-skeleton/);
});

test("the workbench has its own route and also signs in a visitor without a session", async () => {
  const response = await render("/workspace");
  assert.equal(response.status, 200);
  assert.match(await response.text(), /用 AI 看懂你的简历/);
});

test("production BFF enforces limits and IP trust on the raw HTTP connection", { timeout: 30_000 }, async (t) => {
  const calls = [];
  const upstream = createServer((req, res) => {
    calls.push(req.headers["x-bff-client-ip"]);
    res.end("{}");
  });
  upstream.listen(0, "127.0.0.1");
  await once(upstream, "listening");
  t.after(() => { upstream.closeAllConnections(); upstream.close(); });
  const child = spawn(process.execPath, ["--experimental-strip-types", "scripts/start.mjs", "--port", "0", "--hostname", "127.0.0.1"], {
    env: { ...process.env, TRUSTED_PROXIES: "", BACKEND_API_URL: `http://127.0.0.1:${upstream.address().port}` },
    stdio: ["ignore", "pipe", "inherit"],
  });
  t.after(() => child.kill());
  let port;
  for await (const line of createInterface({ input: child.stdout })) {
    const ready = /Production server running at http:\/\/127\.0\.0\.1:(\d+)/.exec(line);
    if (ready) { port = Number(ready[1]); break; }
  }
  assert.ok(port, "production server started");
  function post(path, headers, chunks = [], finish = true) {
    return new Promise((resolve, reject) => {
      const req = request({ host: "127.0.0.1", port, path, method: "POST", headers }, res => {
        res.resume();
        resolve(res.statusCode);
      });
      req.on("error", error => reject(new Error(`POST ${path} (${chunks.length} chunks)`, { cause: error })));
      for (const chunk of chunks) req.write(chunk);
      if (finish) req.end();
      else req.flushHeaders();
    });
  }
  const login = "/api/backend/api/auth/login";
  assert.equal(await post(login, { "x-forwarded-for": "198.51.100.9", "x-bff-client-ip": "198.51.100.8" }), 200);
  assert.equal(await post(login, {}, [Buffer.from("{}")]), 200);
  assert.equal(await post("/api/%62ackend/api/auth/login", {}), 404);
  assert.equal(await post(login, { "content-length": 2_000_000 }, [], false), 413);
  assert.equal(await post(login, {}, [Buffer.alloc(600_000), Buffer.alloc(600_000)], false), 413);
  for (const dots of ["..", "%2e%2E", "%252e%252e"]) {
    assert.equal(await post(`/api/backend/api/${dots}/actuator/prometheus`, {}), 404);
  }
  assert.deepEqual(calls, ["127.0.0.1", "127.0.0.1"]);
});
