import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";
import { FINISH_MESSAGES, STOP_MESSAGES, collectionLog, collectionReason } from "../app/collection-log.ts";

const require = createRequire(import.meta.url);
const extension = require("../../browser-extension/src/collect-status.js");

test("every extension finish and stop reason has the same Chinese translation in the card", () => {
  assert.deepEqual(FINISH_MESSAGES, extension.FINISH_MESSAGES);
  assert.deepEqual(STOP_MESSAGES, extension.STOP_MESSAGES);
  for (const [key, message] of Object.entries({ ...FINISH_MESSAGES, ...STOP_MESSAGES })) {
    assert.match(message, /[\u4e00-\u9fff]/, key);
  }
  // Catch a newly introduced literal reason even if it was never added to either translation table.
  const runner = readFileSync(new URL("../../browser-extension/src/collect-runner.js", import.meta.url), "utf8");
  const codes = [...runner.matchAll(/"([A-Z][A-Z_]{2,})"/g)].map((match) => match[1]);
  for (const code of codes.filter((code) => !["BUSY", "INVALID_COMMAND"].includes(code))) {
    assert.ok(code in FINISH_MESSAGES || code in STOP_MESSAGES, `missing Chinese translation: ${code}`);
  }
  const extractor = readFileSync(new URL("../../browser-extension/src/boss-list-extractor.js", import.meta.url), "utf8");
  for (const match of extractor.matchAll(/(?:return |code: )"([A-Z_]+)"/g)) {
    assert.ok(match[1] in STOP_MESSAGES, `missing blocker translation: ${match[1]}`);
  }
});

test("the operation log shows per-page additions, sources and the finish reason", () => {
  const activity = { pagesRead: 2, pageLog: [{ page: 1, api: 3, dom: 1 }, { page: 2, api: 2, dom: 0 }], finishReason: "MAX_PAGES" };
  assert.deepEqual(collectionLog(activity), ["打开 BOSS 搜索页", "第 1 页读到 4 条（接口 3 条 / 页面 1 条）",
    "第 2 页读到 2 条（接口 2 条 / 页面 0 条）", "结束原因：已读完要求的页数"]);
  assert.deepEqual(collectionLog(activity), extension.logLines(activity));
  for (const reason of Object.keys(FINISH_MESSAGES)) assert.ok(collectionReason({ finishReason: reason }));
  for (const reason of Object.keys(STOP_MESSAGES)) assert.ok(collectionReason({ stopReason: reason }));
});

test("first-page exhaustion shows the login hint, but other finishes and later pages do not", () => {
  assert.match(collectionReason({ pagesRead: 1, finishReason: "NO_MORE_RESULTS" })!, /可能未登录 BOSS，只能看到第一页/);
  assert.doesNotMatch(collectionReason({ pagesRead: 2, finishReason: "NO_MORE_RESULTS" })!, /可能未登录/);
  assert.doesNotMatch(collectionReason({ pagesRead: 1, finishReason: "MAX_PAGES" })!, /可能未登录/);
});
