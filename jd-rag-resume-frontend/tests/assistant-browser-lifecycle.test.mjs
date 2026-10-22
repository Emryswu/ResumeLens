import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import vm from "node:vm";
import test from "node:test";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { JSDOM } from "jsdom";
import ts from "typescript";
import * as wire from "../app/agent-stream.ts";

const require = createRequire(import.meta.url);
const TASK = {
  toolCallId: "b1", tool: "collect_boss_jobs", taskId: "bt_test", executor: "browser-extension",
  action: "collectBossJobs", command: { keyword: "Java", city: "深圳", maxPages: 1 },
  expiresAt: 180_000, timeoutSeconds: 180,
};
const source = readFileSync(new URL("../app/page.tsx", import.meta.url), "utf8");
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
}).outputText;

/** Mount the real page and reducer; replace network and extension I/O with controlled promises. */
async function mount(t, { interrupted = false } = {}) {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost:3000" });
  const previous = { window: globalThis.window, document: globalThis.document, act: globalThis.IS_REACT_ACT_ENVIRONMENT };
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  dom.window.HTMLElement.prototype.scrollIntoView = function () {};
  const requests = [];
  let collections = 0;
  let collectionSignal;
  let finishCollection;
  const frame = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  const api = {
    ApiError: class ApiError extends Error {},
    apiRequest: async (url) => url.startsWith("/api/resumes") ? { totalElements: 1 } : { mockEnabled: true, model: "mock" },
    getAuthSessionId: () => 1,
    isAuthSessionCurrent: () => true,
    visibleApiErrorMessage: (error) => error.message,
    async apiStream(path, body, signal) {
      requests.push({ path, body, signal });
      let events;
      if (requests.length === 1) {
        const transcript = [...body.messages, {
          role: "assistant", tool_calls: [{ id: TASK.toolCallId, type: "function", function: { name: TASK.tool, arguments: "{}" } }],
        }];
        events = frame("browser_task_required", TASK);
        if (!interrupted) events += frame("done", { state: "AWAITING_BROWSER", steps: 1, transcript });
      } else {
        events = frame("done", { state: "COMPLETED", steps: 1, transcript: body.messages });
      }
      return new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode(events)); controller.close(); } });
    },
  };
  const empty = () => null;
  const components = {
    AppShell: ({ children }) => React.createElement("div", null, children),
    RetryButton: empty, AgentMarkdown: empty, AnalysisCard: empty, ConfirmationCard: empty,
    ProcessDrawer: empty, StepTimeline: empty, LoginPage: empty,
    BrowserTaskCard: ({ active, onCancel }) => active ? React.createElement("button", { onClick: onCancel }, "取消采集") : null,
  };
  const exports = {};
  vm.runInNewContext(code, {
    exports, window: dom.window, AbortController,
    require(id) {
      if (id === "react" || id === "react/jsx-runtime") return require(id);
      if (id === "lucide-react") return new Proxy({}, { get: () => empty });
      if (id === "next/link") return { default: empty };
      if (id === "next/navigation") return { useRouter: () => ({ replace() {} }) };
      if (id.endsWith("/use-session-user")) return { useSessionUser: () => ({ user: { id: 1 }, status: "signedIn", logout() {} }) };
      if (id.endsWith("/agent-stream")) return wire;
      if (id.endsWith("/lib/api")) return api;
      if (id.endsWith("/extension-collect")) return {
        runExtensionCollect({ signal }) {
          collections++;
          collectionSignal = signal;
          return new Promise((resolve) => {
            finishCollection = resolve;
            signal.addEventListener("abort", () => resolve({ outcome: "cancelled" }), { once: true });
          });
        },
      };
      if (id.includes("/components/")) return components;
      if (id.endsWith("/analysis-poll")) return {};
      if (id.endsWith("/runtime-mode")) return { runtimeModeLabel: () => "mock" };
      if (id.endsWith("/sample-data") || id.endsWith("/sign-in")) return {};
      if (id.endsWith("/workspace-nav")) return { legacyWorkspaceUrl: () => null };
      throw new Error(`Unexpected import: ${id}`);
    },
  });
  const root = createRoot(dom.window.document.getElementById("root"));
  let mounted = true;
  const unmount = async () => {
    await act(async () => { root.unmount(); });
    mounted = false;
  };
  t.after(async () => {
    try {
      if (mounted) await unmount();
    } finally {
      dom.window.close();
      globalThis.window = previous.window;
      globalThis.document = previous.document;
      globalThis.IS_REACT_ACT_ENVIRONMENT = previous.act;
    }
  });
  await act(async () => { root.render(React.createElement(exports.default)); });
  const click = async (text) => {
    const button = [...dom.window.document.querySelectorAll("button")].find((item) => item.textContent.includes(text));
    assert.ok(button, `Missing button: ${text}`);
    await act(async () => { button.click(); });
  };
  await click("我最新那份简历");
  return { requests, click, unmount, get collections() { return collections; }, get collectionSignal() { return collectionSignal; }, finish: (outcome) => act(async () => { finishCollection(outcome); }) };
}

test("a stream interrupted before done never starts browser collection", async (t) => {
  const page = await mount(t, { interrupted: true });
  assert.equal(page.collections, 0);
  assert.equal(page.requests.length, 1);
});

test("unmount cancels the extension without starting a new assistant request", async (t) => {
  const page = await mount(t);
  assert.equal(page.collections, 1);
  await page.unmount();
  assert.equal(page.collectionSignal.aborted, true);
  assert.equal(page.requests.length, 1);
});

test("the cancel button still reports cancellation to the assistant while mounted", async (t) => {
  const page = await mount(t);
  await page.click("取消采集");
  assert.equal(page.collectionSignal.aborted, true);
  assert.equal(page.requests.length, 2);
  assert.equal(page.requests[1].body.browserResult.outcome, "cancelled");
  assert.equal(page.requests[1].body.messages.at(-1).tool_calls[0].id, TASK.toolCallId);
});

test("a new conversation cancels collection without resuming the old turn", async (t) => {
  const page = await mount(t);
  await page.click("新对话");
  assert.equal(page.collectionSignal.aborted, true);
  assert.equal(page.requests.length, 1);
});

test("a completed collection reports against the transcript received in done", async (t) => {
  const page = await mount(t);
  await page.finish({ outcome: "completed", report: { type: "result" } });
  assert.equal(page.requests.length, 2);
  assert.equal(page.requests[1].body.browserResult.taskId, TASK.taskId);
  assert.equal(page.requests[1].body.messages.at(-1).tool_calls[0].id, TASK.toolCallId);
});
