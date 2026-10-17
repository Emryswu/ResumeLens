import assert from "node:assert/strict";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";
import { JSDOM } from "jsdom";
import ts from "typescript";

// SideDrawer mounted for real in a DOM (jsdom), so the focus rules are checked through React's effect lifecycle.
const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dom = new JSDOM("<!doctype html><html><body></body></html>", { pretendToBeVisual: true });
Object.assign(globalThis, {
  window: dom.window,
  document: dom.window.document,
  HTMLElement: dom.window.HTMLElement,
  Node: dom.window.Node,
  KeyboardEvent: dom.window.KeyboardEvent,
  IS_REACT_ACT_ENVIRONMENT: true,
});
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });

const { act, createElement } = await import("react");
const { createRoot } = await import("react-dom/client");

async function loadSideDrawer() {
  const source = readFileSync(join(root, "app/components/SideDrawer.tsx"), "utf8");
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  });
  const dir = join(root, "node_modules/.cache/side-drawer-test");
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `SideDrawer-${process.pid}.mjs`);
  writeFileSync(file, outputText);
  return (await import(pathToFileURL(file).href)).SideDrawer;
}
const SideDrawer = await loadSideDrawer();

/** Two summary buttons outside the panel, like two turns of the chat, and a mounted drawer the test drives. */
function setup() {
  document.body.innerHTML = "";
  const button = (name: string) => {
    const element = document.createElement("button");
    element.textContent = name;
    document.body.append(element);
    return element;
  };
  const a = button("A");
  const b = button("B");
  const container = document.createElement("div");
  document.body.append(container);
  const reactRoot = createRoot(container);
  const show = (key: string | null) => act(() => reactRoot.render(key == null ? null : createElement(SideDrawer, {
    id: "panel", title: `第 ${key} 轮`, closeLabel: "关闭", focusKey: key, onClose: () => { reactRoot.render(null); },
    children: createElement("p", null, key),
  })));
  const escape = () => act(() => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); });
  const title = () => document.getElementById("panel-title");
  return { a, b, show, escape, title, unmount: () => act(() => reactRoot.unmount()) };
}

test("opening the panel focuses its title, and Escape returns focus to the button that opened it", async () => {
  const { a, show, escape, title, unmount } = setup();
  a.focus();
  await show("A");
  assert.equal(document.activeElement, title());
  await escape();
  assert.equal(document.getElementById("panel"), null);
  assert.equal(document.activeElement, a);
  await unmount();
});

test("open A, switch to B, Escape: focus returns to B, and the switch moves focus to the new title", async () => {
  const { a, b, show, escape, title, unmount } = setup();
  a.focus();
  await show("A");
  b.focus();
  await show("B");
  assert.equal(title()?.textContent, "第 B 轮");
  assert.equal(document.activeElement, title());
  await escape();
  assert.equal(document.activeElement, b);
  await unmount();
});

test("focus inside the panel never becomes the button to return to", async () => {
  const { a, show, escape, unmount } = setup();
  a.focus();
  await show("A");
  // A control inside the panel switches its content, e.g. another snippet; the opener stays A.
  (document.querySelector("#panel button") as HTMLElement).focus();
  await show("A2");
  await escape();
  assert.equal(document.activeElement, a);
  await unmount();
});
