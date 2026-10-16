import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { NAV_ITEMS, hashForView, isHashView, navLabel, viewFromHash, type HashView } from "../app/workspace-nav.ts";

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
    assert.equal(item.href, `/${hashForView(item.view)}`);
    assert.equal(viewFromHash(hashForView(item.view)), item.view);
  }
  assert.equal(viewFromHash(""), "match");
  assert.equal(viewFromHash("#workflow"), "match");
  assert.equal(viewFromHash("#assistant"), "match");
  assert.equal(viewFromHash("jobs"), "jobs");
});

test("AI 助手是独立路由，不会被首页的视图切换拦截", () => {
  const assistant = NAV_ITEMS.find((item) => item.view === "assistant");
  assert.ok(assistant);
  assert.equal(assistant.href, "/assistant");
  assert.equal(isHashView("assistant"), false);
});

test("页面标题取自导航标签", () => {
  const views: HashView[] = ["match", "resumes", "jobs", "saved", "history"];
  for (const view of views) assert.ok(navLabel(view));
  assert.equal(navLabel("jobs"), "职位库");
});

test("所有登录后页面共用 AppShell，不再各写一套外壳", () => {
  for (const page of ["page.tsx", "assistant/page.tsx", "jobs/[id]/page.tsx", "resumes/[id]/page.tsx"]) {
    const source = read(page);
    assert.match(source, /<AppShell\b/, `${page} 应当使用 AppShell`);
    assert.doesNotMatch(source, /AppChrome|WorkspaceNav|className="app-shell/, `${page} 不应再自带外壳`);
  }
});
