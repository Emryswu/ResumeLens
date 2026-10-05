import assert from "node:assert/strict";
import test from "node:test";

import {
  agentChatReducer,
  createSseParser,
  initialAgentChatState,
  nextRequest,
  readAgentStream,
  type AgentEvent,
  type AgentStep,
} from "../app/agent-stream.ts";
import { apiStream, clearAuthSession, setAccessToken } from "../app/lib/api.ts";

test.afterEach(() => {
  clearAuthSession();
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
  const bytes = new TextEncoder().encode(
    "event:message\ndata:{\"content\":\"你好\"}\n\nevent:mystery\ndata:{}\n\nevent:step\ndata:not json\n\n",
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

  assert.deepEqual(events, [{ type: "message", data: { content: "你好" } }]);
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

test("confirmation stays pending until decided, and the decision targets that exact call", () => {
  let state = agentChatReducer(initialAgentChatState, { type: "ask", question: "帮我分析" });
  const confirmation = { toolCallId: "c9", tool: "start_analysis", arguments: { resumeId: 1, jobId: 2 } };
  state = agentChatReducer(state, { type: "event", event: { type: "confirmation_required", data: confirmation } });
  state = agentChatReducer(state, {
    type: "event",
    event: { type: "done", data: { state: "AWAITING_CONFIRMATION", steps: 1, transcript: [] } },
  });

  assert.deepEqual(state.pending, confirmation);
  assert.equal(state.busy, false);
  assert.deepEqual(nextRequest(state, { approved: false }), {
    messages: [],
    approval: { toolCallId: "c9", approved: false },
  });

  state = agentChatReducer(state, { type: "decide", approved: true });
  assert.equal(state.pending, null);
  assert.equal(state.turns[0].decision, "approved");
  assert.throws(() => nextRequest(state, { approved: true }), /no pending action/);
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
