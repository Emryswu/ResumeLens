/* global importScripts */
importScripts("boss-extractor.js", "boss-list-extractor.js", "boss-search.js", "collect-runner.js", "command-relay.js");

(function startServiceWorker() {
  "use strict";

  const { CollectStop } = globalThis.ResumeLensCollectRunner;
  const { PORT_NAME } = globalThis.ResumeLensCommandRelay;
  const { blockerFromUrl } = globalThis.ResumeLensBossListExtractor;
  const { isBossUrl, PERMISSION_ORIGINS } = globalThis.ResumeLensBossSearch;

  const ALLOWED_ORIGINS = new Set(["http://localhost:3000", "http://127.0.0.1:3000"]);
  const BOSS_ORIGINS = [...PERMISSION_ORIGINS];
  const PAGE_SCRIPTS = ["src/boss-extractor.js", "src/boss-list-extractor.js", "src/content-boss-list.js"];

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

    async openTab(url) {
      // A visible tab: the user can see what the assistant is doing, and background tabs are
      // throttled enough that lazy-loaded results may never arrive.
      const tab = await chrome.tabs.create({ url, active: true });
      return tab.id;
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
        return injection.result;
      } catch (reason) {
        if (reason instanceof CollectStop) throw reason;
        if (!(await chrome.permissions.contains({ origins: BOSS_ORIGINS }))) throw new CollectStop("PERMISSION_REQUIRED");
        const stillOpen = await chrome.tabs.get(tabId).then(() => true, () => false);
        throw new CollectStop(stillOpen ? "UNEXPECTED_PAGE" : "TAB_CLOSED");
      }
    },
  };

  const runner = globalThis.ResumeLensCollectRunner.create({
    browser,
    storage,
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
        const started = await runner.start({ ...command, taskId });
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
