import assert from "node:assert/strict";
import test from "node:test";

import {
  agentChatReducer,
  AgentStreamInterruptedError,
  createSseParser,
  initialAgentChatState,
  nextRequest,
  readAgentStream,
  type AgentEvent,
  type AgentMessage,
  type AgentStep,
} from "../app/agent-stream.ts";
import { ApiError, apiStream, clearAuthSession, setAccessToken } from "../app/lib/api.ts";

test.afterEach(() => {
  clearAuthSession();
});

test("summary survives done and is carried into questions and approvals, reset clears it", () => {
  const summary = "用户此前比较过上海职位";
  const transcript: AgentMessage[] = [{ role: "user", content: "继续" }];
  const state = agentChatReducer(initialAgentChatState, { type: "event", event: {
    type: "done", data: { state: "REPLAN_LIMIT", steps: 4, transcript, summary },
  } });
  assert.equal(state.summary, summary);
  assert.equal(nextRequest(state, { question: "还有呢" }).summary, summary);
  const pending = { toolCallId: "write1", tool: "start_analysis", arguments: {} };
  assert.equal(nextRequest({ ...state, pending }, { approved: true }).summary, summary);
  assert.equal(agentChatReducer(state, { type: "reset" }).summary, undefined);
});

test("parses frames split at arbitrary points, including inside CRLF", () => {
  const parser = createSseParser();
  const wire = "event:step\r\ndata:{\"a\":1}\r\n\r\nevent:done\r\ndata:{\"b\":2}\r\n\r\n";
  const frames = [];
  for (const chunk of [wire.slice(0, 7), wire.slice(7, 12), wire.slice(12, 23), wire.slice(23, 24), wire.slice(24)]) {
    frames.push(...parser.push(chunk));
  }

  assert.deepEqual(frames, [
    { event: "step", data: "{\"a\":1}" },
    { event: "done", data: "{\"b\":2}" },
  ]);
});

test("a lone CR is a line break once the next chunk shows it is not CRLF", () => {
  const parser = createSseParser();

  assert.deepEqual(parser.push("event:note\rdata:x\r"), []);
  // The blank line's "\r" ends the chunk: it could still be half of a CRLF, so wait.
  assert.deepEqual(parser.push("\r"), []);
  assert.deepEqual(parser.push("event:done"), [{ event: "note", data: "x" }]);
});

test("joins multi-line data, strips one leading space, ignores comments", () => {
  const parser = createSseParser();

  const frames = parser.push(": keep-alive\nevent: message\ndata: line one\ndata:  indented\n\n");

  assert.deepEqual(frames, [{ event: "message", data: "line one\n indented" }]);
});

test("flush dispatches a final frame the server ended without a blank line", () => {
  const parser = createSseParser();

  assert.deepEqual(parser.push("event:done\ndata:{}"), []);
  assert.deepEqual(parser.flush(), [{ event: "done", data: "{}" }]);
});

test("readAgentStream decodes UTF-8 split across chunks and drops unknown or malformed events", async () => {
  const done = { state: "COMPLETED" as const, steps: 1, transcript: [{ role: "assistant" as const, content: "你好" }] };
  const bytes = new TextEncoder().encode(
    "event:message\ndata:{\"content\":\"你好\"}\n\nevent:mystery\ndata:{}\n\nevent:step\ndata:not json\n\n"
      + `event:done\ndata:${JSON.stringify(done)}\n\n`,
  );
  // Split inside the multi-byte "你".
  const cut = bytes.indexOf(0xe4) + 1;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes.slice(0, cut));
      controller.enqueue(bytes.slice(cut));
      controller.close();
    },
  });
  const events: AgentEvent[] = [];

  await readAgentStream(stream, (event) => events.push(event));

  assert.deepEqual(events, [{ type: "message", data: { content: "你好" } }, { type: "done", data: done }]);
  assert.equal(stream.locked, false);
});

