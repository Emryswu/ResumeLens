import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  HOME_PATH, NAV_ITEMS, WORKSPACE_PATH, hashForView, isHashView, legacyWorkspaceUrl, navLabel, reportHref, viewFromHash,
  workspaceHref, type HashView,
} from "../app/workspace-nav.ts";

const appDir = join(dirname(fileURLToPath(import.meta.url)), "../app");
const read = (path: string) => readFileSync(join(appDir, path), "utf8");

test("导航项各不相同，且都有标签和链接", () => {
  assert.equal(new Set(NAV_ITEMS.map((item) => item.view)).size, NAV_ITEMS.length);
  for (const item of NAV_ITEMS) {
    assert.ok(item.label.trim(), `${item.view} 缺少标签`);
    assert.match(item.href, /^\//);
  }
});

test("hash 与视图互相转换，未知 hash 回到岗位匹配", () => {
  for (const item of NAV_ITEMS) {
    if (!isHashView(item.view)) continue;
    assert.equal(item.href, `${WORKSPACE_PATH}${hashForView(item.view)}`);
    assert.equal(viewFromHash(hashForView(item.view)), item.view);
  }
  assert.equal(viewFromHash(""), "match");
  assert.equal(viewFromHash("#workflow"), "match");
  assert.equal(viewFromHash("#assistant"), "match");
  assert.equal(viewFromHash("jobs"), "jobs");
});

test("AI 助手是首页，排在导航第一位；工作台在 /workspace", () => {
  assert.equal(NAV_ITEMS[0].view, "assistant");
  assert.equal(NAV_ITEMS[0].href, HOME_PATH);
  assert.equal(isHashView("assistant"), false);
  for (const item of NAV_ITEMS.slice(1)) assert.ok(item.href.startsWith(`${WORKSPACE_PATH}#`), item.href);
});

test("工作台链接带上预选的简历、职位和报告", () => {
  assert.equal(workspaceHref(), "/workspace#workflow");
  assert.equal(workspaceHref("jobs"), "/workspace#jobs");
  assert.equal(workspaceHref("match", { jobId: 7 }), "/workspace?jobId=7#workflow");
  assert.equal(reportHref({ analysisId: 9 }), "/workspace?analysisId=9#result");
  assert.equal(reportHref({ resumeId: 1, jobId: 2, analysisId: 3 }), "/workspace?resumeId=1&jobId=2&analysisId=3#result");
});

test("旧的工作台链接从首页转到 /workspace，原样保留查询和锚点", () => {
  assert.equal(legacyWorkspaceUrl("?resumeId=1&jobId=2&analysisId=3", "#result"), "/workspace?resumeId=1&jobId=2&analysisId=3#result");
  assert.equal(legacyWorkspaceUrl("?analysisId=3", ""), "/workspace?analysisId=3");
  assert.equal(legacyWorkspaceUrl("", "#jobs"), "/workspace#jobs");
  assert.equal(legacyWorkspaceUrl("", "#workflow"), "/workspace#workflow");
  assert.equal(legacyWorkspaceUrl("?jobId=5", ""), "/workspace?jobId=5");
  // The home page itself, and anything that was never a workbench link, stays.
  assert.equal(legacyWorkspaceUrl("", ""), null);
  assert.equal(legacyWorkspaceUrl("?utm_source=x", "#top"), null);
});

/** Every source file under app/, as a path relative to it. */
function appSources(dir = appDir): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return appSources(path);
    return /\.(ts|tsx)$/.test(entry.name) ? [relative(appDir, path).replaceAll("\\", "/")] : [];
  });
}

test("站内链接只在 workspace-nav.ts 里拼，别处不手写 \"/#…\"、\"/?…\" 或 \"/\"", () => {
  // Hand-written links are how the workbench move could have left dead "/#jobs" links behind.
  const handWritten = /["'`]\/[#?]|href="\/"|\b(?:replace|push)\(\s*["'`]\/["'`]/;
  const offenders = appSources()
    .filter((file) => file !== "workspace-nav.ts" && !file.startsWith("api/"))
    .filter((file) => handWritten.test(read(file)));
  assert.deepEqual(offenders, []);
});

test("页面标题取自导航标签", () => {
  const views: HashView[] = ["match", "resumes", "jobs", "saved", "history"];
  for (const view of views) assert.ok(navLabel(view));
  assert.equal(navLabel("jobs"), "职位库");
});

test("所有登录后页面共用 AppShell，不再各写一套外壳", () => {
  for (const page of ["page.tsx", "workspace/page.tsx", "jobs/[id]/page.tsx", "resumes/[id]/page.tsx"]) {
    const source = read(page);
    assert.match(source, /<AppShell\b/, `${page} 应当使用 AppShell`);
    assert.doesNotMatch(source, /AppChrome|WorkspaceNav|className="app-shell/, `${page} 不应再自带外壳`);
  }
});
