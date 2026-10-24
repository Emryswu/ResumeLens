import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createCollectorService } from "../src/server.mjs";

async function setup(t) {
  const dataDir = await mkdtemp(join(tmpdir(), "resumelens-collector-test-"));
  const tabs = new Map();
  const opened = [];
  const delays = [];
  let launches = 0;
  const service = await createCollectorService({ token: "test-token", dataDir,
    sleep: async (ms) => delays.push(ms),
    browserFactory: async () => {
      launches++;
      return {
        async hasBossPermission() { return true; },
        async armApiCapture() {}, async disarmApiCapture() {}, async close() {},
        async openTab(url, conversationId) { if (!tabs.has(conversationId)) tabs.set(conversationId, tabs.size + 1); opened.push({ url, id: tabs.get(conversationId) }); return tabs.get(conversationId); },
        async waitForLoad() { return { url: "https://www.zhipin.com/web/geek/job" }; },
        async readPage() { return { state: { kind: "ready", cardCount: 1 }, jobs: [{ sourceJobId: "one", captureSource: "dom" }], skippedWithoutId: 0 }; },
        async showProgress() {},
      };
    },
  });
  await new Promise((resolve) => service.server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${service.server.address().port}`;
  const request = async (path, body, token = "test-token") => fetch(base + path, {
    method: body ? "POST" : "GET", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  t.after(async () => { await service.close(); await rm(dataDir, { recursive: true, force: true }); });
  const command = (id, conversationId = "42") => ({ taskId: `test_${id}`, userId: 1, conversationId, keyword: "Java", city: "深圳", maxPages: 1, pageAllowance: 1 });
  async function run(id, conversationId = "42") {
    const started = await (await request("/tasks", command(id, conversationId))).json();
    if (started.type === "rejected") return started;
    for (let attempt = 0; attempt < 100; attempt++) {
      const result = await (await request(`/tasks/test_${id}`)).json();
      if (result.task.status !== "running") return result;
      await new Promise((resolve) => setImmediate(resolve));
    }
    assert.fail("task did not finish");
  }
  return { request, run, command, opened, delays, get launches() { return launches; } };
}

test("HTTP service authenticates and validates the budget before opening a browser", async (t) => {
  const service = await setup(t);
  assert.equal((await service.request("/tasks", service.command(1), "wrong")).status, 401);
  assert.equal((await service.request("/tasks", { ...service.command(1), pageAllowance: 0 })).status, 400);
  assert.equal(service.launches, 0);
});

test("HTTP accepts the backend's Shanghai budget date in a UTC runtime and rejects yesterday", async (t) => {
  const previousZone = process.env.TZ;
  process.env.TZ = "UTC";
  t.after(() => { if (previousZone === undefined) delete process.env.TZ; else process.env.TZ = previousZone; });
  t.mock.timers.enable({ apis: ["Date"], now: new Date("2026-10-24T17:30:00Z") });
  assert.equal(new Date().getDate(), 24);
  const service = await setup(t);
  const stale = await (await service.request("/tasks", { ...service.command("stale"), budgetDate: "2026-10-24" })).json();
  assert.equal(stale.type, "rejected");
  assert.equal(stale.code, "DAILY_LIMIT");
  assert.equal(service.opened.length, 0);
  const started = await (await service.request("/tasks", { ...service.command("today"), budgetDate: "2026-10-25" })).json();
  assert.equal(started.type, "accepted");
  let result;
  for (let attempt = 0; attempt < 100; attempt++) {
    result = await (await service.request("/tasks/test_today")).json();
    if (result.task.status !== "running") break;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.equal(result.task.status, "completed");
  assert.equal(result.task.pagesLoaded, 1);
  assert.equal(service.opened.length, 1);
});

test("HTTP tasks reuse one conversation tab across rounds and keep the shared runner's logs and pacing", async (t) => {
  const service = await setup(t);
  const first = await service.run(1);
  assert.equal(first.task.finishReason, "MAX_PAGES");
  assert.deepEqual(first.task.pageLog, [{ page: 1, api: 0, dom: 1 }]);
  assert.equal(first.task.pagesLoaded, 1);
  await service.run(2);
  await service.run(3, "43");
  assert.deepEqual(service.opened.map((tab) => tab.id), [1, 1, 2]);
  assert.ok(service.delays.length >= 2);
  assert.ok(service.delays.every((ms) => ms >= 3000 && ms <= 7000));
});

test("the independent browser also enforces the daily page cap across tasks", async (t) => {
  const service = await setup(t);
  for (let page = 0; page < 30; page++) assert.equal((await service.run(page)).type, "result");
  assert.equal((await service.run(31)).code, "DAILY_LIMIT");
  assert.equal(service.opened.length, 30);
});
