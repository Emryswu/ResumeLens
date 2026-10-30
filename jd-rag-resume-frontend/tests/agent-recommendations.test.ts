import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import vm from "node:vm";
import { createElement, type ComponentType } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import test from "node:test";
import { approvedRecommendations } from "../app/agent-recommendations.ts";
import type { AgentStep } from "../app/agent-stream.ts";

const item = { jobId: 7, resumeId: 1, title: "Java 实习", company: "示例公司", location: "深圳",
  reason: "项目与岗位要求相符", resumeEvidence: [{ quote: "熟练使用 Spring Boot 构建 REST API", supports: "熟悉 Java 后端开发" }] };
function step(facts: Record<string, unknown>, ok = true): AgentStep {
  return { toolCallId: "s1", tool: "submit_recommendations", arguments: JSON.stringify({ recommendations: [item] }),
    ok, latencyMs: 1, resultChars: 100, resultPreview: "clipped", facts };
}

test("mixed submissions and restored server steps display only approved evidence, without duplicating retained jobs", () => {
  const turn = { steps: [step({ accepted: false, recommendations: [item], rejected: 1 }), step({ recommendations: [item] })] };
  assert.deepEqual(approvedRecommendations(turn), [item]);
  assert.deepEqual(approvedRecommendations(JSON.parse(JSON.stringify(turn))), [item]);
});

test("rejected calls, previews, arguments, legacy and malformed facts cannot become evidence cards", () => {
  assert.deepEqual(approvedRecommendations({ steps: [step({ recommendations: [item] }, false), step({}),
    step({ recommendations: [{ ...item, resumeEvidence: [] }, { ...item, resumeEvidence: [{ quote: "invented" }] }] })] }), []);
});

test("cards render both the quote and JD requirement using the report evidence styles", () => {
  const card = readFileSync(new URL("../app/components/assistant/RecommendationCards.tsx", import.meta.url), "utf8");
  assert.match(card, /review-evidence source-chunk/);
  assert.match(card, /className="source-text"/);
  assert.match(card, /citation.quote/);
  assert.match(card, /citation.supports/);
  const page = readFileSync(new URL("../app/page.tsx", import.meta.url), "utf8");
  assert.match(page, /<RecommendationCards turn=\{turn\}/);
});

test("the rendered card escapes citations and shows the paired JD requirement", () => {
  const source = readFileSync(new URL("../app/components/assistant/RecommendationCards.tsx", import.meta.url), "utf8");
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports: Record<string, ComponentType<{ turn: { steps: AgentStep[] } }>> = {};
  const require = createRequire(import.meta.url);
  vm.runInNewContext(code, { exports, require: (id: string) => id.endsWith("/agent-recommendations")
    ? { approvedRecommendations } : require(id) });
  const recommendation = { ...item, resumeEvidence: [{ quote: "使用 <script>alert(1)</script> 作为项目测试数据", supports: "编写接口测试" }] };
  const html = renderToStaticMarkup(createElement(exports.RecommendationCards,
    { turn: { steps: [step({ recommendations: [recommendation] })] } }));
  assert.match(html, /简历原文/);
  assert.match(html, /编写接口测试/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script/i);
  assert.equal(renderToStaticMarkup(createElement(exports.RecommendationCards,
    { turn: { steps: [step({ recommendations: [item] }, false)] } })), "");
});