test("readAgentStream accepts a valid done frame dispatched only by EOF flush", async () => {
  const done = { state: "COMPLETED" as const, steps: 1, transcript: [] };
  const stream = new Response(`event:done\ndata:${JSON.stringify(done)}`).body!;
  const events: AgentEvent[] = [];

  await readAgentStream(stream, (event) => events.push(event));

  assert.deepEqual(events, [{ type: "done", data: done }]);
  assert.equal(stream.locked, false);
});

test("a terminal done finishes the turn even when the transport stays open", async () => {
  const done = { state: "AWAITING_CONFIRMATION" as const, steps: 1, transcript: [] };
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(new TextEncoder().encode(`event:done\ndata:${JSON.stringify(done)}\n\n`)); },
    cancel() { cancelled = true; },
  });
  const events: AgentEvent[] = [];

  await readAgentStream(stream, (event) => events.push(event));

  assert.deepEqual(events, [{ type: "done", data: done }]);
  assert.equal(cancelled, true);
  assert.equal(stream.locked, false);
});

test("EOF without done rejects the turn and discards an unusable confirmation", async () => {
  const confirmation = { toolCallId: "c9", tool: "start_analysis", arguments: { resumeId: 1, jobId: 2 } };
  const stream = new Response(`event:confirmation_required\ndata:${JSON.stringify(confirmation)}\n\n`).body!;
  let state = agentChatReducer(initialAgentChatState, { type: "ask", question: "帮我分析" });

  await assert.rejects(readAgentStream(stream, (event) => {
    state = agentChatReducer(state, { type: "event", event });
    // Until done supplies the transcript, the card cannot be submitted.
    assert.throws(() => nextRequest(state, { approved: true }), /in progress/);
  }), AgentStreamInterruptedError);
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "连接中断" });

  assert.equal(stream.locked, false);
  assert.equal(state.pending, null);
  assert.equal(state.busy, false);
  assert.throws(() => nextRequest(state, { approved: true }), /no pending action/);
  assert.deepEqual(nextRequest(state, { question: "重新分析" }), { messages: [{ role: "user", content: "重新分析" }] });
});

test("malformed or incomplete done events do not satisfy the completion check", async () => {
  for (const data of ["not json", "null", "{}", '{"state":"COMPLETED","steps":1}',
    '{"state":"UNKNOWN","steps":1,"transcript":[]}']) {
    const events: AgentEvent[] = [];
    const stream = new Response(`event:done\ndata:${data}\n\n`).body!;

    await assert.rejects(readAgentStream(stream, (event) => events.push(event)), AgentStreamInterruptedError);

    assert.deepEqual(events, []);
    assert.equal(stream.locked, false);
  }
});

test("a transport read failure propagates and releases the stream lock", async () => {
  const failure = new Error("connection reset");
  const stream = new ReadableStream<Uint8Array>({ start(controller) { controller.error(failure); } });

  await assert.rejects(readAgentStream(stream, () => {}), failure);

  assert.equal(stream.locked, false);
});

const step = (overrides: Partial<AgentStep> = {}): AgentStep => ({
  toolCallId: "c1",
  tool: "list_resumes",
  arguments: "{}",
  ok: true,
  latencyMs: 12,
  resultChars: 80,
  resultPreview: "{}",
  ...overrides,
});

test("a turn collects steps, then the answer, then adopts the server transcript", () => {
  let state = agentChatReducer(initialAgentChatState, { type: "ask", question: "哪些职位适合我？" });
  assert.equal(state.busy, true);

  state = agentChatReducer(state, { type: "event", event: { type: "step", data: step() } });
  state = agentChatReducer(state, { type: "event", event: { type: "message", data: { content: "第一名是 Java 后端" } } });
  const transcript = [
    { role: "user" as const, content: "哪些职位适合我？" },
    { role: "assistant" as const, content: "第一名是 Java 后端" },
  ];
  state = agentChatReducer(state, {
    type: "event",
    event: { type: "done", data: { state: "COMPLETED", steps: 2, transcript } },
  });

  assert.equal(state.busy, false);
  assert.equal(state.turns[0].steps.length, 1);
  assert.equal(state.turns[0].answer, "第一名是 Java 后端");
  assert.deepEqual(state.transcript, transcript);
  assert.deepEqual(nextRequest(state, { question: "第二个呢？" }), {
    messages: [...transcript, { role: "user", content: "第二个呢？" }],
  });
});

