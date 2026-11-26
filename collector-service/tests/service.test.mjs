import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, rmdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import runnerModule from "../../browser-extension/src/collect-runner.js";
import { createCollectorService } from "../src/server.mjs";

const { KEY_PREFIX } = runnerModule;

async function setup(t, { launch = () => {}, close = () => {} } = {}) {
  const dataDir = await mkdtemp(join(tmpdir(), "resumelens-collector-test-"));
  const tabs = new Map();
  const opened = [];
  const delays = [];
  let launches = 0;
  const service = await createCollectorService({ token: "test-token", dataDir,
    sleep: async (ms) => delays.push(ms),
    browserFactory: async ({ profileDir }) => {
      const browserId = ++launches;
      await launch(profileDir);
      return {
        async hasBossPermission() { return true; },
        async armApiCapture() {}, async disarmApiCapture() {},
        async close() { await close(profileDir, browserId); },
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
  return { request, run, command, opened, delays, dataDir, close: service.close,
    get launches() { return launches; } };
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

test("clears cached runner promise on browserFactory failure so subsequent requests can retry", async (t) => {
  const dataDir = await mkdtemp(join(tmpdir(), "resumelens-collector-retry-"));
  let attempts = 0;
  const service = await createCollectorService({
    token: "test-token",
    dataDir,
    sleep: async () => {},
    browserFactory: async () => {
      attempts++;
      if (attempts === 1) throw new Error("transient launch failure");
      return {
        async hasBossPermission() { return true; },
        async armApiCapture() {}, async disarmApiCapture() {}, async close() {},
        async openTab() { return 1; },
        async waitForLoad() { return { url: "https://www.zhipin.com/web/geek/job" }; },
        async readPage() { return { state: { kind: "ready", cardCount: 1 }, jobs: [{ sourceJobId: "retry-1", captureSource: "dom" }], skippedWithoutId: 0 }; },
        async showProgress() {},
      };
    },
  });
  await new Promise((resolve) => service.server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${service.server.address().port}`;
  t.after(async () => { await service.close(); await rm(dataDir, { recursive: true, force: true }); });

  const payload = { taskId: "retry_1", userId: 1, conversationId: "42", keyword: "Java", city: "深圳", maxPages: 1, pageAllowance: 1 };
  const first = await fetch(`${base}/tasks`, {
    method: "POST",
    headers: { Authorization: "Bearer test-token", "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  assert.equal(first.status, 500);
  assert.equal(attempts, 1);

  const second = await fetch(`${base}/tasks`, {
    method: "POST",
    headers: { Authorization: "Bearer test-token", "Content-Type": "application/json" },
    body: JSON.stringify({ ...payload, taskId: "retry_2" }),
  });
  assert.equal(second.status, 200);
  assert.equal((await second.json()).type, "accepted");
  let status;
  for (let attempt = 0; attempt < 100; attempt++) {
    status = await (await fetch(`${base}/tasks/retry_2`, {
      headers: { Authorization: "Bearer test-token" },
    })).json();
    if (status.task.status !== "running") break;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.equal(status.task.status, "completed");
  assert.equal(attempts, 2);
});

test("releases the browser profile on stale-task recovery failure and retries with a new browser", async (t) => {
  const occupiedProfiles = new Set();
  const closed = [];
  const service = await setup(t, {
    launch(profileDir) {
      assert.equal(occupiedProfiles.has(profileDir), false, "the profile is still in use");
      occupiedProfiles.add(profileDir);
    },
    close(profileDir, browserId) {
      assert.equal(occupiedProfiles.delete(profileDir), true, "the browser was closed twice");
      closed.push(browserId);
    },
  });
  const stateFile = join(service.dataDir, "state", "1.json");
  await mkdir(stateFile + ".tmp", { recursive: true });
  await writeFile(stateFile, JSON.stringify({
    [KEY_PREFIX + "stale"]: { taskId: "stale", status: "running", epoch: "previous" },
  }));

  const failed = await service.request("/tasks", service.command("recover"));
  assert.equal(failed.status, 500);
  assert.equal(service.launches, 1);
  assert.deepEqual(closed, [1]);

  await rmdir(stateFile + ".tmp");
  const retried = await service.run("recover");
  assert.equal(retried.task.status, "completed");
  assert.equal(service.launches, 2);

  await service.close();
  assert.deepEqual(closed, [1, 2]);
});

test("shutdown closes browsers whose launch was already in flight", async (t) => {
  const launching = Promise.withResolvers();
  const finishLaunch = Promise.withResolvers();
  const closed = [];
  const service = await setup(t, {
    launch() {
      launching.resolve();
      return finishLaunch.promise;
    },
    close(profileDir, browserId) { closed.push(browserId); },
  });

  const pending = service.request("/tasks", { ...service.command("shutdown"), budgetDate: "2000-01-01" });
  await launching.promise;
  const shutdown = service.close();
  finishLaunch.resolve();

  assert.equal((await (await pending).json()).type, "rejected");
  await shutdown;
  assert.deepEqual(closed, [1]);
});

test("rejects multi-byte UTF-8 bodies exceeding 8192 bytes with 413 even when character length is under 8192", async (t) => {
  const service = await setup(t);
  const keyword = "中".repeat(3000); // 3000 chars (< 8192) -> 9000 UTF-8 bytes (> 8192)
  assert.ok(keyword.length < 8192);
  assert.ok(Buffer.byteLength(keyword, "utf8") > 8192);
  const response = await service.request("/tasks", {
    taskId: "utf8_big",
    userId: 1,
    conversationId: "42",
    keyword,
    city: "深圳",
    maxPages: 1,
    pageAllowance: 1,
  });
  assert.equal(response.status, 413);
  assert.equal((await response.json()).error, "request too large");
  assert.equal(service.launches, 0);
});
