/* global importScripts */
importScripts(
  "boss-extractor.js",
  "boss-list-extractor.js",
  "boss-api-extractor.js",
  "boss-search.js",
  "collect-status.js",
  "collect-runner.js",
  "command-relay.js",
);

(function startServiceWorker() {
  "use strict";

  const { CollectStop } = globalThis.ResumeLensCollectRunner;
  const { PORT_NAME } = globalThis.ResumeLensCommandRelay;
  const { blockerFromUrl } = globalThis.ResumeLensBossListExtractor;
  const { BUFFER_KEY, combine } = globalThis.ResumeLensBossApiExtractor;
  const { isBossUrl, PERMISSION_ORIGINS } = globalThis.ResumeLensBossSearch;

  const ALLOWED_ORIGINS = new Set(["http://localhost:3000", "http://127.0.0.1:3000"]);
  const BOSS_ORIGINS = [...PERMISSION_ORIGINS];
  const PAGE_SCRIPTS = ["src/boss-extractor.js", "src/boss-list-extractor.js", "src/content-boss-list.js"];
  /**
   * The list API hook, registered only while a task runs. It must be a registered content script
   * rather than an executeScript call: only registration runs it at document_start, before the
   * page sends its first list request.
   */
  const API_HOOK = Object.freeze({
    id: "resumelens-boss-api-hook",
    matches: ["https://www.zhipin.com/web/geek/job*"],
    js: ["src/boss-api-hook.js"],
    runAt: "document_start",
    world: "MAIN",
    persistAcrossSessions: false,
  });

  // Session storage survives a service worker restart but not a browser restart, and is not
  // readable from content scripts by default.
  const storage = {
    async get(key) {
      return (await chrome.storage.session.get(key))[key];
    },
    set(key, value) {
      return chrome.storage.session.set({ [key]: value });
    },
    remove(key) {
      return chrome.storage.session.remove(key);
    },
    async keys() {
      return Object.keys(await chrome.storage.session.get(null));
    },
  };

  const browser = {
    hasBossPermission() {
      return chrome.permissions.contains({ origins: BOSS_ORIGINS });
    },

    async armApiCapture() {
      // Clearing leftovers is best effort here: one BOSS tab that refuses the script (still
      // loading, discarded) must not cost this task its capture. The registration is gone either way.
      await browser.disarmApiCapture().catch(() => {});
      await chrome.scripting.registerContentScripts([{ ...API_HOOK }]);
    },

    async disarmApiCapture() {
      const registered = await chrome.scripting.getRegisteredContentScripts({ ids: [API_HOOK.id] });
      if (registered.length > 0) await chrome.scripting.unregisterContentScripts({ ids: [API_HOOK.id] });
      // Unregistering only prevents future injection. Stop hooks in existing pages too,
      // including tabs left open by a restarted worker or moved within BOSS by the user.
      const tabs = await chrome.tabs.query({ url: BOSS_ORIGINS });
      const stopped = await Promise.allSettled(tabs.map((tab) => chrome.scripting.executeScript({
        target: { tabId: tab.id },
        world: "MAIN",
        func: (key) => globalThis[key]?.stop(),
        args: [BUFFER_KEY],
      })));
      // Try every tab even if one closed or lost permission during cleanup, then report failure.
      const failure = stopped.find((result) => result.status === "rejected");
      if (failure) throw failure.reason;
    },

    async openTab(url, conversationId) {
      // A visible tab: the user can see what the assistant is doing, and background tabs are
      // throttled enough that lazy-loaded results may never arrive.
      const key = "collect-conversation-tab:" + conversationId;
      const previousId = (await chrome.storage.session.get(key))[key];
      if (previousId != null) {
        const existing = await chrome.tabs.get(previousId).then(() => true, () => false);
        if (existing) {
          const tab = await chrome.tabs.update(previousId, { url, active: true });
          return tab.id;
        }
      }
      const tab = await chrome.tabs.create({ url, active: true });
      await chrome.storage.session.set({ [key]: tab.id });
      return tab.id;
    },

    async showProgress(tabId, task) {
      await chrome.scripting.executeScript({ target: { tabId },
        files: ["src/collect-status.js", "src/content-collect-progress.js"] });
      await chrome.scripting.executeScript({ target: { tabId },
        func: (state, url) => globalThis.ResumeLensCollectProgress.render(state, url),
        args: [task, task.returnUrl || "http://localhost:3000/assistant"],
      });
    },

    waitForLoad(tabId, timeoutMs) {
      return new Promise((resolve, reject) => {
        const cleanup = () => {
          clearTimeout(timer);
          chrome.tabs.onUpdated.removeListener(onUpdated);
          chrome.tabs.onRemoved.removeListener(onRemoved);
        };
        const onUpdated = (updatedId, change, tab) => {
          if (updatedId !== tabId || change.status !== "complete") return;
          cleanup();
          resolve({ url: tab.url || "" });
        };
        const onRemoved = (removedId) => {
          if (removedId !== tabId) return;
          cleanup();
          reject(new CollectStop("TAB_CLOSED"));
        };
        const timer = setTimeout(() => {
          cleanup();
          reject(new CollectStop("PAGE_LOAD_TIMEOUT"));
        }, timeoutMs);
        chrome.tabs.onUpdated.addListener(onUpdated);
        chrome.tabs.onRemoved.addListener(onRemoved);
        chrome.tabs.get(tabId).then((tab) => {
          if (tab.status === "complete" && tab.url && tab.url !== "about:blank") {
            cleanup();
            resolve({ url: tab.url });
          }
        }, () => {
          cleanup();
          reject(new CollectStop("TAB_CLOSED"));
        });
      });
    },

    async readDetail(tabId) {
      let tab;
      try {
        tab = await chrome.tabs.get(tabId);
      } catch {
        throw new CollectStop("TAB_CLOSED");
      }
      const blocker = blockerFromUrl(tab.url || "");
      if (blocker) throw new CollectStop(blocker);
      if (!/^https:\/\/(?:[^/]+\.)?zhipin\.com\/job_detail\/[^/]+\.html(?:[?#]|$)/i.test(tab.url || "")) {
        throw new CollectStop("DETAIL_UNAVAILABLE");
      }
      try {
        await chrome.scripting.executeScript({ target: { tabId }, files: ["src/boss-extractor.js", "src/boss-list-extractor.js"] });
        const [injection] = await chrome.scripting.executeScript({
          target: { tabId },
          func: async () => {
            // BOSS may render the body after the document load event. Do not store a blank shell.
            const deadline = Date.now() + 15_000;
            let detail;
            do {
              const state = globalThis.ResumeLensBossListExtractor.inspect(document, location.href);
              if (state.kind === "blocked") return { blocked: state.code };
              detail = globalThis.ResumeLensBossExtractor.extract(document, location.href);
              // A meta description or search-list summary is not proof that the detail body loaded.
              const hasRealBody = globalThis.ResumeLensBossExtractor.SELECTORS.description.some((selector) =>
                [...document.querySelectorAll(selector)].some((node) =>
                  String(node.innerText || node.textContent || "").trim().length >= 60));
              if (hasRealBody && detail.job.description.length >= 60) return detail;
              await new Promise((resolve) => setTimeout(resolve, 400));
            } while (Date.now() < deadline);
            return null;
          },
        });
        return injection?.result;
      } catch {
        throw new CollectStop("DETAIL_UNAVAILABLE");
      }
    },

    async readPage(tabId, operation, options) {
      let tab;
      try {
        tab = await chrome.tabs.get(tabId);
      } catch {
        throw new CollectStop("TAB_CLOSED");
      }
      // The user (or BOSS) may have navigated the tab since the last step.
      const blocker = blockerFromUrl(tab.url || "");
      if (blocker) throw new CollectStop(blocker);
      if (!isBossUrl(tab.url || "")) throw new CollectStop("UNEXPECTED_PAGE");
      try {
        await chrome.scripting.executeScript({ target: { tabId }, files: PAGE_SCRIPTS });
        const [injection] = await chrome.scripting.executeScript({
          target: { tabId },
          func: (name, args) => globalThis.ResumeLensBossListPage[name](args),
          args: [operation, options],
        });
        if (!injection || !injection.result || !injection.result.state) throw new CollectStop("STRUCTURE_CHANGED");
        return combine(injection.result, await drainApiCapture(tabId));
      } catch (reason) {
        if (reason instanceof CollectStop) throw reason;
        if (!(await chrome.permissions.contains({ origins: BOSS_ORIGINS }))) throw new CollectStop("PERMISSION_REQUIRED");
        const stillOpen = await chrome.tabs.get(tabId).then(() => true, () => false);
        throw new CollectStop(stillOpen ? "UNEXPECTED_PAGE" : "TAB_CLOSED");
      }
    },
  };

  /** List responses the hook copied since the last read; null when there is no hook in the page. */
  async function drainApiCapture(tabId) {
    try {
      const [injection] = await chrome.scripting.executeScript({
        target: { tabId },
        world: "MAIN",
        func: (key) => {
          const buffer = globalThis[key];
          return buffer && typeof buffer.drain === "function" ? buffer.drain() : null;
        },
        args: [BUFFER_KEY],
      });
      return injection ? injection.result : null;
    } catch {
      return null;
    }
  }

  // Unlike the task state, the daily page count must survive a browser restart.
  const daily = {
    async get(key) {
      return (await chrome.storage.local.get(key))[key];
    },
    set(key, value) {
      return chrome.storage.local.set({ [key]: value });
    },
    remove(key) {
      return chrome.storage.local.remove(key);
    },
    async keys() {
      return Object.keys(await chrome.storage.local.get(null));
    },
  };

  function today() {
    const date = new Date();
    const pad = (value) => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  }

  const runner = globalThis.ResumeLensCollectRunner.create({
    browser,
    storage,
    daily,
    today,
    random: Math.random,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    now: Date.now,
    epoch: crypto.randomUUID(),
  });
  void runner.recoverStaleTasks();

  chrome.runtime.onConnect.addListener((port) => {
    if (port.name !== PORT_NAME) return;
    if (!trustedSender(port.sender)) {
      port.disconnect();
      return;
    }
    let unsubscribe = null;
    const post = (event) => {
      try {
        port.postMessage(event);
      } catch {
        unsubscribe?.();
      }
    };
    const follow = (taskId) => {
      unsubscribe?.();
      unsubscribe = runner.subscribe(taskId, post);
    };
    port.onDisconnect.addListener(() => unsubscribe?.());
    port.onMessage.addListener((message) => {
      void handle(message).catch(() => post({ type: "rejected", code: "INTERNAL_ERROR", error: "插件内部错误" }));
    });

    async function handle(message) {
      if (!message || typeof message !== "object") return;
      const taskId = typeof message.taskId === "string" ? message.taskId : "";
      if (message.type === "start") {
        // Subscribe first, so no progress event falls between starting and listening.
        follow(taskId);
        const command = message.command && typeof message.command === "object" ? message.command : {};
        const returnUrl = new URL("/assistant", port.sender.origin || port.sender.url).href;
        const started = await runner.start({ ...command, taskId, returnUrl });
        if (!started.ok) post({ type: "rejected", taskId, code: started.code, error: started.error });
        else reportTask(started.task);
      } else if (message.type === "status") {
        follow(taskId);
        const task = await runner.status(taskId);
        if (!task) post({ type: "rejected", taskId, code: "UNKNOWN_TASK", error: "插件里找不到这次采集任务" });
        else reportTask(task);
      } else if (message.type === "cancel") {
        await runner.cancel(taskId);
        post({ type: "cancel_requested", taskId });
      }
    }

    function reportTask(task) {
      post(task.status === "running" ? { type: "accepted", task } : { type: "result", task });
    }
  });

  function trustedSender(sender) {
    if (!sender || sender.id !== chrome.runtime.id) return false;
    try {
      return ALLOWED_ORIGINS.has(sender.origin || new URL(sender.url).origin);
    } catch {
      return false;
    }
  }
})();