function awaitingConfirmation() {
  let state = agentChatReducer(initialAgentChatState, { type: "ask", question: "帮我分析" });
  const confirmation = { toolCallId: "c9", tool: "start_analysis", arguments: { resumeId: 1, jobId: 2 } };
  const transcript: AgentMessage[] = [
    { role: "user", content: "帮我分析" },
    { role: "assistant", tool_calls: [{ id: "c9", type: "function",
      function: { name: "start_analysis", arguments: '{"resumeId":1,"jobId":2}' } }] },
  ];
  state = agentChatReducer(state, { type: "event", event: { type: "confirmation_required", data: confirmation } });
  state = agentChatReducer(state, {
    type: "event",
    event: { type: "done", data: { state: "AWAITING_CONFIRMATION", steps: 1, transcript } },
  });
  return { state, confirmation, transcript };
}

test("confirmation stays pending until acknowledged, and the decision targets that exact call", () => {
  const awaiting = awaitingConfirmation();
  let { state } = awaiting;

  assert.deepEqual(state.pending, awaiting.confirmation);
  assert.equal(state.busy, false);
  assert.deepEqual(nextRequest(state, { approved: false }), {
    messages: awaiting.transcript,
    approval: { toolCallId: "c9", approved: false },
  });

  state = agentChatReducer(state, { type: "decide", approved: true });
  assert.deepEqual(state.pending, awaiting.confirmation);
  assert.equal(state.turns[0].decision, undefined);
  assert.throws(() => nextRequest(state, { approved: true }), /in progress/);
  state = agentChatReducer(state, { type: "event", event: { type: "step",
    data: step({ toolCallId: "c9", tool: "start_analysis", resultPreview: '{"analysisId":42}' }) } });
  assert.equal(state.pending, null);
  assert.equal(state.turns[0].decision, "approved");
  assert.equal(state.turns[0].analysisId, 42);
});

test("429 and queue-full 503 restore the same confirmation for a manual retry", async () => {
  setAccessToken("token");
  for (const [status, code] of [[429, "AGENT_RATE_LIMITED"], [503, "AGENT_BUSY"]] as const) {
    const awaiting = awaitingConfirmation();
    const request = nextRequest(awaiting.state, { approved: true });
    let state = agentChatReducer(awaiting.state, { type: "decide", approved: true });
    globalThis.fetch = async () => Response.json({ success: false, code, message: "try later", data: null }, { status });
    try {
      await apiStream("/api/agent/chat", request);
      assert.fail("the request should be rejected");
    } catch (reason) {
      assert.ok(reason instanceof ApiError);
      state = agentChatReducer(state, { type: "failed", code: reason.code, message: reason.message, retryConfirmation: true });
    }

    assert.equal(state.busy, false);
    assert.equal(state.turns[0].decision, undefined);
    assert.deepEqual(state.pending, awaiting.confirmation);
    assert.deepEqual(nextRequest(state, { approved: true }), request);
  }
});

test("transport failures never offer an approval retry whose execution is unknown", () => {
  for (const code of ["AGENT_STREAM_INTERRUPTED", "BACKEND_UNAVAILABLE", "NETWORK_ERROR"]) {
    const awaiting = awaitingConfirmation();
    let state = agentChatReducer(awaiting.state, { type: "decide", approved: true });
    state = agentChatReducer(state, { type: "failed", code, message: "connection lost", retryConfirmation: true });

    assert.equal(state.pending, null);
    assert.equal(state.turns[0].decision, undefined);
    assert.throws(() => nextRequest(state, { approved: true }), /no pending action/);
  }
});

