import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import React, { act } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { JSDOM } from "jsdom";
import * as steps from "../app/agent-steps.ts";

const require = createRequire(import.meta.url);
const exports = {};
const source = readFileSync(new URL("../app/components/assistant/StepTimeline.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
}).outputText;
vm.runInNewContext(compiled, {
  exports,
  require: (id) => {
    if (id === "../../agent-steps") return steps;
    if (id === "../SideDrawer") return {
      SideDrawer: ({ children }) => React.createElement("section", { id: "process-drawer" }, children),
    };
    return require(id);
  },
});

const unusualTools = ["constructor", "__proto__", "toString", "hasOwnProperty", "valueOf"];
const finishedStep = (tool, id = "step") => ({
  toolCallId: id, tool, arguments: "{}", ok: true, latencyMs: 1, resultChars: 2, resultPreview: "{}",
  errorCode: null, facts: null,
});
const turnFor = (tool, completed) => ({
  steps: completed ? [finishedStep(tool)] : [],
  started: completed ? [] : [{ toolCallId: "step", tool, arguments: "{}" }],
  unfinished: [], browserTask: null, confirmation: null, answer: completed ? "完成" : "",
});

for (const tool of unusualTools) {
  test(`unknown tool ${tool} always has a renderable fallback icon`, () => {
    const meta = steps.toolMeta(tool);
    assert.equal(meta.label, tool);
    assert.doesNotThrow(() => renderToStaticMarkup(React.createElement(meta.icon, { size: 16 })));
  });

  test(`StepTimeline renders ${tool} while running and when folded after completion`, () => {
    assert.doesNotThrow(() => renderToStaticMarkup(React.createElement(exports.StepTimeline, {
      turn: turnFor(tool, false), pendingId: null, thinking: true, open: false, onOpen: () => {},
    })));
    const folded = renderToStaticMarkup(React.createElement(exports.StepTimeline, {
      turn: turnFor(tool, true), pendingId: null, thinking: false, open: false, onOpen: () => {},
    }));
    assert.match(folded, /process-summary/);
    assert.doesNotThrow(() => renderToStaticMarkup(React.createElement(exports.ProcessDrawer, {
      turn: turnFor(tool, true), turnKey: 0, pendingId: null, onClose: () => {},
    })));
  });
}

test("a broken step falls back without hiding the other steps or messages", async () => {
  const dom = new JSDOM("<!doctype html><html><body><div id='root'></div></body></html>");
  const previous = {
    window: globalThis.window, document: globalThis.document, HTMLElement: globalThis.HTMLElement,
    IS_REACT_ACT_ENVIRONMENT: globalThis.IS_REACT_ACT_ENVIRONMENT,
  };
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.HTMLElement = dom.window.HTMLElement;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const previousError = console.error;
  console.error = () => {}; // React reports the intentionally thrown render error.
  let root;
  try {
    const { createRoot } = await import("react-dom/client");
    root = createRoot(dom.window.document.getElementById("root"));
    const broken = { ...finishedStep("collect_boss_jobs", "bad"), facts: {
      get pagesRead() { throw new Error("deliberate step rendering failure"); },
    } };
    const turn = {
      steps: [finishedStep("list_resumes", "first"), broken, finishedStep("search_jobs", "last")],
      started: [], unfinished: [], browserTask: null, confirmation: null, answer: "",
    };
    await act(async () => {
      root.render(React.createElement("main", null,
        React.createElement("p", null, "其他消息仍在"),
        React.createElement(exports.StepTimeline, {
          turn, pendingId: null, thinking: true, open: false, onOpen: () => {},
        }),
      ));
    });
    const text = dom.window.document.body.textContent;
    assert.match(text, /其他消息仍在/);
    assert.match(text, /读取简历列表/);
    assert.match(text, /搜索职位库/);
    assert.match(text, /该步骤无法展示/);
  } finally {
    if (root) await act(async () => root.unmount());
    console.error = previousError;
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
    dom.window.close();
  }
});
