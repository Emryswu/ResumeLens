(function exposeCollectRunner(root, factory) {
  const node = typeof module === "object" && module.exports;
  const runner = factory(
    node ? require("./boss-search.js") : root.ResumeLensBossSearch,
    node ? require("./boss-list-extractor.js") : root.ResumeLensBossListExtractor,
  );
  if (node) module.exports = runner;
  root.ResumeLensCollectRunner = runner;
})(typeof globalThis !== "undefined" ? globalThis : this, function createCollectRunnerModule(search, list) {
  "use strict";

  const KEY_PREFIX = "collect-task:";
  /** Pages read per local day, in persistent storage so a browser restart does not reset it. */
  const DAILY_PREFIX = "collect-daily-pages:";
  const LOAD_TIMEOUT_MS = 30_000;
  const FIRST_READ_WAIT_MS = 15_000;
  const SCROLL_WAIT_MS = 10_000;
  /** Finished tasks stay readable this long so a reconnecting page can still collect the result. */
  const RETENTION_MS = 2 * 60 * 60 * 1000;

  const STOP_MESSAGES = Object.freeze({
    PERMISSION_REQUIRED: "插件还没获得 BOSS 直聘的访问授权：请点开插件弹窗，允许访问 BOSS 直聘后再试",
    BOSS_NOT_LOGGED_IN: "BOSS 直聘未登录：请先在这个浏览器里登录 BOSS 直聘",
    CAPTCHA: "BOSS 直聘要求安全验证，已停止采集；请手动完成验证后再试",
    STRUCTURE_CHANGED: "BOSS 搜索页的结构和插件认识的不一致，已停止采集，可能需要更新插件",
    PAGE_LOAD_TIMEOUT: "BOSS 搜索页加载超时，已停止采集",
    TAB_CLOSED: "采集用的标签页被关闭，已停止采集",
    UNEXPECTED_PAGE: "BOSS 搜索页跳转到了插件无法读取的页面，已停止采集",
    WORKER_RESTARTED: "插件后台在采集途中被浏览器重启，已停止采集，保留重启前读到的岗位",
    USER_CANCELLED: "已按要求停止采集",
    DAILY_LIMIT: `今天已读取 ${search.LIMITS.maxPagesPerDay} 页 BOSS 搜索结果，达到每日上限，明天再试`,
    INTERNAL_ERROR: "插件内部错误，已停止采集",
    DETAIL_UNAVAILABLE: "完整 JD 没有加载出来或与目标岗位不一致，未将摘要标记为已核实",
  });

  /** A failure the browser adapter reports with one of the stop codes above. */
  class CollectStop extends Error {
    constructor(code) {
      super(STOP_MESSAGES[code] || code);
      this.code = code;
    }
  }

  /**
   * Runs BOSS collection tasks. Every state change is written to {@code storage} before it is
   * announced, so a service worker that is restarted mid-task can still tell the page what
   * happened: the task keeps the epoch of the worker that ran it, and a later worker with a
   * different epoch reports it as stopped with WORKER_RESTARTED instead of leaving it hanging.
   *
   * Every BOSS page the task loads (the first page, and each scroll that loads another) first
   * takes one unit of the daily page budget; a task that finds the budget spent ends there with
   * what it has read.
   *
   * Dependencies: browser { hasBossPermission, armApiCapture, disarmApiCapture, openTab,
   * waitForLoad, readPage }, storage { get, set, remove, keys } (per session), daily { get, set,
   * remove, keys } (persistent), today() → "YYYY-MM-DD" in local time, random() in [0, 1),
   * sleep(ms), now(), epoch.
   */
  function create(deps) {
    const { browser, storage, daily, today, random, sleep, now, epoch } = deps;
    const listeners = new Map();
    const cancelled = new Set();
    const running = new Map();
    // Starts are serialized so two pages cannot both pass the "nothing else is running" check.
    let startQueue = Promise.resolve();

    function start(rawCommand) {
      const result = startQueue.then(() => startNow(rawCommand));
      startQueue = result.catch(() => undefined);
      return result;
    }

    async function startNow(rawCommand) {
      const validation = rawCommand?.mode === "detail"
        ? search.validateDetailCommand(rawCommand)
        : search.validateCommand(rawCommand);
      if (!validation.ok) return { ok: false, code: "INVALID_COMMAND", error: validation.error };
      const command = validation.command;
      const existing = await status(command.taskId);
      // Starting is idempotent: a page that reconnects with the same id gets the same task back.
      if (existing) return { ok: true, task: existing };
      for (const other of await allTasks()) {
        if (other.status === "running" && other.epoch === epoch) {
          return { ok: false, code: "BUSY", error: "插件正在执行另一次采集，请等它结束" };
        }
      }
      if ((await dailyPagesUsed()) >= search.LIMITS.maxPagesPerDay) {
        return { ok: false, code: "DAILY_LIMIT", error: STOP_MESSAGES.DAILY_LIMIT };
      }
      const state = {
        taskId: command.taskId,
        status: "running",
        command: command.mode === "detail"
          ? { mode: "detail", jobId: command.jobId, sourceJobId: command.sourceJobId, sourceUrl: command.sourceUrl }
          : { keyword: command.keyword, city: command.city, maxPages: command.maxPages, experience: command.experience },
        pagesRead: 0,
        jobs: [],
        sources: { api: 0, dom: 0 },
        skippedWithoutId: 0,
        dailyPagesLeft: null,
        stopReason: null,
        stopMessage: null,
        finishReason: null,
        startedAt: now(),
        updatedAt: now(),
        epoch,
      };
      await save(state);
      running.set(state.taskId, run(state, command));
      return { ok: true, task: publicView(state) };
    }

    /** The task as last persisted; a running task owned by an earlier worker is closed out here. */
    async function status(taskId) {
      const state = await storage.get(KEY_PREFIX + taskId);
      if (!state) return null;
      if (state.status === "running" && state.epoch !== epoch) {
        return publicView(await stopStale(state));
      }
      return publicView(state);
    }

    async function cancel(taskId) {
      cancelled.add(taskId);
      return status(taskId);
    }

    /**
     * Called once when the worker starts: closes tasks a previous worker left running, drops old
     * ones and earlier days' page counts, and removes a capture hook an interrupted task left
     * registered. Queued with starts, so it cannot remove the hook of a task that starts meanwhile.
     */
    function recoverStaleTasks() {
      const result = startQueue.then(recoverNow);
      startQueue = result.catch(() => undefined);
      return result;
    }

    async function recoverNow() {
      await quietly(() => browser.disarmApiCapture());
      const todayKey = DAILY_PREFIX + today();
      for (const key of await daily.keys()) {
        if (key.startsWith(DAILY_PREFIX) && key !== todayKey) await daily.remove(key);
      }
      for (const state of await allTasks()) {
        if (now() - state.updatedAt > RETENTION_MS && state.status !== "running") {
          await storage.remove(KEY_PREFIX + state.taskId);
        } else if (state.status === "running" && state.epoch !== epoch) {
          await stopStale(state);
        }
      }
    }

    function subscribe(taskId, listener) {
      if (!listeners.has(taskId)) listeners.set(taskId, new Set());
      listeners.get(taskId).add(listener);
      return () => listeners.get(taskId)?.delete(listener);
    }

    /** Resolves when this worker's run of the task has finished; for tests and shutdown. */
    function whenSettled(taskId) {
      return running.get(taskId) || Promise.resolve();
    }

    async function run(state, command) {
      try {
        if (!(await browser.hasBossPermission())) throw new CollectStop("PERMISSION_REQUIRED");
        if (command.mode === "detail") {
          if (!(await takeDailyPage(state))) throw new CollectStop("DAILY_LIMIT");
          progress(state, "opening");
          const tabId = await browser.openTab(command.sourceUrl);
          const loaded = await browser.waitForLoad(tabId, LOAD_TIMEOUT_MS);
          const blocker = list.blockerFromUrl(loaded.url);
          if (blocker) throw new CollectStop(blocker);
          if (loaded.url.split("?")[0].split("#")[0] !== command.sourceUrl) {
            throw new CollectStop("DETAIL_UNAVAILABLE");
          }
          checkCancelled(state);
          progress(state, "verifying");
          const detail = await browser.readDetail(tabId);
          if (detail?.blocked === "CAPTCHA" || detail?.blocked === "BOSS_NOT_LOGGED_IN") {
            throw new CollectStop(detail.blocked);
          }
          if (!detail?.job || detail.job.sourceJobId !== command.sourceJobId
              || detail.job.sourceUrl !== command.sourceUrl
              || detail.job.description.length < 60) {
            throw new CollectStop("DETAIL_UNAVAILABLE");
          }
          checkCancelled(state);
          state.jobs = [{ ...detail.job, captureSource: "dom" }];
          state.sources.dom = 1;
          state.pagesRead = 1;
          await save(state);
          return finish(state, "DETAIL_READ");
        }
        if (!(await takeDailyPage(state))) throw new CollectStop("DAILY_LIMIT");
        // Best effort: without the hook every page is simply read from the DOM.
        await quietly(() => browser.armApiCapture());
        progress(state, "opening");
        const tabId = await browser.openTab(search.buildSearchUrl(command));
        const loaded = await browser.waitForLoad(tabId, LOAD_TIMEOUT_MS);
        const urlBlocker = list.blockerFromUrl(loaded.url);
        if (urlBlocker) throw new CollectStop(urlBlocker);
        if (!search.isBossUrl(loaded.url)) throw new CollectStop("UNEXPECTED_PAGE");
        checkCancelled(state);

        progress(state, "reading", { page: 1 });
        let snapshot = await browser.readPage(tabId, "read", { waitMs: FIRST_READ_WAIT_MS });
        if (snapshot.state.kind === "blocked") throw new CollectStop(snapshot.state.code);
        if (snapshot.state.kind === "empty") return finish(state, "NO_RESULTS");
        if (snapshot.state.kind !== "ready") throw new CollectStop("STRUCTURE_CHANGED");
        let cardCount = snapshot.state.cardCount;
        await merge(state, snapshot, 1);

        for (let page = 2; page <= command.maxPages; page += 1) {
          if (state.jobs.length >= search.LIMITS.maxJobs) return finish(state, "MAX_JOBS");
          const delayMs = search.pageDelayMs(random);
          progress(state, "waiting", { page, delayMs });
          await sleep(delayMs);
          checkCancelled(state);
          if (!(await takeDailyPage(state))) return finish(state, "DAILY_LIMIT");
          progress(state, "scrolling", { page });
          snapshot = await browser.readPage(tabId, "scrollAndWait", { previousCount: cardCount, waitMs: SCROLL_WAIT_MS });
          if (snapshot.state.kind === "blocked") throw new CollectStop(snapshot.state.code);
          if (snapshot.state.kind !== "ready" || snapshot.state.cardCount <= cardCount) {
            return finish(state, "NO_MORE_RESULTS");
          }
          cardCount = snapshot.state.cardCount;
          await merge(state, snapshot, page);
        }
        return finish(state, state.jobs.length >= search.LIMITS.maxJobs ? "MAX_JOBS" : "MAX_PAGES");
      } catch (reason) {
        // No retries: every stop goes straight back to the caller with what was read so far.
        return stop(state, reason instanceof CollectStop ? reason.code : "INTERNAL_ERROR");
      } finally {
        cancelled.delete(state.taskId);
        running.delete(state.taskId);
        await quietly(() => browser.disarmApiCapture());
      }
    }

    async function dailyPagesUsed() {
      return Number(await daily.get(DAILY_PREFIX + today())) || 0;
    }

    /** Takes one page from today's budget; false when it is spent. */
    async function takeDailyPage(state) {
      const used = await dailyPagesUsed();
      if (used >= search.LIMITS.maxPagesPerDay) {
        state.dailyPagesLeft = 0;
        return false;
      }
      await daily.set(DAILY_PREFIX + today(), used + 1);
      state.dailyPagesLeft = search.LIMITS.maxPagesPerDay - used - 1;
      return true;
    }

    async function merge(state, snapshot, page) {
      const known = new Set(state.jobs.map((job) => job.sourceJobId));
      for (const job of snapshot.jobs) {
        if (state.jobs.length >= search.LIMITS.maxJobs) break;
        if (known.has(job.sourceJobId)) continue;
        known.add(job.sourceJobId);
        state.jobs.push(job);
        state.sources[job.captureSource === "api" ? "api" : "dom"] += 1;
      }
      // The page re-reports every card it has rendered, so the latest count is the total, not an increment.
      state.skippedWithoutId = Math.max(state.skippedWithoutId, Number(snapshot.skippedWithoutId) || 0);
      state.pagesRead = page;
      await save(state);
      progress(state, "read", { page });
    }

    function checkCancelled(state) {
      if (cancelled.has(state.taskId)) throw new CollectStop("USER_CANCELLED");
    }

    async function finish(state, finishReason) {
      state.status = "completed";
      state.finishReason = finishReason;
      await save(state);
      announce(state);
      return state;
    }

    async function stop(state, code) {
      state.status = "stopped";
      state.stopReason = code;
      state.stopMessage = STOP_MESSAGES[code] || STOP_MESSAGES.INTERNAL_ERROR;
      await save(state);
      announce(state);
      return state;
    }

    async function stopStale(state) {
      state.status = "stopped";
      state.stopReason = "WORKER_RESTARTED";
      state.stopMessage = STOP_MESSAGES.WORKER_RESTARTED;
      await save(state);
      return state;
    }

    async function save(state) {
      state.updatedAt = now();
      await storage.set(KEY_PREFIX + state.taskId, structuredClone(state));
    }

    async function allTasks() {
      const keys = (await storage.keys()).filter((key) => key.startsWith(KEY_PREFIX));
      const tasks = [];
      for (const key of keys) {
        const state = await storage.get(key);
        if (state) tasks.push(state);
      }
      return tasks;
    }

    function progress(state, phase, extra) {
      emit(state.taskId, { type: "progress", taskId: state.taskId, phase, jobCount: state.jobs.length, ...extra });
    }

    function announce(state) {
      emit(state.taskId, { type: "result", task: publicView(state) });
    }

    function emit(taskId, event) {
      for (const listener of listeners.get(taskId) || []) {
        try {
          listener(event);
        } catch {
          // A listener whose port has gone away must not stop the task.
        }
      }
    }

    return { cancel, recoverStaleTasks, start, status, subscribe, whenSettled };
  }

  /** Runs a best-effort step whose failure must not end the task. */
  async function quietly(step) {
    try {
      await step();
    } catch {
      // The step's only effect is optional; the task goes on without it.
    }
  }

  function publicView(state) {
    const { epoch: _epoch, ...view } = state;
    return structuredClone(view);
  }

  return { CollectStop, DAILY_PREFIX, KEY_PREFIX, STOP_MESSAGES, create };
});
