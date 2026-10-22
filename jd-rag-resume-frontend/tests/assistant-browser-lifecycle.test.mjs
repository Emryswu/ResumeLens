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
import * as modelDisplay from "../app/agent-model-display.ts";

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
async function mount(t, { interrupted = false, savedEffort, autoAsk = true } = {}) {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost:3000" });
  if (savedEffort != null) dom.window.localStorage.setItem("resumelens-agent-reasoning-effort", savedEffort);
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
    apiRequest: async (url) => url.startsWith("/api/resumes") ? { totalElements: 1 } : { mockEnabled: true, model: "deepseek-flash" },
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
  const choiceExports = {};
  const choiceCode = ts.transpileModule(readFileSync(new URL("../app/components/ChoiceMenu.tsx", import.meta.url), "utf8"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(choiceCode, {
    exports: choiceExports, document: dom.window.document,
    require(id) {
      if (id === "lucide-react") return new Proxy({}, { get: () => empty });
      return require(id);
    },
  });
  vm.runInNewContext(code, {
    exports, window: dom.window, AbortController,
    require(id) {
      if (id === "react" || id === "react/jsx-runtime") return require(id);
      if (id === "lucide-react") return new Proxy({}, { get: () => empty });
      if (id === "next/link") return { default: empty };
      if (id === "next/navigation") return { useRouter: () => ({ replace() {} }) };
      if (id.endsWith("/use-session-user")) return { useSessionUser: () => ({ user: { id: 1 }, status: "signedIn", logout() {} }) };
      if (id.endsWith("/agent-stream")) return wire;
      if (id.endsWith("/agent-model-display")) return modelDisplay;
      if (id.endsWith("/components/ChoiceMenu")) return choiceExports;
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
  if (autoAsk) await click("我最新那份简历");
  return { dom, requests, click, unmount, get collections() { return collections; }, get collectionSignal() { return collectionSignal; }, finish: (outcome) => act(async () => { finishCollection(outcome); }) };
}

test("thinking defaults to high and stays fixed through browser-task continuation", async (t) => {
  const page = await mount(t);
  const trigger = page.dom.window.document.querySelector('button[aria-haspopup="listbox"]');
  assert.ok(trigger);
  assert.match(trigger.textContent, /DeepSeek Flash/);
  assert.match(trigger.textContent, /高/);
  assert.equal(trigger.disabled, true);
  assert.equal(page.requests[0].body.reasoningEffort, "high");
  await page.finish({ outcome: "completed", report: { type: "result" } });
  assert.equal(page.requests[1].body.reasoningEffort, "high");
  assert.equal(trigger.disabled, false);
});

test("thinking restores the previous selection and persists a change for the next question", async (t) => {
  const page = await mount(t, { savedEffort: "low", autoAsk: false });
  const trigger = page.dom.window.document.querySelector('button[aria-haspopup="listbox"]');
  assert.ok(trigger);
  assert.match(trigger.textContent, /低/);
  await act(async () => { trigger.click(); });
  const options = [...page.dom.window.document.querySelectorAll('[role="option"]')];
  assert.deepEqual(options.map((option) => option.querySelector('.choice-menu-option-label').textContent), ["低", "高", "最高"]);
  assert.equal(options[0].getAttribute("aria-selected"), "true");
  await act(async () => {
    options[2].click();
  });
  assert.equal(page.dom.window.localStorage.getItem("resumelens-agent-reasoning-effort"), "max");
  await page.click("我最新那份简历");
  assert.equal(page.requests[0].body.reasoningEffort, "max");
});

test("Escape and outside pointer presses close the menu without changing the effort", async (t) => {
  const page = await mount(t, { autoAsk: false });
  const doc = page.dom.window.document;
  const trigger = doc.querySelector('button[aria-haspopup="listbox"]');
  assert.ok(trigger);
  await act(async () => { trigger.click(); });
  assert.equal(trigger.getAttribute("aria-expanded"), "true");
  await act(async () => {
    doc.activeElement.dispatchEvent(new page.dom.window.KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  });
  assert.equal(doc.querySelector('[role="listbox"]'), null);
  assert.equal(doc.activeElement, trigger);
  await act(async () => { trigger.click(); });
  await act(async () => { doc.body.dispatchEvent(new page.dom.window.Event("pointerdown", { bubbles: true })); });
  assert.equal(trigger.getAttribute("aria-expanded"), "false");
  assert.equal(doc.querySelector('[role="listbox"]'), null);
  assert.equal(page.requests.length, 0);
});

test("arrow keys move focus and Enter selects an effort without submitting the form", async (t) => {
  const page = await mount(t, { autoAsk: false });
  const doc = page.dom.window.document;
  const trigger = doc.querySelector('button[aria-haspopup="listbox"]');
  assert.ok(trigger);
  await act(async () => { trigger.click(); });
  const options = [...doc.querySelectorAll('[role="option"]')];
  assert.equal(doc.activeElement, options[1]);
  const key = async (value) => act(async () => {
    doc.activeElement.dispatchEvent(new page.dom.window.KeyboardEvent("keydown", { key: value, bubbles: true, cancelable: true }));
  });
  await key("ArrowUp");
  assert.equal(doc.activeElement, options[0]);
  await key("ArrowDown");
  await key("ArrowDown");
  assert.equal(doc.activeElement, options[2]);
  await key("Enter");
  assert.equal(doc.querySelector('[role="listbox"]'), null);
  assert.equal(doc.activeElement, trigger);
  assert.equal(page.requests.length, 0);
  await page.click("我最新那份简历");
  assert.equal(page.requests[0].body.reasoningEffort, "max");
});

test("a previous off preference migrates to high", async (t) => {
  const page = await mount(t, { savedEffort: "off" });
  assert.equal(page.requests[0].body.reasoningEffort, "high");
});

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
