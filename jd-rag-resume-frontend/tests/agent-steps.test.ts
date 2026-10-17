import assert from "node:assert/strict";
import test from "node:test";

import {
  agentChatReducer, initialAgentChatState, nextRequest, retryAdvice, retryRequest, staleConversation,
  type AgentChatState, type AgentEvent, type AgentMessage, type AgentStep,
} from "../app/agent-stream.ts";
import {
  describeArguments, formatDuration, processSummary, timelineSteps, toolTimeMs, type StepStatus, type TimelineStep,
} from "../app/agent-steps.ts";

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
  const state = agentChatReducer(awaitingApproval(), { type: "decide", approved: true });
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

const canRetry = (state: AgentChatState) => retryAdvice(state.turns.at(-1)!).kind === "retry";

test("a failed question can be asked again unless rephrasing or starting over is the only fix", () => {
  const failed = (code: string) => agentChatReducer(run(start("c1")), { type: "failed", code, message: "失败" });
  assert.equal(canRetry(run(start("c1"), finish("c1"), done("COMPLETED"))), false, "no error, nothing to retry");
  assert.equal(canRetry(failed("NETWORK_ERROR")), true);
  assert.equal(canRetry(failed("AGENT_BUSY")), true);
  assert.equal(canRetry(failed("AGENT_STREAM_INTERRUPTED")), true);
  for (const code of ["AGENT_STEP_LIMIT", "AGENT_TRANSCRIPT_TOO_LARGE", "AI_NOT_CONFIGURED"]) {
    assert.equal(canRetry(failed(code)), false, code);
  }
});

test("no retry after an approval whose outcome is unknown: the write may already have run", () => {
  let state = agentChatReducer(awaitingApproval(), { type: "decide", approved: true });
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "中断" });
  assert.deepEqual(retryAdvice(state.turns[0]), { kind: "check-report", analysisId: undefined });
  assert.equal(retryRequest(agentChatReducer(state, { type: "settled" })), null);
});

/** The conversation as the server returned it when it asked for confirmation; the approved call is not in it yet. */
const beforeApproval: AgentMessage[] = [
  { role: "user", content: "帮我对最合适的职位做一次完整分析" },
  { role: "assistant", content: "", tool_calls: [{ id: "w1", type: "function", function: { name: "start_analysis", arguments: "{}" } }] },
];

function approvedAndRan(...after: AgentEvent[]): AgentChatState {
  let state = run(
    { type: "confirmation_required", data: { toolCallId: "w1", tool: "start_analysis", arguments: { resumeId: 1, jobId: 2 } } },
    { type: "done", data: { state: "AWAITING_CONFIRMATION", steps: 1, transcript: beforeApproval } },
  );
  state = agentChatReducer(state, { type: "decide", approved: true });
  state = agentChatReducer(state, { type: "event", event: start("w1", "start_analysis") });
  state = agentChatReducer(state, { type: "event", event: finish("w1", { tool: "start_analysis", resultPreview: "{\"analysisId\":7}" }) });
  return after.reduce((current, event) => agentChatReducer(current, { type: "event", event }), state);
}

test("regression: approve, analysis succeeds, connection drops before done, then 重试 sends nothing", () => {
  let state = approvedAndRan();
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "中断" });
  state = agentChatReducer(state, { type: "settled" });

  // The result arrived but done did not, so the transcript still predates the approved call.
  assert.deepEqual(state.transcript, beforeApproval);
  assert.deepEqual(retryAdvice(state.turns[0]), { kind: "check-report", analysisId: 7 });

  // What the page's retry does: send retryRequest's body, if there is one.
  const sent: unknown[] = [];
  const request = retryRequest(state);
  if (request) sent.push(request.body);
  assert.equal(request, null);
  assert.deepEqual(sent, [], "a retry would resend the old transcript and the server would call the analysis unconfirmed");
});

test("regression: analysis succeeds, connection drops before done, then a typed question is not sent on the old transcript", () => {
  let state = approvedAndRan();
  // Still waiting for the server: nothing is decided yet, so the conversation is not blocked.
  assert.equal(staleConversation(state), null);
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "中断" });
  state = agentChatReducer(state, { type: "settled" });

  assert.deepEqual(staleConversation(state), { analysisId: 7 }, "the page locks the input and points to report 7");
  // Whatever the page does, the shared builder refuses to put the old transcript in a request.
  const sent: unknown[] = [];
  assert.throws(() => sent.push(nextRequest(state, { question: "刚才分析完成了吗？" })), /new conversation/);
  assert.deepEqual(sent, []);

  // 新对话 starts from an empty transcript, which is consistent again.
  const fresh = agentChatReducer(state, { type: "reset" });
  assert.equal(staleConversation(fresh), null);
  assert.deepEqual(nextRequest(fresh, { question: "刚才分析完成了吗？" }).messages, [{ role: "user", content: "刚才分析完成了吗？" }]);
});

