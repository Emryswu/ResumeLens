import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createHistoryOpener } from "../app/history-open.ts";

type Report = { id: number; jobSnapshot: { title: string } | null };

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((ok, no) => { resolve = ok; reject = no; });
  return { promise, resolve, reject };
}

test("打开分析记录时展示按 id 拉回的详情，而不是列表行", async () => {
  const shown: Report[] = [];
  const open = createHistoryOpener<Report>();

  await open(7, {
    fetchById: async (id) => ({ id, jobSnapshot: { title: "Java 后端" } }),
    isSessionCurrent: () => true,
    show: (report) => shown.push(report),
    fail: () => assert.fail("should not fail"),
  });

  assert.deepEqual(shown, [{ id: 7, jobSnapshot: { title: "Java 后端" } }]);
});

test("连续点两条记录时，先点的那条晚返回也不会盖掉后点的", async () => {
  const first = deferred<Report>();
  const second = deferred<Report>();
  const shown: number[] = [];
  const open = createHistoryOpener<Report>();
  const deps = {
    fetchById: (id: number) => (id === 1 ? first.promise : second.promise),
    isSessionCurrent: () => true,
    show: (report: Report) => shown.push(report.id),
    fail: () => assert.fail("should not fail"),
  };

  const a = open(1, deps);
  const b = open(2, deps);
  second.resolve({ id: 2, jobSnapshot: null });
  await b;
  first.resolve({ id: 1, jobSnapshot: null });
  await a;

  assert.deepEqual(shown, [2]);
});

test("换号后旧会话的详情和错误都不再生效", async () => {
  let current = true;
  const pending = deferred<Report>();
  const failing = deferred<Report>();
  const events: string[] = [];
  const open = createHistoryOpener<Report>();
  const deps = {
    fetchById: (id: number) => (id === 1 ? pending.promise : failing.promise),
    isSessionCurrent: () => current,
    show: () => events.push("show"),
    fail: () => events.push("fail"),
  };

  const a = open(1, deps);
  current = false;
  pending.resolve({ id: 1, jobSnapshot: null });
  await a;
  current = true;
  const b = open(2, deps);
  current = false;
  failing.reject(new Error("boom"));
  await b;

  assert.deepEqual(events, []);
});

test("分析记录行点开时不得直接把列表项当报告渲染", () => {
  const page = readFileSync(new URL("../app/workspace/page.tsx", import.meta.url), "utf8");
  const row = page.slice(page.indexOf("<HistoryRow"), page.indexOf("</div>", page.indexOf("<HistoryRow")));
  assert.match(row, /openHistoryReport\(item\.id\)/);
  assert.doesNotMatch(row, /setAnalysis\(item\)/);
});
