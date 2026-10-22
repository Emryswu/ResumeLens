const assert = require("node:assert/strict");
const test = require("node:test");

const collectRunner = require("../src/collect-runner.js");
const search = require("../src/boss-search.js");

const SEARCH_URL = "https://www.zhipin.com/web/geek/job?query=Java&city=101280600";
const COMMAND = { taskId: "call_1", keyword: "Java 后端实习", city: "深圳", maxPages: 3 };

function memoryStorage() {
  const data = new Map();
  return {
    data,
    async get(key) { return data.has(key) ? structuredClone(data.get(key)) : undefined; },
    async set(key, value) { data.set(key, structuredClone(value)); },
    async remove(key) { data.delete(key); },
    async keys() { return [...data.keys()]; },
  };
}

function job(id) {
  return { title: `职位 ${id}`, companyName: "示例科技", description: "摘要", sourcePlatform: "BOSS", sourceJobId: id, sourceUrl: `https://www.zhipin.com/job_detail/${id}.html` };
}

function ready(ids) {
  return { state: { kind: "ready", cardCount: ids.length }, jobs: ids.map(job), skippedWithoutId: 0 };
}

/** A browser whose page steps come from a script; every call is recorded. */
function fakeBrowser({ permission = true, loadedUrl = SEARCH_URL, steps = [] } = {}) {
  const calls = [];
  const queue = [...steps];
  return {
    calls,
    async hasBossPermission() { calls.push(["permission"]); return permission; },
    async openTab(url) { calls.push(["open", url]); return 7; },
    async waitForLoad(tabId) { calls.push(["load", tabId]); return { url: loadedUrl }; },
    async readPage(tabId, operation, options) {
      calls.push([operation, options]);
      const next = queue.shift();
      if (!next) throw new Error("unexpected extra page step");
      return typeof next === "function" ? next() : next;
    },
  };
}

function setup(overrides = {}) {
  const storage = overrides.storage || memoryStorage();
  const delays = [];
  const browser = overrides.browser || fakeBrowser();
  const runner = collectRunner.create({
    browser,
    storage,
    random: overrides.random || (() => 0.5),
    sleep: overrides.sleep || (async (ms) => { delays.push(ms); }),
    now: () => 1_000,
    epoch: overrides.epoch || "worker-1",
  });
  return { runner, storage, browser, delays };
}

/** Lets queued work run until {@code done()} holds; fails instead of hanging when it never does. */
async function waitUntil(done) {
  for (let turn = 0; turn < 1_000; turn += 1) {
    if (done()) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail("condition never became true");
}

async function runToEnd(runner, command = COMMAND) {
  const events = [];
  runner.subscribe(command.taskId, (event) => events.push(event));
  const started = await runner.start(command);
  assert.equal(started.ok, true, JSON.stringify(started));
  await runner.whenSettled(command.taskId);
  return { events, task: await runner.status(command.taskId) };
}

test("reads up to maxPages with a random pause before every extra page", async () => {
  const browser = fakeBrowser({ steps: [ready(["a", "b"]), ready(["a", "b", "c"]), ready(["a", "b", "c", "d"])] });
  const { runner, delays } = setup({ browser });
  const { events, task } = await runToEnd(runner);

  assert.equal(task.status, "completed");
  assert.equal(task.finishReason, "MAX_PAGES");
  assert.equal(task.pagesRead, 3);
  assert.deepEqual(task.jobs.map((item) => item.sourceJobId), ["a", "b", "c", "d"]);
  assert.equal(browser.calls.find(([name]) => name === "open")[1], search.buildSearchUrl(COMMAND));
  assert.deepEqual(delays, [search.pageDelayMs(() => 0.5), search.pageDelayMs(() => 0.5)]);
  assert.deepEqual(browser.calls.filter(([name]) => name === "scrollAndWait").map(([, options]) => options.previousCount), [2, 3]);
  assert.ok(events.some((event) => event.type === "progress" && event.phase === "waiting" && event.page === 2));
  assert.equal(events.at(-1).type, "result");
  assert.equal("epoch" in events.at(-1).task, false);
});

test("a captcha on a later page stops at once, keeps earlier jobs and does not retry", async () => {
  const browser = fakeBrowser({ steps: [ready(["a", "b"]), { state: { kind: "blocked", code: "CAPTCHA" }, jobs: [] }] });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner);

  assert.equal(task.status, "stopped");
  assert.equal(task.stopReason, "CAPTCHA");
  assert.match(task.stopMessage, /安全验证/);
  assert.deepEqual(task.jobs.map((item) => item.sourceJobId), ["a", "b"]);
  assert.equal(browser.calls.filter(([name]) => name === "scrollAndWait").length, 1);
});

test("without BOSS permission nothing is opened", async () => {
  const browser = fakeBrowser({ permission: false });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner);

  assert.equal(task.stopReason, "PERMISSION_REQUIRED");
  assert.equal(browser.calls.some(([name]) => name === "open"), false);
});

