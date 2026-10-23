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
    async armApiCapture() { calls.push(["arm"]); },
    async disarmApiCapture() { calls.push(["disarm"]); },
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
  const daily = overrides.daily || memoryStorage();
  const delays = [];
  const browser = overrides.browser || fakeBrowser();
  const runner = collectRunner.create({
    browser,
    storage,
    daily,
    today: overrides.today || (() => "2026-10-22"),
    random: overrides.random || (() => 0.5),
    sleep: overrides.sleep || (async (ms) => { delays.push(ms); }),
    now: () => 1_000,
    epoch: overrides.epoch || "worker-1",
  });
  return { runner, storage, daily, browser, delays };
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

test("per-page source counts survive reconnects and reach the BOSS banner without closing the search tab", async () => {
  const first = ready(["a", "b"]);
  first.jobs[0].captureSource = "api";
  const browser = fakeBrowser({ steps: [first, ready(["a", "b", "c"])] });
  const banners = [];
  browser.showProgress = async (tabId, task) => banners.push({ tabId, task: structuredClone(task) });
  browser.closeTab = async () => assert.fail("search tabs must remain open");
  const { runner } = setup({ browser });
  const { events, task } = await runToEnd(runner, { ...COMMAND, maxPages: 2 });
  assert.deepEqual(task.pageLog, [
    { page: 1, api: 1, dom: 1 },
    { page: 2, api: 0, dom: 1 },
  ]);
  assert.deepEqual(events.find((event) => event.phase === "read").pageLog, task.pageLog.slice(0, 1));
  assert.equal(banners.at(-1).tabId, 7);
  assert.equal(banners.at(-1).task.finishReason, "MAX_PAGES");
  assert.deepEqual((await runner.status(COMMAND.taskId)).pageLog, task.pageLog);
});

test("detail verification reads a real posting and keeps it distinct from list summaries", async () => {
  const sourceUrl = "https://www.zhipin.com/job_detail/abc123.html";
  const browser = fakeBrowser({ loadedUrl: sourceUrl });
  browser.readDetail = async () => ({ job: {
    ...job("abc123"), description: "这是从真实详情页读取的岗位职责与任职要求".repeat(5),
  } });
  const { runner, daily } = setup({ browser });
  const { task } = await runToEnd(runner, { taskId: "detail_1", mode: "detail",
    jobId: 17, sourceJobId: "abc123", sourceUrl });
  assert.equal(task.status, "completed");
  assert.equal(task.finishReason, "DETAIL_READ");
  assert.equal(task.jobs[0].sourceJobId, "abc123");
  assert.equal(task.pagesRead, 1);
  assert.equal(await daily.get("collect-daily-pages:" + "2026-10-22"), 1);
  assert.equal(browser.calls.some(([name]) => name === "read"), false);
});

test("back-to-back verifications are spaced like page turns and reuse one detail tab slot", async () => {
  const urls = ["abc123", "def456"].map((id) => `https://www.zhipin.com/job_detail/${id}.html`);
  let opened = 0;
  const closed = [];
  const browser = fakeBrowser();
  browser.openTab = async (url) => { browser.calls.push(["open", url]); opened += 1; return 100 + opened; };
  browser.waitForLoad = async (tabId) => ({ url: urls[tabId - 101] });
  browser.closeDetailTab = async (tabId) => { closed.push(tabId); };
  browser.readDetail = async (tabId) => ({ job: {
    ...job(["abc123", "def456"][tabId - 101]), description: "真实详情页的岗位职责与任职要求".repeat(6),
  } });
  const { runner, delays } = setup({ browser });
  const first = await runToEnd(runner, { taskId: "detail_a", mode: "detail", jobId: 1, sourceJobId: "abc123", sourceUrl: urls[0] });
  assert.equal(first.task.finishReason, "DETAIL_READ");
  assert.deepEqual(delays, [], "the first verification does not wait");
  assert.deepEqual(closed, []);
  const second = await runToEnd(runner, { taskId: "detail_b", mode: "detail", jobId: 2, sourceJobId: "def456", sourceUrl: urls[1] });
  assert.equal(second.task.finishReason, "DETAIL_READ");
  assert.deepEqual(delays, [search.pageDelayMs(() => 0.5)], "the second verification waits one page delay");
  assert.ok(second.events.some((event) => event.type === "progress" && event.phase === "waiting"));
  assert.deepEqual(closed, [], "verification tabs stay open for reuse by their conversation");
});

test("detail verification refuses a short or mismatched posting", async () => {
  const sourceUrl = "https://www.zhipin.com/job_detail/abc123.html";
  const browser = fakeBrowser({ loadedUrl: sourceUrl });
  browser.readDetail = async () => ({ job: { ...job("other"), description: "摘要" } });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner, { taskId: "detail_2", mode: "detail",
    jobId: 17, sourceJobId: "abc123", sourceUrl });
  assert.equal(task.status, "stopped");
  assert.equal(task.stopReason, "DETAIL_UNAVAILABLE");
  assert.deepEqual(task.jobs, []);
});

test("a blocked detail page is never treated as a complete JD", async () => {
  const sourceUrl = "https://www.zhipin.com/job_detail/abc123.html";
  const browser = fakeBrowser({ loadedUrl: sourceUrl });
  browser.readDetail = async () => ({ blocked: "CAPTCHA" });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner, { taskId: "detail_captcha", mode: "detail",
    jobId: 17, sourceJobId: "abc123", sourceUrl });
  assert.equal(task.stopReason, "CAPTCHA");
  assert.deepEqual(task.jobs, []);
});

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