test("once done delivered the transcript, a later failure can be retried on that transcript", () => {
  const afterRun: AgentMessage[] = [...beforeApproval, { role: "tool", tool_call_id: "w1", content: "{\"analysisId\":7}" }];
  let state = approvedAndRan(
    { type: "error", data: { code: "AGENT_TIMEOUT", message: "超时" } },
    { type: "done", data: { state: "TIMED_OUT", steps: 2, transcript: afterRun } },
  );
  state = agentChatReducer(state, { type: "settled" });
  assert.deepEqual(retryAdvice(state.turns[0]), { kind: "retry" });
  const request = retryRequest(state);
  assert.ok(request);
  assert.deepEqual(request.body.messages, [...afterRun, { role: "user", content: state.turns[0].question }]);
});

test("a confirmed write that failed ran nothing, so it can be retried even without done", () => {
  let state = run(
    { type: "confirmation_required", data: { toolCallId: "w1", tool: "start_analysis", arguments: {} } },
    { type: "done", data: { state: "AWAITING_CONFIRMATION", steps: 1, transcript: beforeApproval } },
  );
  state = agentChatReducer(state, { type: "decide", approved: true });
  state = agentChatReducer(state, { type: "event", event: finish("w1", { tool: "start_analysis", ok: false, errorCode: "TOOL_FAILED" }) });
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "中断" });
  state = agentChatReducer(state, { type: "settled" });
  assert.deepEqual(retryAdvice(state.turns[0]), { kind: "retry" });
});

const step = (tool: string, status: StepStatus = "done", latencyMs?: number): TimelineStep =>
  ({ id: `${tool}-${status}-${latencyMs}`, tool, label: tool, arguments: "{}", status, latencyMs });

test("a turn without tools has no summary line", () => {
  assert.equal(processSummary([]), null);
});

test("one tool: its own name and icon, no total time", () => {
  const summary = processSummary([step("search_jobs", "done", 80)]);
  assert.equal(summary?.text, "搜索职位库");
  assert.equal(summary?.icon, "search_jobs");
  assert.equal(summary?.totalMs, null);
  assert.deepEqual(summary?.issues, []);
});

test("several tools: names in order of first use, a clock icon and the total tool time", () => {
  const summary = processSummary([step("list_resumes", "done", 12), step("rank_jobs_for_resume", "done", 900)]);
  assert.equal(summary?.text, "读取简历列表、按语义相似度排序职位");
  assert.equal(summary?.icon, "multiple");
  assert.equal(summary?.totalMs, 912);
});

test("a tool called again is listed once, where it was first used", () => {
  const summary = processSummary([
    step("search_jobs", "done", 10), step("get_job", "done", 20), step("search_jobs", "done", 30), step("get_job", "done", 40),
  ]);
  assert.deepEqual(summary?.tools, ["search_jobs", "get_job"]);
  assert.equal(summary?.text, "搜索职位库、读取职位详情");
  assert.equal(summary?.steps, 4);
  assert.equal(summary?.totalMs, 100);
  // Calling the same tool twice is still one kind of tool: its own icon, no total.
  const repeated = processSummary([step("get_job", "done", 5), step("get_job", "done", 6)]);
  assert.equal(repeated?.icon, "get_job");
  assert.equal(repeated?.totalMs, null);
});

test("failed and refused steps are never folded away by the summary", () => {
  const summary = processSummary([
    step("search_jobs", "done", 10), step("get_job", "failed", 30), step("get_job", "failed", 5), step("start_analysis", "rejected"),
  ]);
  assert.deepEqual(summary?.issues, [
    { status: "failed", label: "2 步失败" },
    { status: "rejected", label: "1 步已拒绝" },
  ]);
  // A single failed tool still says so.
  assert.deepEqual(processSummary([step("get_job", "failed", 30)])?.issues, [{ status: "failed", label: "1 步失败" }]);
});

test("total time adds up only what the server reported: failed calls count, refused and unfinished ones do not", () => {
  const summary = processSummary([
    step("search_jobs", "done", 100), step("get_job", "failed", 50), step("start_analysis", "rejected", 999),
    step("list_resumes", "unfinished"),
  ]);
  assert.equal(summary?.totalMs, 150);
  assert.equal(formatDuration(summary?.totalMs ?? 0), "150 ms");
  assert.equal(processSummary([step("search_jobs", "unfinished"), step("get_job", "unfinished")])?.totalMs, null);
});
