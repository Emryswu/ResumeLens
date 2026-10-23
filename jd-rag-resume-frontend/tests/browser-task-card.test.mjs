import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import vm from "node:vm";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { JSDOM } from "jsdom";
import * as activity from "../app/collection-log.ts";

const require = createRequire(import.meta.url);
const exports = {};
const code = ts.transpileModule(readFileSync(new URL("../app/components/assistant/BrowserTaskCard.tsx", import.meta.url), "utf8"), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
}).outputText;
vm.runInNewContext(code, { exports, require: (id) => id === "../../collection-log" ? activity : require(id) });

test("the real collection card renders persisted page logs after the task is finished", () => {
  const html = renderToStaticMarkup(React.createElement(exports.BrowserTaskCard, {
    task: { command: { keyword: "Java", city: "深圳", maxPages: 3 }, action: "collectBossJobs", expiresAt: 0 },
    facts: { pagesRead: 1, pageLog: [{ page: 1, api: 2, dom: 1 }], finishReason: "NO_MORE_RESULTS" },
    active: false, running: false, outcome: "completed",
  }));
  assert.match(html, /采集操作日志/);
  assert.match(html, /第 1 页读到 3 条（接口 2 条 \/ 页面 1 条）/);
  assert.match(html, /可能未登录 BOSS，只能看到第一页/);
});

test("all completed batches remain visible after a later detail verification in the same turn", () => {
  const html = renderToStaticMarkup(React.createElement(exports.CompletedCollectionCards, { turn: {
    steps: [1, 2, 3].map((id) => ({ tool: "collect_boss_jobs", ok: true, toolCallId: String(id),
      arguments: JSON.stringify({ keyword: `Java${id}`, maxPages: 1 }),
      facts: { pagesRead: 1, pageLog: [{ page: 1, api: id, dom: 0 }], finishReason: "MAX_PAGES" } })),
    browserTask: { toolCallId: "detail" },
  } }));
  assert.equal((html.match(/采集操作日志/g) ?? []).length, 3);
});

test("the BOSS banner keeps a Chinese summary and return button after completion without stacking banners", () => {
  const dom = new JSDOM("<html><body></body></html>", { runScripts: "outside-only" });
  const context = dom.getInternalVMContext();
  for (const file of ["collect-status.js", "content-collect-progress.js"]) {
    vm.runInContext(readFileSync(new URL(`../../browser-extension/src/${file}`, import.meta.url), "utf8"), context);
  }
  const task = { status: "running", phase: "reading", pagesRead: 1, jobs: [{}], pageLog: [{ page: 1, api: 1, dom: 0 }] };
  dom.window.ResumeLensCollectProgress.render(task, "http://127.0.0.1:3000/assistant");
  dom.window.ResumeLensCollectProgress.render({ ...task, status: "completed", finishReason: "NO_MORE_RESULTS" }, "http://127.0.0.1:3000/assistant");
  assert.equal(dom.window.document.querySelectorAll("#resumelens-collect-progress").length, 1);
  const banner = dom.window.document.querySelector("#resumelens-collect-progress").shadowRoot;
  assert.match(banner.textContent, /可能未登录 BOSS，只能看到第一页/);
  assert.equal(banner.querySelector("a").textContent, "返回 ResumeLens");
  assert.equal(banner.querySelector("a").href, "http://127.0.0.1:3000/assistant");
  dom.window.close();
});
