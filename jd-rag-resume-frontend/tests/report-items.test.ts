import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { asList, evidenceChunks } from "../app/report-export.ts";
import { CATEGORY_LABELS, chunkSimilarity, findingsFrom, normalizeCategory } from "../app/report-items.ts";

const appDir = join(dirname(fileURLToPath(import.meta.url)), "../app");

test("结构化条目原样使用，类别码只在前端映射成中文", () => {
  const items = findingsFrom([
    { title: "Java 后端", detail: "你做过 Spring Boot 服务。", chunks: [0, 2, 2], category: "TECH_STACK", source: "MODEL" },
    { title: "部署", detail: "", chunks: [], category: "ENGINEERING", source: "RULE" },
  ], []);
  assert.deepEqual(items, [
    { title: "Java 后端", detail: "你做过 Spring Boot 服务。", chunks: [0, 2], category: "TECH_STACK", source: "MODEL" },
    { title: "部署", detail: "", chunks: [], category: "ENGINEERING", source: "RULE" },
  ]);
  assert.equal(CATEGORY_LABELS.TECH_STACK, "技术栈");
  assert.equal(CATEGORY_LABELS.GENERAL, "综合");
});

test("未知类别、中文类别和坏数据都按默认处理，不报错", () => {
  assert.equal(normalizeCategory("技术栈"), "GENERAL");
  assert.equal(normalizeCategory("SOMETHING"), "GENERAL");
  assert.equal(normalizeCategory(undefined), "GENERAL");
  const items = findingsFrom([null, 3, { title: "", detail: "" }, { detail: "只有说明", chunks: ["1", -1, 1.5, 4] }], []);
  assert.deepEqual(items, [{ title: "只有说明", detail: "", chunks: [4], category: "GENERAL", source: "MODEL" }]);
});

test("旧报告没有结构化字段时，按文本行回退并把 [chunk-N] 移到证据标签", () => {
  const items = findingsFrom(null, asList("候选人熟悉 Java。[chunk-0]\n有 MySQL 调优经验 [chunk-1] [chunk-1]"));
  assert.deepEqual(items, [
    { title: "候选人熟悉 Java。", detail: "", chunks: [0], category: "GENERAL", source: "MODEL" },
    { title: "有 MySQL 调优经验", detail: "", chunks: [1], category: "GENERAL", source: "MODEL" },
  ]);
});

test("旧报告的「规则校验缺失」一行拆成逐条规则缺口", () => {
  const items = findingsFrom(undefined, asList("规则校验缺失：Java、MySQL\n简历尚未体现部署经验。"));
  assert.deepEqual(items.map((item) => [item.title, item.source, item.category]), [
    ["Java", "RULE", "TECH_STACK"],
    ["MySQL", "RULE", "TECH_STACK"],
    ["简历尚未体现部署经验。", "MODEL", "GENERAL"],
  ]);
});

test("空报告得到空列表", () => {
  assert.deepEqual(findingsFrom(null, asList("")), []);
  assert.deepEqual(findingsFrom([], asList("忽略文本 [chunk-0]")), []);
});

test("相似度取自报告保存的检索片段，没记录的片段没有数字", () => {
  const evidence = evidenceChunks("[resume-chunk-0 | similarity=0.8123 | status=kept | section=技能]\nJava\n\n[resume-chunk-3 | similarity=0.5 | status=filtered]\nx");
  assert.equal(chunkSimilarity(evidence, 0), 0.8123);
  assert.equal(chunkSimilarity(evidence, 3), 0.5);
  assert.equal(chunkSimilarity(evidence, 9), undefined);
});

test("报告页文案：第二人称、无 MATCH REVIEW 眉标、不暗示证据已被验证", () => {
  const reportFiles = [
    "components/MatchReport.tsx",
    ...readdirSync(join(appDir, "components/report")).map((name) => `components/report/${name}`),
  ];
  for (const file of [...reportFiles, "page.tsx", "resumes/[id]/page.tsx", "preview-evidence.ts"]) {
    assert.doesNotMatch(readFileSync(join(appDir, file), "utf8"), /候选人/, file);
  }
  for (const file of reportFiles) {
    const source = readFileSync(join(appDir, file), "utf8");
    assert.doesNotMatch(source, /MATCH REVIEW/, file);
    assert.doesNotMatch(source, /已验证|已证实|证据确凿/, file);
  }
  assert.match(readFileSync(join(appDir, "components/report/EvidenceDrawer.tsx"), "utf8"), /相关原文片段/);
});

test("缺口条目不显示相似度", () => {
  const source = readFileSync(join(appDir, "components/report/FindingList.tsx"), "utf8");
  assert.match(source, /similarity=\{kind === "strength" \? chunkSimilarity\(evidence, chunk\) : undefined\}/);
});
