(function installBossListPage() {
  "use strict";
  if (globalThis.ResumeLensBossListPage) return;

  const POLL_MS = 400;

  /**
   * Read-only helpers the service worker calls through chrome.scripting.executeScript. Nothing
   * here clicks, types or submits: the only interaction is scrolling the result list.
   */
  globalThis.ResumeLensBossListPage = Object.freeze({
    /** Waits until cards, an empty result, a blocker or the deadline appears, then reads the page. */
    async read(options) {
      const state = await waitFor((current) => current.kind !== "unknown", options && options.waitMs);
      return snapshot(state);
    },

    /** Scrolls to the bottom and waits for the list to grow past {@code previousCount} cards. */
    async scrollAndWait(options) {
      const previousCount = Number(options && options.previousCount) || 0;
      const cards = extractor().findCards(document);
      const last = cards[cards.length - 1];
      if (last && typeof last.scrollIntoView === "function") last.scrollIntoView({ block: "end" });
      window.scrollTo(0, document.documentElement.scrollHeight);
      const state = await waitFor(
        (current) => current.kind === "blocked" || (current.kind === "ready" && current.cardCount > previousCount),
        options && options.waitMs,
      );
      return snapshot(state);
    },
  });

  function snapshot(state) {
    if (state.kind !== "ready") return { state, jobs: [], skippedWithoutId: 0 };
    return { state, ...extractor().extractCards(document, window.location.href) };
  }

  async function waitFor(done, waitMs) {
    const deadline = Date.now() + Math.max(0, Math.min(Number(waitMs) || 0, 20_000));
    let state = extractor().inspect(document, window.location.href);
    while (!done(state) && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, POLL_MS));
      state = extractor().inspect(document, window.location.href);
    }
    return state;
  }

  function extractor() {
    const value = globalThis.ResumeLensBossListExtractor;
    if (!value) throw new Error("列表抓取器没有加载");
    return value;
  }
})();
