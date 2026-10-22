(function exposeBossApiHook(root, factory) {
  const hook = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = hook;
  } else {
    hook.install(root);
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function createBossApiHook() {
  "use strict";

  /** Must equal BUFFER_KEY and LIST_API_PATHS in boss-api-extractor.js (checked by tests). */
  const BUFFER_KEY = "__resumeLensBossListCapture";
  const LIST_API_PATHS = Object.freeze(["/wapi/zpgeek/search/joblist.json"]);
  const MAX_ENTRIES = 10;
  const MAX_BODY_CHARS = 1_000_000;

  /**
   * Runs in the page's own JavaScript world at document_start, before BOSS's scripts, and only
   * while a collection task is running (the service worker registers it for the task and
   * unregisters it afterwards).
   *
   * It wraps fetch and XMLHttpRequest for one purpose: keeping a copy of the job list responses
   * the page itself asked for. Requests go out unchanged, responses reach the page unchanged, and
   * nothing here starts a request of its own. Copies wait in a small buffer until the extension
   * drains it; only the path is kept, never the query string with the user's search.
   */
  function install(win) {
    if (!win || win[BUFFER_KEY]) return;
    const entries = [];
    Object.defineProperty(win, BUFFER_KEY, {
      value: Object.freeze({ drain: () => entries.splice(0) }),
    });

    const pageHref = () => (win.location && win.location.href) || undefined;
    const record = (path, body) => {
      if (typeof body !== "string" || body.length > MAX_BODY_CHARS) return;
      entries.push({ path, body });
      if (entries.length > MAX_ENTRIES) entries.shift();
    };

    const originalFetch = win.fetch;
    if (typeof originalFetch === "function") {
      win.fetch = function fetch(input) {
        const pending = originalFetch.apply(this, arguments);
        const path = listApiPath(requestUrl(input), pageHref());
        if (path) {
          // Registered before the page sees the promise, so the clone happens before the page
          // reads the body. Failures here never reach the page.
          pending
            .then((response) => response.clone().text())
            .then((body) => record(path, body), () => {});
        }
        return pending;
      };
    }

    const Xhr = win.XMLHttpRequest;
    if (Xhr && Xhr.prototype) {
      const paths = new WeakMap();
      const originalOpen = Xhr.prototype.open;
      const originalSend = Xhr.prototype.send;
      Xhr.prototype.open = function open(method, url) {
        const path = listApiPath(String(url), pageHref());
        if (path) paths.set(this, path);
        else paths.delete(this);
        return originalOpen.apply(this, arguments);
      };
      Xhr.prototype.send = function send() {
        const path = paths.get(this);
        if (path) {
          this.addEventListener("load", () => {
            try {
              record(path, xhrBody(this));
            } catch {
              // An unreadable body just means this response is not captured.
            }
          });
        }
        return originalSend.apply(this, arguments);
      };
    }
  }

  function requestUrl(input) {
    try {
      if (typeof input === "string") return input;
      if (input && typeof input.url === "string") return input.url;
      return String(input);
    } catch {
      return "";
    }
  }

  function xhrBody(xhr) {
    if (xhr.responseType === "" || xhr.responseType === "text") return xhr.responseText;
    if (xhr.responseType === "json") return JSON.stringify(xhr.response);
    return null;
  }

  /** The matched list API path, or null for every other request. */
  function listApiPath(value, base) {
    try {
      const url = new URL(value, base);
      if (url.protocol !== "https:" || !/(^|\.)zhipin\.com$/i.test(url.hostname)) return null;
      return LIST_API_PATHS.includes(url.pathname) ? url.pathname : null;
    } catch {
      return null;
    }
  }

  return { BUFFER_KEY, LIST_API_PATHS, MAX_BODY_CHARS, MAX_ENTRIES, install, listApiPath };
});