test("a refusal is shown only after the server acknowledges USER_REJECTED", () => {
  let { state } = awaitingConfirmation();
  state = agentChatReducer(state, { type: "decide", approved: false });
  assert.equal(state.turns[0].decision, undefined);
  state = agentChatReducer(state, { type: "event", event: { type: "step",
    data: step({ toolCallId: "c9", tool: "start_analysis", ok: false, errorCode: "USER_REJECTED" }) } });

  assert.equal(state.pending, null);
  assert.equal(state.turns[0].decision, "rejected");
  assert.equal(state.turns[0].analysisId, undefined);
});

test("a successful start_analysis step exposes the analysis id to follow", () => {
  let state = agentChatReducer(initialAgentChatState, { type: "ask", question: "帮我分析" });
  state = agentChatReducer(state, {
    type: "event",
    event: { type: "step", data: step({ tool: "start_analysis", resultPreview: "{\"analysisId\":42,\"status\":\"PENDING\"}" }) },
  });
  assert.equal(state.turns[0].analysisId, 42);

  let failed = agentChatReducer(initialAgentChatState, { type: "ask", question: "帮我分析" });
  failed = agentChatReducer(failed, {
    type: "event",
    event: { type: "step", data: step({ tool: "start_analysis", ok: false, errorCode: "USER_REJECTED", resultPreview: "用户拒绝执行" }) },
  });
  assert.equal(failed.turns[0].analysisId, undefined);
});

test("apiStream refreshes once on 401, then returns the event stream", async () => {
  setAccessToken("expired-access-token");
  const authorizations: Array<string | null> = [];
  const responses = [
    Response.json({ success: false, code: "UNAUTHORIZED", message: "expired", data: null }, { status: 401 }),
    Response.json({
      success: true,
      code: "OK",
      message: "success",
      data: { tokenType: "Bearer", accessToken: "fresh", expiresInSeconds: 900, user: { id: 1, username: "a", displayName: "A", email: "a@example.com" } },
    }),
    new Response("event:done\ndata:{}\n\n", { headers: { "Content-Type": "text/event-stream" } }),
  ];
  globalThis.fetch = async (_input, init) => {
    authorizations.push(new Headers(init?.headers).get("Authorization"));
    return responses.shift()!;
  };

  const stream = await apiStream("/api/agent/chat", { messages: [] });

  assert.equal(await new Response(stream).text(), "event:done\ndata:{}\n\n");
  assert.deepEqual(authorizations, ["Bearer expired-access-token", null, "Bearer fresh"]);
});

test("apiStream surfaces the JSON error of a rejected turn instead of reading it as SSE", async () => {
  setAccessToken("token");
  globalThis.fetch = async () => Response.json(
    { success: false, code: "AGENT_RATE_LIMITED", message: "too many assistant turns", data: null },
    { status: 429 },
  );

  await assert.rejects(apiStream("/api/agent/chat", { messages: [] }), (error: unknown) => {
    assert.equal((error as { code?: string }).code, "AGENT_RATE_LIMITED");
    assert.equal((error as { status?: number }).status, 429);
    return true;
  });
});

test("raw tool envelopes never render as answer body, normal closing text survives done", () => {
  const asked = agentChatReducer(initialAgentChatState, { type: "ask", question: "除了 Java 呢" });
  for (const raw of ['{"tool":"lookup","untrusted_data":{"jobs":[]}}',
    '已获得结果：\n{"untrusted_data":{"name":"秘密"}}', '[{"jobId":1}]']) {
    const shown = agentChatReducer(asked, { type: "event", event: { type: "message", data: { content: raw } } });
    assert.ok(!shown.turns[0].answer?.includes("untrusted_data"));
    assert.ok(!shown.turns[0].answer?.includes("jobId"));
  }
  const answer = "可以考虑后端测试开发；其他方向还未核实。";
  const shown = agentChatReducer(asked, { type: "event", event: { type: "message", data: { content: answer } } });
  const done = agentChatReducer(shown, { type: "event", event: { type: "done", data: {
    state: "STEP_LIMIT", steps: 7, transcript: [{ role: "user", content: "除了 Java 呢" }],
  } } });
  assert.equal(done.turns[0].answer, answer);
});