test("the experience condition reaches the opened URL and is echoed in the result", async () => {
  const browser = fakeBrowser({ steps: [ready(["a"])] });
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner, { ...COMMAND, keyword: "", experience: "在校生", maxPages: 1 });
  const url = new URL(browser.calls.find(([name]) => name === "open")[1]);
  assert.equal(url.searchParams.get("experience"), "108");
  assert.equal(url.searchParams.get("city"), "101280600");
  assert.equal(task.command.experience, "在校生");
  assert.equal(task.command.keyword, "");
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

test("the API hook is armed before the tab opens and removed when the task ends, however it ends", async () => {
  const finished = setup({ browser: fakeBrowser({ steps: [ready(["a"])] }) });
  await runToEnd(finished.runner, { ...COMMAND, maxPages: 1 });
  const names = finished.browser.calls.map(([name]) => name);
  assert.ok(names.indexOf("arm") < names.indexOf("open"));
  assert.equal(names.at(-1), "disarm");

  const stopped = setup({ browser: fakeBrowser({ steps: [{ state: { kind: "blocked", code: "CAPTCHA" }, jobs: [] }] }) });
  await runToEnd(stopped.runner);
  assert.equal(stopped.browser.calls.at(-1)[0], "disarm");
});

test("a failing hook registration does not stop collection", async () => {
  const browser = fakeBrowser({ steps: [ready(["a"])] });
  browser.armApiCapture = async () => { throw new Error("registerContentScripts failed"); };
  const { runner } = setup({ browser });
  const { task } = await runToEnd(runner, { ...COMMAND, maxPages: 1 });
  assert.equal(task.status, "completed");
});

test("each job keeps its capture source and the task counts both", async () => {
  const mixed = { state: { kind: "ready", cardCount: 3 }, jobs: [
    { ...job("a"), captureSource: "api" },
    { ...job("b"), captureSource: "api" },
    { ...job("c"), captureSource: "dom" },
  ], skippedWithoutId: 0 };
  const { runner } = setup({ browser: fakeBrowser({ steps: [mixed] }) });
  const { task } = await runToEnd(runner, { ...COMMAND, maxPages: 1 });
  assert.deepEqual(task.jobs.map((item) => item.captureSource), ["api", "api", "dom"]);
  assert.deepEqual(task.sources, { api: 2, dom: 1 });
});

test("every BOSS page loaded takes one unit of the daily page budget", async () => {
  const { runner, daily } = setup({ browser: fakeBrowser({ steps: [ready(["a"]), ready(["a", "b"]), ready(["a", "b", "c"])] }) });
  const { task } = await runToEnd(runner);
  assert.equal(await daily.get(`${collectRunner.DAILY_PREFIX}2026-10-22`), 3);
  assert.equal(task.dailyPagesLeft, search.LIMITS.maxPagesPerDay - 3);
});

test("a spent daily budget rejects the start before anything is stored or opened", async () => {
  const daily = memoryStorage();
  await daily.set(`${collectRunner.DAILY_PREFIX}2026-10-22`, search.LIMITS.maxPagesPerDay);
  const { runner, storage, browser } = setup({ daily });
  const result = await runner.start(COMMAND);

  assert.equal(result.ok, false);
  assert.equal(result.code, "DAILY_LIMIT");
  assert.match(result.error, /页数已用完/);
  assert.equal(storage.data.size, 0);
  assert.equal(browser.calls.length, 0);
});

test("a budget that runs out mid-task ends the task with the pages already read", async () => {
  const daily = memoryStorage();
  await daily.set(`${collectRunner.DAILY_PREFIX}2026-10-22`, search.LIMITS.maxPagesPerDay - 2);
  const browser = fakeBrowser({ steps: [ready(["a"]), ready(["a", "b"])] });
  const { runner } = setup({ daily, browser });
  const { task } = await runToEnd(runner);

  assert.equal(task.status, "completed");
  assert.equal(task.finishReason, "DAILY_LIMIT");
  assert.equal(task.pagesRead, 2);
  assert.equal(task.dailyPagesLeft, 0);
  assert.equal(browser.calls.filter(([name]) => name === "scrollAndWait").length, 1);
});

test("the budget is per local day and survives a new worker; earlier days are cleaned up", async () => {
  const daily = memoryStorage();
  await daily.set(`${collectRunner.DAILY_PREFIX}2026-10-21`, search.LIMITS.maxPagesPerDay);
  await daily.set(`${collectRunner.DAILY_PREFIX}2026-10-22`, search.LIMITS.maxPagesPerDay);
  await daily.set("unrelated", 1);

  const sameDay = setup({ daily, epoch: "worker-2" });
  await sameDay.runner.recoverStaleTasks();
  assert.equal((await sameDay.runner.start(COMMAND)).code, "DAILY_LIMIT");
  assert.equal(await daily.get(`${collectRunner.DAILY_PREFIX}2026-10-21`), undefined);
  assert.equal(await daily.get("unrelated"), 1);

  const nextDay = setup({ daily, today: () => "2026-10-23", browser: fakeBrowser({ steps: [ready(["a"])] }) });
  assert.equal((await nextDay.runner.start({ ...COMMAND, maxPages: 1 })).ok, true);
});

test("cancelling during the pause does not spend another page", async () => {
  let release;
  const { runner, daily } = setup({
    browser: fakeBrowser({ steps: [ready(["a"])] }),
    sleep: () => new Promise((resolve) => { release = resolve; }),
  });
  await runner.start(COMMAND);
  await waitUntil(() => release);
  await runner.cancel(COMMAND.taskId);
  release();
  await runner.whenSettled(COMMAND.taskId);
  assert.equal(await daily.get(`${collectRunner.DAILY_PREFIX}2026-10-22`), 1);
});

test("worker start-up removes a hook an interrupted task left registered", async () => {
  const { runner, browser } = setup();
  await runner.recoverStaleTasks();
  assert.deepEqual(browser.calls, [["disarm"]]);
});