test("a redirect to the login page is reported as not logged in", async () => {
  const browser = fakeBrowser({ loadedUrl: "https://www.zhipin.com/web/user/?ka=header-login" });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner);

  assert.equal(task.stopReason, "BOSS_NOT_LOGGED_IN");
  assert.equal(browser.calls.some(([name]) => name === "read"), false);
});

test("an unrecognized page is a layout change, an empty search is a normal finish", async () => {
  const changed = setup({ browser: fakeBrowser({ steps: [{ state: { kind: "unknown" }, jobs: [] }] }) });
  assert.equal((await runToEnd(changed.runner)).task.stopReason, "STRUCTURE_CHANGED");

  const empty = setup({ browser: fakeBrowser({ steps: [{ state: { kind: "empty" }, jobs: [] }] }) });
  const { task } = await runToEnd(empty.runner);
  assert.equal(task.status, "completed");
  assert.equal(task.finishReason, "NO_RESULTS");
});

test("a list that stops growing ends early instead of scrolling on", async () => {
  const browser = fakeBrowser({ steps: [ready(["a"]), ready(["a"])] });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner);

  assert.equal(task.finishReason, "NO_MORE_RESULTS");
  assert.equal(task.pagesRead, 1);
});

test("the job cap is enforced across pages", async () => {
  const ids = (count, offset = 0) => Array.from({ length: count }, (_, index) => `j${offset + index}`);
  const browser = fakeBrowser({ steps: [ready(ids(30)), ready(ids(60))] });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner);

  assert.equal(task.jobs.length, search.LIMITS.maxJobs);
  assert.equal(task.finishReason, "MAX_JOBS");
});

test("cancelling stops before the next page", async () => {
  let release;
  const { runner } = setup({
    browser: fakeBrowser({ steps: [ready(["a"]), ready(["a", "b"])] }),
    sleep: () => new Promise((resolve) => { release = resolve; }),
  });
  runner.subscribe(COMMAND.taskId, () => {});
  await runner.start(COMMAND);
  await waitUntil(() => release);
  await runner.cancel(COMMAND.taskId);
  release();
  await runner.whenSettled(COMMAND.taskId);

  const task = await runner.status(COMMAND.taskId);
  assert.equal(task.stopReason, "USER_CANCELLED");
  assert.deepEqual(task.jobs.map((item) => item.sourceJobId), ["a"]);
});

test("one task at a time, and starting the same task again returns it instead of re-running", async () => {
  const { runner, browser } = setup({
    browser: fakeBrowser({ steps: [ready(["a"])] }),
    sleep: () => new Promise(() => {}),
  });
  const [first, second] = await Promise.all([
    runner.start(COMMAND),
    runner.start({ ...COMMAND, taskId: "call_2" }),
  ]);
  assert.equal(first.ok, true);
  assert.equal(second.code, "BUSY");

  const again = await runner.start(COMMAND);
  assert.equal(again.ok, true);
  assert.equal(browser.calls.filter(([name]) => name === "open").length, 1);
});

test("invalid commands are rejected before anything is stored", async () => {
  const { runner, storage } = setup();
  const result = await runner.start({ ...COMMAND, maxPages: 9 });
  assert.equal(result.code, "INVALID_COMMAND");
  assert.equal(storage.data.size, 0);
});

test("after a worker restart, the interrupted task reports WORKER_RESTARTED with the jobs read so far", async () => {
  const storage = memoryStorage();
  // Worker 1 reads page 1, then is killed while pausing before page 2: its sleep never returns.
  const first = setup({
    storage,
    epoch: "worker-1",
    browser: fakeBrowser({ steps: [ready(["a", "b"])] }),
    sleep: () => new Promise(() => {}),
  });
  const progress = [];
  first.runner.subscribe(COMMAND.taskId, (event) => progress.push(event));
  await first.runner.start(COMMAND);
  await waitUntil(() => progress.some((event) => event.phase === "waiting"));

  // Worker 2 starts on the same session storage with a new epoch.
  const second = setup({ storage, epoch: "worker-2" });
  await second.runner.recoverStaleTasks();
  const task = await second.runner.status(COMMAND.taskId);

  assert.equal(task.status, "stopped");
  assert.equal(task.stopReason, "WORKER_RESTARTED");
  assert.match(task.stopMessage, /重启/);
  assert.equal(task.pagesRead, 1);
  assert.deepEqual(task.jobs.map((item) => item.sourceJobId), ["a", "b"]);
  // The stale task no longer blocks new work.
  const next = await second.runner.start({ ...COMMAND, taskId: "call_2", maxPages: 1 });
  assert.equal(next.ok, true);
});

test("status alone closes out a stale task, even if recovery has not run yet", async () => {
  const storage = memoryStorage();
  await storage.set(`${collectRunner.KEY_PREFIX}call_9`, {
    taskId: "call_9", status: "running", jobs: [], pagesRead: 0, epoch: "gone", updatedAt: 1_000,
  });
  const { runner } = setup({ storage, epoch: "worker-2" });
  assert.equal((await runner.status("call_9")).stopReason, "WORKER_RESTARTED");
  assert.equal((await storage.get(`${collectRunner.KEY_PREFIX}call_9`)).status, "stopped");
});
