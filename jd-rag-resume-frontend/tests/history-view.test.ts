import assert from "node:assert/strict";
import test from "node:test";
import { analysisTime, filterHistory, historyStats, historyStatusLabel } from "../app/history-view.ts";

const now = new Date(2026, 9, 20, 12, 0).getTime();
const daysAgo = (days: number) => new Date(now - days * 86400000).toISOString();
const rows = [
  { id: 5, status: "COMPLETED" as const, matchScore: 86, createdAt: daysAgo(1), jobDescriptionId: 16, jobTitle: "Java 后端" },
  { id: 4, status: "COMPLETED" as const, matchScore: 79.6, createdAt: daysAgo(10), jobDescriptionId: 15, jobTitle: "AI 应用" },
  { id: 3, status: "FAILED" as const, matchScore: 95, createdAt: daysAgo(20), jobDescriptionId: 14, jobTitle: "实习" },
  { id: 2, status: "PENDING" as const, matchScore: null, createdAt: daysAgo(40), jobDescriptionId: 16, jobTitle: "Java 后端" },
];

test("时间范围与仅看已收藏岗位可以叠加筛选", () => {
  const base = { savedOnly: false, savedJobs: [] as number[], now };
  assert.equal(filterHistory(rows, { ...base, range: "all" }).length, 4);
  assert.deepEqual(filterHistory(rows, { ...base, range: "7d" }).map((row) => row.id), [5]);
  assert.deepEqual(filterHistory(rows, { ...base, range: "30d" }).map((row) => row.id), [5, 4, 3]);
  assert.deepEqual(filterHistory(rows, { range: "all", savedOnly: true, savedJobs: [16], now }).map((row) => row.id), [5, 2]);
  assert.deepEqual(filterHistory(rows, { range: "7d", savedOnly: true, savedJobs: [15], now }), []);
});

test("统计只算已完成的分数：失败记录的分数不进最高分", () => {
  const stats = historyStats(rows, 4);
  assert.equal(stats.total, 4);
  assert.equal(stats.best, 86);
  assert.equal(stats.strong, 1, "79.6 不算 ≥ 80");
  assert.equal(stats.latest?.id, 5);
  assert.equal(stats.partial, false);
});

test("没加载完时标为部分统计，总数仍以服务端为准", () => {
  const stats = historyStats(rows.slice(0, 2), 30);
  assert.equal(stats.total, 30);
  assert.equal(stats.partial, true);
  assert.equal(stats.loaded, 2);
  assert.deepEqual(historyStats([], 0), { total: 0, best: null, strong: 0, latest: null, partial: false, loaded: 0 });
});

test("时间与状态的读法", () => {
  assert.equal(analysisTime(new Date(2026, 9, 3, 9, 5).toISOString()), "2026.10.03 09:05");
  assert.equal(analysisTime("bad"), "");
  assert.equal(historyStatusLabel("COMPLETED"), "已完成");
  assert.equal(historyStatusLabel("PENDING"), "分析中");
  assert.equal(historyStatusLabel("FAILED"), "分析失败");
});
