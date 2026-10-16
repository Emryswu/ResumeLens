import assert from "node:assert/strict";
import test from "node:test";

import { agentChatReducer, initialAgentChatState, type AgentChatState, type AgentEvent, type AgentStep } from "../app/agent-stream.ts";
import { describeArguments, formatDuration, timelineSteps, toolTimeMs } from "../app/agent-steps.ts";

const start = (toolCallId: string, tool = "list_resumes", args = "{}"): AgentEvent =>
  ({ type: "step_started", data: { toolCallId, tool, arguments: args } });
const finish = (toolCallId: string, overrides: Partial<AgentStep> = {}): AgentEvent => ({
  type: "step",
  data: { toolCallId, tool: "list_resumes", arguments: "{}", ok: true, errorCode: null, latencyMs: 12,
    resultChars: 40, resultPreview: "{\"total\":1}", ...overrides },
});
const done = (state: "COMPLETED" | "TIMED_OUT" | "FAILED" | "AWAITING_CONFIRMATION"): AgentEvent =>
  ({ type: "done", data: { state, steps: 1, transcript: [] } });

function run(...events: AgentEvent[]): AgentChatState {
  return events.reduce(
    (state, event) => agentChatReducer(state, { type: "event", event }),
    agentChatReducer(initialAgentChatState, { type: "ask", question: "我的简历适合哪些职位？" }),
  );
}

const rows = (state: AgentChatState) => timelineSteps(state.turns[0], state.pending?.toolCallId ?? null);
const statuses = (state: AgentChatState) => rows(state).map((row) => [row.id, row.status]);

test("a started step runs until its result arrives, then shows the server's latency", () => {
  const running = run(start("c1"));
  assert.deepEqual(statuses(running), [["c1", "running"]]);
  assert.equal(rows(running)[0].latencyMs, undefined);

  const finished = run(start("c1"), finish("c1"), done("COMPLETED"));
  assert.deepEqual(statuses(finished), [["c1", "done"]]);
  assert.equal(rows(finished)[0].latencyMs, 12);
  assert.equal(toolTimeMs(rows(finished)), 12);
});

test("a failed tool is shown as failed with its error code", () => {
  const state = run(start("c1"), finish("c1", { ok: false, errorCode: "TOOL_FAILED", latencyMs: 30 }), done("COMPLETED"));
  assert.deepEqual(statuses(state), [["c1", "failed"]]);
  assert.equal(rows(state)[0].errorCode, "TOOL_FAILED");
});

test("a turn that ends without the result never leaves a step spinning or marks it done", () => {
  // Timeout: the server reports an error and finishes the turn, but this call never got its step event.
  const timedOut = run(start("c1"), finish("c1"), start("c2", "rank_jobs_for_resume"),
    { type: "error", data: { code: "AGENT_TIMEOUT", message: "超时" } }, done("TIMED_OUT"));
  assert.deepEqual(statuses(timedOut), [["c1", "done"], ["c2", "unfinished"]]);
  assert.equal(rows(timedOut)[1].latencyMs, undefined);
  assert.equal(toolTimeMs(rows(timedOut)), 12, "only the finished call's reported latency counts");

  // The connection drops: the page reports the failure, then settles.
  let interrupted = run(start("c1"));
  interrupted = agentChatReducer(interrupted, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "中断" });
  interrupted = agentChatReducer(interrupted, { type: "settled" });
  assert.deepEqual(statuses(interrupted), [["c1", "unfinished"]]);

  // Even without done or failed, settling the request closes what is still open.
  const settled = agentChatReducer(run(start("c1")), { type: "settled" });
  assert.deepEqual(statuses(settled), [["c1", "unfinished"]]);
});

