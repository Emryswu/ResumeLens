import assert from "node:assert/strict";
import test from "node:test";
import { highlightRanges, highlightSegments } from "../app/evidence-highlight.ts";

const marked = (chunk: string, title: string, detail = "") =>
  highlightSegments(chunk, { title, detail }).filter((segment) => segment.mark).map((segment) => segment.text);

test("英文技术词按整词、忽略大小写高亮", () => {
  assert.deepEqual(marked("熟悉 java 与 Spring Boot，写过 JavaScript", "Java 后端", "你用过 Spring Boot"), ["java", "Spring", "Boot"]);
});

test("中文取标题里能在片段中原样找到的最长子串（至少两个字）", () => {
  assert.deepEqual(marked("负责支付对账系统的后端开发", "支付对账经验"), ["支付对账"]);
  assert.deepEqual(marked("参与后端服务开发", "后端技术栈"), ["后端"]);
  assert.deepEqual(marked("参与开发", "后"), []);
});

test("说明里的中文不参与高亮，避免「你的简历」之类的词到处命中", () => {
  assert.deepEqual(marked("你的简历写了 Kafka", "消息队列", "你的简历体现了 Kafka"), ["Kafka"]);
});

test("重叠的命中合并为一段，片段原文完整保留", () => {
  const chunk = "Spring Boot 服务";
  assert.deepEqual(highlightRanges(chunk, { title: "Spring Boot 服务", detail: "Boot" }), [[0, 6], [7, 11], [12, 14]]);
  assert.equal(highlightSegments(chunk, { title: "x", detail: "" }).map((segment) => segment.text).join(""), chunk);
});

test("找不到任何相同的词时不高亮", () => {
  assert.deepEqual(highlightSegments("负责数据库优化", { title: "Kubernetes", detail: "" }), [{ text: "负责数据库优化", mark: false }]);
});
