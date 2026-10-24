import assert from "node:assert/strict";
import test from "node:test";
import { COLLECTOR_OPTIONS, collectorStorageKey, readCollectorModes } from "../app/collector-mode.ts";
import { agentChatReducer, initialAgentChatState, toAgentEvent } from "../app/agent-stream.ts";
import { collectionLog } from "../app/collection-log.ts";

test("collector selections are scoped to the user and conversation and reject invalid persisted values", () => {
  assert.notEqual(collectorStorageKey(1), collectorStorageKey(2));
  assert.deepEqual(readCollectorModes('{"1":"chrome","2":"patchright","3":"auto","4":"model-choice"}'),
    { 1: "chrome", 2: "patchright", 3: "auto" });
  assert.deepEqual(readCollectorModes("broken"), {});
  assert.equal(COLLECTOR_OPTIONS.length, 3);
});

test("durable collector progress renders the fallback reason through the existing collection log", () => {
  const progress = { toolCallId: "c", command: { keyword: "Java" },
    progress: { phase: "read", pagesRead: 1, pageLog: [{ page: 1, api: 2, dom: 0 }] },
    switchReason: "我的 Chrome 不可用，已切换到独立浏览器" };
  const event = toAgentEvent({ event: "collector_progress", data: JSON.stringify(progress) });
  assert.ok(event);
  const asked = agentChatReducer(initialAgentChatState, { type: "ask", question: "找岗位" });
  const state = agentChatReducer(asked, { type: "event", event });
  const saved = state.turns[0].collectorProgress!.c;
  assert.deepEqual(saved, progress);
  assert.equal(collectionLog({ ...saved.progress, switchReason: saved.switchReason })[0], progress.switchReason);
});
