import assert from "node:assert/strict";
import test from "node:test";

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
