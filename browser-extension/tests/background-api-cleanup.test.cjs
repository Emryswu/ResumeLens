const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const test = require("node:test");
const hook = require("../src/boss-api-hook.js");

/** Loads the service worker against the given chrome stub and returns its browser adapter. */
function workerAdapter(chrome) {
  let adapter;
  vm.runInNewContext(fs.readFileSync(require.resolve("../src/background.js"), "utf8"), {
    importScripts() {},
    crypto: { randomUUID: () => "test-worker" },
    ResumeLensCollectRunner: {
      CollectStop: Error,
      create(deps) { adapter = deps.browser; return { recoverStaleTasks() {} }; },
    },
    ResumeLensCommandRelay: { PORT_NAME: "test" },
    ResumeLensBossListExtractor: require("../src/boss-list-extractor.js"),
    ResumeLensBossApiExtractor: require("../src/boss-api-extractor.js"),
    ResumeLensBossSearch: require("../src/boss-search.js"),
    chrome: { ...chrome, runtime: { onConnect: { addListener() {} } } },
  });
  return adapter;
}

test("collection and verification reuse one owned tab across turns, reopen closed tabs and isolate conversations", async () => {
  const data = {};
  const tabs = new Map([[99, { id: 99, url: "https://www.zhipin.com/web/geek/job" }]]);
  const created = [];
  const updated = [];
  const chrome = {
    storage: { session: {
      async get(key) { return { [key]: data[key] }; },
      async set(values) { Object.assign(data, values); },
    } },
    tabs: {
      async get(id) { if (!tabs.has(id)) throw new Error("No tab"); return tabs.get(id); },
      async create(options) { const tab = { id: created.length + 1, ...options }; tabs.set(tab.id, tab); created.push(tab); return tab; },
      async update(id, options) { assert.ok(tabs.has(id)); Object.assign(tabs.get(id), options); updated.push({ id, ...options }); return tabs.get(id); },
    },
  };
  let adapter = workerAdapter(chrome);
  const search = "https://www.zhipin.com/web/geek/job?query=Java";
  const detail = "https://www.zhipin.com/job_detail/abc.html";
  for (const url of [search, search, search, detail, detail]) await adapter.openTab(url, "42");
  assert.equal(created.length, 1, "three searches and two verifications create only one tab");
  adapter = workerAdapter(chrome); // Later turn and service-worker restart.
  await adapter.openTab(search, "42");
  assert.equal(created.length, 1, "换一批 reuses the persisted tab");
  assert.ok(created.every((tab) => tab.active === true));
  assert.ok(updated.every((tab) => tab.active === true && tab.id !== 99));
  await adapter.openTab(search, "43");
  assert.equal(created.length, 2);
  tabs.delete(1);
  await adapter.openTab(search, "42");
  assert.equal(created.length, 3, "a closed owned tab is recreated");
  assert.equal(tabs.get(99).url, "https://www.zhipin.com/web/geek/job");
});

test("worker cleanup stops already injected hooks as well as unregistering future injections", async () => {
  const page = { location: { href: "https://www.zhipin.com/web/geek/job" }, fetch: async () => new Response("{}") };
  const originalFetch = page.fetch;
  hook.install(page);
  let registered = [{ id: "resumelens-boss-api-hook" }];
  let query;
  const cleanedTabs = [];
  const adapter = workerAdapter({
    scripting: {
      async getRegisteredContentScripts() { return registered; },
      async unregisterContentScripts() { registered = []; },
      async executeScript({ target, world, func, args }) {
        assert.equal(world, "MAIN");
        cleanedTabs.push(target.tabId);
        vm.runInNewContext(`(${func.toString()})(...args)`, { [hook.BUFFER_KEY]: page[hook.BUFFER_KEY], args });
      },
    },
    tabs: { async query(options) { query = options; return [{ id: 7 }, { id: 8 }]; } },
  });

  await adapter.disarmApiCapture();
  assert.equal(registered.length, 0);
  assert.deepEqual(Array.from(query.url), ["https://*.zhipin.com/*"]);
  assert.deepEqual(cleanedTabs, [7, 8]);
  assert.equal(page.fetch, originalFetch);

  // A restarted worker may find a page hook even though its registration is already gone.
  hook.install(page);
  await adapter.disarmApiCapture();
  assert.equal(page.fetch, originalFetch);
});

test("a BOSS tab that refuses cleanup does not stop the next task from capturing", async () => {
  let registered = [{ id: "resumelens-boss-api-hook" }];
  const adapter = workerAdapter({
    scripting: {
      async getRegisteredContentScripts() { return registered; },
      async unregisterContentScripts() { registered = []; },
      async registerContentScripts(scripts) { registered = scripts; },
      async executeScript() { throw new Error("Frame with ID 0 was removed."); },
    },
    tabs: { async query() { return [{ id: 7 }]; } },
  });

  await adapter.armApiCapture();
  assert.deepEqual(Array.from(registered, (script) => script.id), ["resumelens-boss-api-hook"]);
  assert.equal(registered[0].world, "MAIN");

  // At the end of a task the same failure is still reported, so the caller knows cleanup was incomplete.
  await assert.rejects(adapter.disarmApiCapture(), /Frame with ID 0 was removed/);
  assert.equal(registered.length, 0);
});