test("waiting, rejected and executed confirmations are three different rows", () => {
  const confirmation = { toolCallId: "w1", tool: "start_analysis", arguments: { resumeId: 1, jobId: 2 } };
  let state = run(start("c1"), finish("c1"), { type: "confirmation_required", data: confirmation }, done("AWAITING_CONFIRMATION"));
  assert.deepEqual(statuses(state), [["c1", "done"], ["w1", "awaiting"]]);
  assert.equal(rows(state)[1].latencyMs, undefined);

  let rejected = agentChatReducer(state, { type: "decide", approved: false });
  rejected = agentChatReducer(rejected, { type: "event", event: finish("w1",
    { tool: "start_analysis", ok: false, errorCode: "USER_REJECTED", latencyMs: 0 }) });
  assert.deepEqual(statuses(rejected), [["c1", "done"], ["w1", "rejected"]]);
  assert.equal(rows(rejected)[1].latencyMs, undefined, "a refused call never ran");
  assert.equal(toolTimeMs(rows(rejected)), 12);

  state = agentChatReducer(state, { type: "decide", approved: true });
  state = agentChatReducer(state, { type: "event", event: start("w1", "start_analysis", "{\"resumeId\":1,\"jobId\":2}") });
  assert.deepEqual(statuses(state), [["c1", "done"], ["w1", "running"]]);
  state = agentChatReducer(state, { type: "event", event: finish("w1",
    { tool: "start_analysis", latencyMs: 40, resultPreview: "{\"analysisId\":7}" }) });
  assert.deepEqual(statuses(state), [["c1", "done"], ["w1", "done"]]);
  assert.equal(toolTimeMs(rows(state)), 52);
});

function awaitingApproval() {
  const confirmation = { toolCallId: "w1", tool: "start_analysis", arguments: { resumeId: 1, jobId: 2 } };
  return run({ type: "confirmation_required", data: confirmation }, done("AWAITING_CONFIRMATION"));
}

test("an approval cut off before any result is unknown, never 'not executed'", () => {
  // The server may already have received the approval and run the analysis; the page cannot tell.
  let state = agentChatReducer(awaitingApproval(), { type: "decide", approved: true });
  assert.equal(state.turns[0].approvedCallId, "w1");
  assert.deepEqual(statuses(state), [["w1", "approving"]]);

  for (const code of ["AGENT_STREAM_INTERRUPTED", "BACKEND_UNAVAILABLE", "NETWORK_ERROR"]) {
    let broken = agentChatReducer(state, { type: "failed", code, message: "中断", retryConfirmation: true });
    broken = agentChatReducer(broken, { type: "settled" });
    assert.deepEqual(statuses(broken), [["w1", "unknown"]], code);
    assert.equal(rows(broken)[0].latencyMs, undefined);
  }
});

test("a refused request keeps the confirmation open, so the approval is not left as unknown", () => {
  let state = agentChatReducer(awaitingApproval(), { type: "decide", approved: true });
  state = agentChatReducer(state, { type: "failed", code: "AGENT_RATE_LIMITED", message: "稍后再试", retryConfirmation: true });
  state = agentChatReducer(state, { type: "settled" });
  assert.equal(state.turns[0].approvedCallId, undefined);
  assert.deepEqual(statuses(state), [["w1", "awaiting"]]);
});

test("once the server answers, its result wins over the sent approval", () => {
  let state = agentChatReducer(awaitingApproval(), { type: "decide", approved: true });
  state = agentChatReducer(state, { type: "event", event: start("w1", "start_analysis") });
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "中断" });
  assert.deepEqual(statuses(state), [["w1", "unfinished"]], "started but no result: unfinished, not done");
});

test("a refusal cut off before the server answered is still not executed", () => {
  let state = agentChatReducer(awaitingApproval(), { type: "decide", approved: false });
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "中断" });
  assert.equal(state.turns[0].approvedCallId, undefined);
  assert.deepEqual(statuses(state), [["w1", "unconfirmed"]]);
});

test("steps without a start event (an older server, or a call that never ran) still show their result", () => {
  const state = run(finish("c1", { ok: false, errorCode: "INVALID_ARGUMENTS", latencyMs: 0 }), done("COMPLETED"));
  assert.deepEqual(statuses(state), [["c1", "failed"]]);
});

test("no finished call means no tool time at all, not zero", () => {
  assert.equal(toolTimeMs(rows(run(start("c1")))), null);
});

test("arguments read as Chinese, unknown keys are kept, invalid JSON is shown raw", () => {
  assert.equal(describeArguments("{\"resumeId\":36,\"limit\":3}"), "简历 #36 · 前 3 个");
  assert.equal(describeArguments("{\"keyword\":\"Java\",\"page\":0}"), "关键词「Java」 · 第 1 页");
  assert.equal(describeArguments("{\"other\":true}"), "other=true");
  assert.equal(describeArguments("{}"), "无参数");
  assert.equal(describeArguments("not json"), "not json");
});

test("durations keep the reported value", () => {
  assert.equal(formatDuration(12), "12 ms");
  assert.equal(formatDuration(1430), "1.4 s");
});
