import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { join } from "node:path";
const require = createRequire(import.meta.url);
const { CollectStop } = require("../../browser-extension/src/collect-runner.js");
const api = require("../../browser-extension/src/boss-api-extractor.js");
const list = require("../../browser-extension/src/boss-list-extractor.js");

const source = (name) => readFile(new URL(`../../browser-extension/src/${name}`, import.meta.url), "utf8");

export async function createPatchrightBrowser({ chromium, profileDir, returnUrl = "http://localhost:3000/assistant", launchOptions = {} }) {
  const context = await chromium.launchPersistentContext(profileDir, { channel: "chrome", headless: false, viewport: null, ...launchOptions });
  const scripts = await Promise.all(["boss-extractor.js", "boss-list-extractor.js", "content-boss-list.js"].map(source));
  const bannerScripts = await Promise.all(["collect-status.js", "content-collect-progress.js"].map(source));
  const owned = new Map();
  const captures = new Map();
  let capturing = false;
  let nextId = 0;
  const ids = new Map();
  let startupPage = context.pages().find((page) => page.url() === "about:blank");

  async function attach(page, conversationId) {
    const id = ++nextId;
    owned.set(conversationId, { page, id });
    ids.set(id, page);
    captures.set(id, []);
    page.on("response", async (response) => {
      if (!capturing) return;
      const url = new URL(response.url());
      if (url.origin !== "https://www.zhipin.com" || !api.LIST_API_PATHS.includes(url.pathname)) return;
      try {
        const body = await response.text();
        if (!capturing || body.length > api.MAX_BODY_CHARS) return;
        const buffer = captures.get(id);
        buffer.push({ path: url.pathname, body });
        if (buffer.length > api.MAX_ENTRIES) buffer.shift();
      } catch (error) {
        // A page can close before its response body is available; DOM extraction remains available.
        if (!page.isClosed()) console.warn("BOSS list response unavailable; DOM extraction remains active:", error.message);
      }
    });
    return { page, id };
  }

  function pageFor(id) {
    const page = ids.get(id);
    if (!page || page.isClosed()) throw new CollectStop("TAB_CLOSED");
    return page;
  }

  return {
    context,
    async hasBossPermission() { return true; },
    async armApiCapture() { capturing = true; },
    async disarmApiCapture() { capturing = false; for (const buffer of captures.values()) buffer.length = 0; },
    async openTab(url, conversationId) {
      let entry = owned.get(conversationId);
      if (!entry || entry.page.isClosed()) {
        const page = startupPage && !startupPage.isClosed() ? startupPage : await context.newPage();
        startupPage = null;
        entry = await attach(page, conversationId);
      }
      await entry.page.bringToFront();
      try { await entry.page.goto(url, { waitUntil: "domcontentloaded", timeout: 30_000 }); }
      catch (error) {
        if (entry.page.isClosed()) throw new CollectStop("TAB_CLOSED");
        if (error.name === "TimeoutError") throw new CollectStop("PAGE_LOAD_TIMEOUT");
        throw error;
      }
      return entry.id;
    },
    async waitForLoad(id) { return { url: pageFor(id).url() }; },
    async readPage(id, operation, options) {
      const page = pageFor(id);
      const blocker = list.blockerFromUrl(page.url());
      if (blocker) throw new CollectStop(blocker);
      const snapshot = await page.evaluate(async ({ scripts, operation, options }) => {
        for (const script of scripts) (0, eval)(script);
        return globalThis.ResumeLensBossListPage[operation](options);
      }, { scripts, operation, options });
      const captured = captures.get(id).splice(0);
      return api.combine(snapshot, captured);
    },
    async readDetail(id) {
      return pageFor(id).evaluate(async (scripts) => {
        for (const script of scripts) (0, eval)(script);
        const deadline = Date.now() + 15_000;
        do {
          const state = globalThis.ResumeLensBossListExtractor.inspect(document, location.href);
          if (state.kind === "blocked") return { blocked: state.code };
          const body = globalThis.ResumeLensBossExtractor.SELECTORS.description.some((selector) =>
            [...document.querySelectorAll(selector)].some((node) => String(node.innerText || node.textContent || "").trim().length >= 60));
          const detail = globalThis.ResumeLensBossExtractor.extract(document, location.href);
          if (body && detail.job.description.length >= 60) return detail;
          await new Promise((resolve) => setTimeout(resolve, 400));
        } while (Date.now() < deadline);
        return null;
      }, scripts);
    },
    async showProgress(id, task) {
      await pageFor(id).evaluate(({ scripts, task, returnUrl }) => {
        for (const script of scripts) (0, eval)(script);
        globalThis.ResumeLensCollectProgress.render(task, returnUrl);
      }, { scripts: bannerScripts, task, returnUrl });
    },
    async close() { await context.close(); },
  };
}

export const userProfileDir = (dataDir, userId) => join(dataDir, "profiles", String(userId));
