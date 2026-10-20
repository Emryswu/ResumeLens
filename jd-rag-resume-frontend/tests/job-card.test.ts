import assert from "node:assert/strict";
import test from "node:test";
import { companyMark, jobFitLabel, jobMetaParts, jobRing } from "../app/job-card.ts";

test("公司标记取首字、同名颜色稳定，不同公司分散到不同色", () => {
  assert.equal(companyMark("  星河云科技").initial, "星");
  assert.equal(companyMark("acme").initial, "A");
  assert.equal(companyMark("").initial, "?");
  assert.deepEqual(companyMark("青禾智能"), companyMark("青禾智能"));
  const tones = new Set(["星河云科技", "青禾智能", "鹿鸣网络", "远山金融科技", "长风数据", "白鹭设计"].map((name) => companyMark(name).tone));
  assert.ok(tones.size >= 3, `6 家公司只用了 ${tones.size} 种颜色`);
});

test("匹配徽章只看完整分析的分数，分档 80 / 60", () => {
  assert.equal(jobFitLabel(null), null);
  assert.equal(jobFitLabel(undefined), null);
  assert.equal(jobFitLabel(Number.NaN), null);
  assert.deepEqual(jobFitLabel(80), { text: "高度匹配", tone: "high" });
  assert.deepEqual(jobFitLabel(79.9), { text: "较为匹配", tone: "mid" });
  assert.deepEqual(jobFitLabel(60), { text: "较为匹配", tone: "mid" });
  assert.deepEqual(jobFitLabel(59), { text: "匹配一般", tone: "low" });
});

test("元信息行只放有值的字段", () => {
  assert.deepEqual(jobMetaParts({ companyName: "星河", location: " ", employmentType: "校招" }), ["星河", "校招"]);
});

test("圆环跟随排序：语义排序优先语义分，其余优先完整分析的匹配分", () => {
  const done = { status: "COMPLETED" as const, matchScore: 86 };
  assert.deepEqual(jobRing({ analysis: done, similarity: 0.72, prefer: "semantic" }), { value: 72, label: "语义分", muted: true });
  assert.deepEqual(jobRing({ analysis: done, similarity: 0.72, prefer: "score" }), { value: 86, label: "匹配分", muted: false });
  assert.equal(jobRing({ analysis: { status: "PENDING", matchScore: null }, similarity: 0.5, prefer: "score" }).label, "分析中");
  assert.equal(jobRing({ analysis: { status: "FAILED", matchScore: null }, prefer: "score" }).label, "失败");
  assert.deepEqual(jobRing({ similarity: 0.4, prefer: "score" }), { value: 40, label: "语义分", muted: true });
  assert.deepEqual(jobRing({ prefer: "semantic" }), { value: null, label: "未分析", muted: false });
});
