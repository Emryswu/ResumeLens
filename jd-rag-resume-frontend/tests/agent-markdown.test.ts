import assert from "node:assert/strict";
import test from "node:test";

import { parseAgentMarkdown, parseInline, type MdInline } from "../app/agent-markdown.ts";

const plain = (inline: MdInline[]) => inline.map((part) => part.text).join("");

test("pipe table from a real model reply becomes a table, not raw pipes", () => {
  const reply = "你有 2 份简历：\n\n| resumeId | 标题 | 候选人 | 最近更新 |\n|---|---|---|---|\n"
    + "| 32 | frontend-lin | 林晓晴 | 2026-10-07 |\n| 31 | java-backend-chen | 陈思远 | 2026-10-07 |\n\n"
    + "需要我用其中某份简历去匹配职位，或做完整分析吗？";

  const blocks = parseAgentMarkdown(reply);

  assert.deepEqual(blocks.map((block) => block.type), ["paragraph", "table", "paragraph"]);
  const table = blocks[1];
  assert.equal(table.type, "table");
  assert.deepEqual(table.header.map(plain), ["resumeId", "标题", "候选人", "最近更新"]);
  assert.deepEqual(table.rows.map((row) => row.map(plain)), [
    ["32", "frontend-lin", "林晓晴", "2026-10-07"],
    ["31", "java-backend-chen", "陈思远", "2026-10-07"],
  ]);
});

test("ragged table rows are padded to the header width", () => {
  const [table] = parseAgentMarkdown("a | b | c\n--- | --- | ---\n1 | 2\n3 | 4 | 5 | 6");

  assert.equal(table.type, "table");
  assert.deepEqual(table.rows.map((row) => row.map(plain)), [["1", "2", ""], ["3", "4", "5"]]);
});

test("bold and inline code are parsed; unmatched markers stay literal", () => {
  assert.deepEqual(parseInline("**结论：最适合的是 jobId 37。** 依据见下"), [
    { type: "strong", text: "结论：最适合的是 jobId 37。" },
    { type: "text", text: " 依据见下" },
  ]);
  assert.deepEqual(parseInline("调用 `rank_jobs_for_resume` 排序"), [
    { type: "text", text: "调用 " },
    { type: "code", text: "rank_jobs_for_resume" },
    { type: "text", text: " 排序" },
  ]);
  assert.deepEqual(parseInline("5 ** 2 = 25"), [{ type: "text", text: "5 ** 2 = 25" }]);
});

test("numbers at the start of a line are text unless they are a list marker", () => {
  const blocks = parseAgentMarkdown("5 年 Java 后端经验\n1.8s 慢查询优化到 40ms\n99.9% 可用性");

  assert.equal(blocks.length, 1);
  assert.equal(blocks[0].type, "paragraph");
  assert.deepEqual(blocks[0].lines.map(plain), ["5 年 Java 后端经验", "1.8s 慢查询优化到 40ms", "99.9% 可用性"]);
});

test("numbered and bullet lists keep their items and start number", () => {
  const reply = "**简历原文证据：**\n\n1. 求职意向与技能：\n   - “熟悉 Java 17”\n   - “有 Kafka 经验”\n\n2. 工作经历：\n   - “参与值班”";

  const blocks = parseAgentMarkdown(reply);

  assert.deepEqual(blocks.map((block) => block.type), ["paragraph", "list", "list", "list", "list"]);
  const [, first, firstBullets, second] = blocks;
  assert.ok(first.type === "list" && first.ordered && first.start === 1);
  assert.deepEqual(first.items.map(plain), ["求职意向与技能："]);
  assert.ok(firstBullets.type === "list" && !firstBullets.ordered);
  assert.deepEqual(firstBullets.items.map(plain), ["“熟悉 Java 17”", "“有 Kafka 经验”"]);
  assert.ok(second.type === "list" && second.ordered && second.start === 2);
});

test("headings, rules and fenced code", () => {
  const blocks = parseAgentMarkdown("### 匹配结论\n---\n```json\n{\"a\": \"**not bold**\"}\n```\n收尾");

  assert.deepEqual(blocks, [
    { type: "heading", level: 3, content: [{ type: "text", text: "匹配结论" }] },
    { type: "code", text: "{\"a\": \"**not bold**\"}" },
    { type: "paragraph", lines: [[{ type: "text", text: "收尾" }]] },
  ]);
});

test("markup in quoted resume text stays text", () => {
  const [block] = parseAgentMarkdown("<img src=x onerror=alert(1)> **<b>粗</b>**");

  assert.equal(block.type, "paragraph");
  assert.deepEqual(block.lines[0], [
    { type: "text", text: "<img src=x onerror=alert(1)> " },
    { type: "strong", text: "<b>粗</b>" },
  ]);
});
