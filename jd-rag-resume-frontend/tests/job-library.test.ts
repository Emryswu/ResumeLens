import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";
import { JSDOM } from "jsdom";
import ts from "typescript";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const cache = join(root, "node_modules/.cache/job-library-test", String(process.pid));
mkdirSync(cache, { recursive: true });
const link = join(cache, "Link.mjs");
writeFileSync(link, 'import {createElement} from "react"; export default function Link(props) { return createElement("a", props); }');

// Compile the real page and its components, replacing only Next's navigation wrapper.
const compiled = new Map<string, string>();
function compile(source: string): string {
  const previous = compiled.get(source);
  if (previous) return previous;
  const target = join(cache, relative(root, source).replace(/\.tsx?$/, ".mjs"));
  compiled.set(source, target);
  const { outputText } = ts.transpileModule(readFileSync(source, "utf8"), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  });
  const output = outputText.replace(/from "([^"]+)"/g, (statement, specifier: string) => {
    if (specifier === "next/link") return `from "${pathToFileURL(link).href}"`;
    if (!specifier.startsWith(".")) return statement;
    const base = resolve(dirname(source), specifier);
    const dependency = [base, `${base}.ts`, `${base}.tsx`].find(existsSync);
    assert.ok(dependency, `Cannot resolve ${specifier} in ${source}`);
    return `from "${pathToFileURL(compile(dependency)).href}"`;
  });
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, output);
  return target;
}

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/workspace#jobs", pretendToBeVisual: true });
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement,
  Node: dom.window.Node, localStorage: dom.window.localStorage,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window), IS_REACT_ACT_ENVIRONMENT: true,
});
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
dom.window.HTMLElement.prototype.scrollIntoView = () => {};
const { act, createElement } = await import("react");
const { createRoot } = await import("react-dom/client");
const { default: Home } = await import(pathToFileURL(compile(join(root, "app/workspace/page.tsx"))).href);

for (const hasResume of [false, true]) {
  test(hasResume ? "向量服务 503 时仍能查看、编辑和删除已有岗位" : "没有简历时仍能查看、编辑和删除已有岗位", async (t) => {
    const originalFetch = globalThis.fetch;
    const requests: string[] = [];
    const job = { id: 1, title: "Java 后端实习", companyName: "测试公司", description: "Spring Boot" };
    globalThis.fetch = async (input, init) => {
      const url = String(input);
      requests.push(url);
      let data: unknown;
      if (url.endsWith("/actuator/health")) return Response.json({ status: "UP" });
      if (url.endsWith("/api/auth/refresh")) data = { accessToken: "test-token", user: { id: 1, username: "test", displayName: "测试" } };
      else if (url.endsWith("/api/ai/status")) data = { mockEnabled: true };
      else if (url.includes("/api/resumes?")) data = { content: hasResume ? [{ id: 10, title: "简历", candidateName: "测试" }] : [], totalElements: hasResume ? 1 : 0 };
      else if (url.includes("/api/job-descriptions?")) data = { content: [job], totalElements: 1 };
      else if (url.includes("/api/analysis-histories?")) data = { content: [], totalElements: 0 };
      else if (url.includes("/latest-by-resume?")) data = [];
      else if (url.includes("/matches?")) return Response.json({ success: false, code: "EMBEDDING_UNAVAILABLE", message: "向量服务暂不可用" }, { status: 503 });
      else if (url.endsWith("/api/job-descriptions/1") && init?.method === "DELETE") data = null;
      else throw new Error(`Unexpected request: ${url}`);
      return Response.json({ success: true, data });
    };
    document.body.innerHTML = "<div id='root'></div>";
    window.history.replaceState(null, "", "/workspace#jobs");
    window.confirm = () => true;
    const reactRoot = createRoot(document.getElementById("root")!);
    t.after(async () => { await act(() => reactRoot.unmount()); globalThis.fetch = originalFetch; });
    await act(() => reactRoot.render(createElement(Home)));
    for (let i = 0; i < 4; i++) await act(async () => { await new Promise((done) => setTimeout(done, 10)); });
    const library = document.getElementById("jobs")!;
    assert.equal(library.hidden, false);
    assert.equal(library.querySelectorAll(".job-card").length, 1);
    assert.match(library.textContent!, /按最近保存展示/);
    assert.equal(library.querySelector<HTMLAnchorElement>(".job-card h3 a")?.getAttribute("href"), "/jobs/1");
    if (hasResume) {
      assert.ok(requests.some((url) => url.includes("/matches?")));
      assert.match(library.textContent!, /向量服务暂不可用/);
    }
    await act(() => library.querySelector<HTMLButtonElement>('[aria-label="编辑岗位"]')!.click());
    assert.equal(library.querySelector<HTMLInputElement>("#job-editor input")?.value, job.title);
    await act(async () => { library.querySelector<HTMLButtonElement>('[aria-label="删除岗位"]')!.click(); });
    assert.equal(library.querySelectorAll(".job-card").length, 0);
    assert.ok(requests.some((url) => url.endsWith("/api/job-descriptions/1")));
  });
}
