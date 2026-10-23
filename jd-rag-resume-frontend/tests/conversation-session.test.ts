import assert from "node:assert/strict";
import test from "node:test";
import { ConversationSession } from "../app/conversation-session.ts";
import { nextRequest } from "../app/agent-stream.ts";
import type { ConversationDetail } from "../app/conversation-client.ts";

const detail = (id = 1, state = "COMPLETED"): ConversationDetail => ({
  conversation: { id, title: `会话 ${id}`, state, activeRun: state === "RUNNING" ? "run" : null, eventSequence: 5, updatedAt: "now" },
  turns: [{ question: `提问 ${id}`, steps: [], started: [], unfinished: [], notes: ["已保存的过程"], answer: "已保存的回答" }], events: [],
});

test("polling the server while collection runs preserves the live operation log for that task", async (t) => {
  const saved = detail(1, "AWAITING_BROWSER");
  saved.turns[0].browserTask = { toolCallId: "c", taskId: "bt_1", tool: "collect_boss_jobs",
    executor: "browser-extension", action: "collectBossJobs", command: {}, expiresAt: Date.now() + 10000, timeoutSeconds: 10 };
  const client = new ConversationSession({ request: async <T>(path: string) =>
    structuredClone(path.endsWith("conversations") ? [saved.conversation] : saved) as T,
    stream: async () => { throw new Error("unused"); }, current: () => true });
  t.after(client.clear);
  await client.open(1, 1);
  const progress = { phase: "read", pagesRead: 1, pageLog: [{ page: 1, api: 2, dom: 1 }] };
  client.progress({ type: "browser_progress", progress });
  await client.refresh();
  assert.deepEqual(client.getSnapshot().chat.turns[0].browserProgress, progress);
  saved.turns[0].browserTask.taskId = "bt_2";
  await client.refresh();
  assert.equal(client.getSnapshot().chat.turns[0].browserProgress, undefined);
});

test("a new browser session restores full turns and does not submit any history", async (t) => {
  const saved = detail();
  const client = new ConversationSession({ request: async <T>(path: string) =>
    (path.endsWith("conversations") ? [saved.conversation] : saved) as T,
  stream: async () => { throw new Error("must not generate while restoring"); }, current: () => true });
  t.after(client.clear);
  await client.open(1, 1);
  assert.deepEqual(client.getSnapshot().chat.turns, saved.turns);
  assert.deepEqual(nextRequest(client.getSnapshot().chat, { question: "继续" }), { question: "继续" });
});

test("a broken stream reconciles a running write and never automatically retries it", async (t) => {
  let saved = detail();
  let posts = 0;
  const client = new ConversationSession({ request: async <T>(path: string) =>
    (path.endsWith("conversations") ? [saved.conversation] : saved) as T,
  stream: async () => { posts++; saved = detail(1, "RUNNING"); throw new Error("socket closed"); }, current: () => true });
  t.after(client.clear);
  await client.open(1, 1);
  await client.send({ question: "写操作" }, "high");
  assert.equal(client.getSnapshot().chat.busy, true);
  assert.equal(posts, 1);
  saved = detail(1, "COMPLETED");
  await client.refresh();
  assert.equal(client.getSnapshot().chat.busy, false);
  assert.equal(posts, 1);
  assert.deepEqual(client.getSnapshot().chat.turns, saved.turns);
});

test("late data from the previous login cannot populate the next account", async (t) => {
  let current = 1;
  let resolveOld!: (value: unknown) => void;
  const oldRequest = new Promise((resolve) => { resolveOld = resolve; });
  const client = new ConversationSession({ request: async <T>(path: string) => {
    if (current === 1) return await oldRequest as T;
    return (path.endsWith("conversations") ? [detail(2).conversation] : detail(2)) as T;
  }, stream: async () => { throw new Error("unused"); }, current: (session) => session === current });
  t.after(client.clear);
  const old = client.open(1, 1);
  current = 2;
  await client.open(2, 2);
  resolveOld([detail(1).conversation]);
  await old;
  assert.equal(client.getSnapshot().scope, "2:2");
  assert.equal(client.getSnapshot().detail?.conversation.id, 2);
  assert.equal(client.getSnapshot().items.some((item) => item.id === 1), false);
});

test("late detail from a previous selection cannot replace the selected conversation", async (t) => {
  let delayed = false;
  let resolveOld!: (value: unknown) => void;
  const oldDetail = new Promise((resolve) => { resolveOld = resolve; });
  const client = new ConversationSession({ request: async <T>(path: string) => {
    if (path.endsWith("conversations")) return [detail(1).conversation, detail(2).conversation] as T;
    if (delayed && path.endsWith("/1")) return await oldDetail as T;
    return (path.endsWith("/2") ? detail(2) : detail(1)) as T;
  }, stream: async () => { throw new Error("unused"); }, current: () => true });
  t.after(client.clear);
  await client.open(1, 1);
  delayed = true;
  const previous = client.select(1);
  await client.select(2);
  resolveOld(detail(1));
  await previous;
  assert.equal(client.getSnapshot().detail?.conversation.id, 2);
});

test("restored confirmation submits only the recorded call id and decision", async (t) => {
  let saved = detail(1, "AWAITING_CONFIRMATION");
  saved.turns[0].confirmation = { toolCallId: "write", tool: "start_analysis", arguments: { resumeId: 1, jobId: 2 } };
  let submitted: unknown;
  const client = new ConversationSession({ request: async <T>(path: string) =>
    (path.endsWith("conversations") ? [saved.conversation] : saved) as T,
  stream: async (_path, body) => {
    submitted = body;
    saved = detail();
    return new Response('event:done\ndata:{"state":"COMPLETED","steps":1,"transcript":[]}\n\n').body!;
  }, current: () => true });
  t.after(client.clear);
  await client.open(1, 1);
  await client.send(nextRequest(client.getSnapshot().chat, { approved: true }), "high");
  assert.deepEqual(submitted, { conversationId: 1, reasoningEffort: "high", approval: { toolCallId: "write", approved: true } });
});

test("restoring a browser task never executes collection or changes its deadline", async (t) => {
  const saved = detail(1, "AWAITING_BROWSER");
  const task = { toolCallId: "browser", tool: "collect_boss_jobs", taskId: "task", executor: "browser-extension", action: "collectBossJobs", command: {}, expiresAt: 9000, timeoutSeconds: 180 };
  saved.turns[0].browserTask = task;
  const client = new ConversationSession({ request: async <T>(path: string) =>
    (path.endsWith("conversations") ? [saved.conversation] : saved) as T,
  stream: async () => { throw new Error("restoration must not execute a pending task"); }, current: () => true });
  t.after(client.clear);
  await client.open(1, 1);
  assert.deepEqual(client.getSnapshot().chat.pendingBrowser, task);
  assert.equal(client.getSnapshot().chat.pendingBrowser?.expiresAt, 9000);
});

test("creating another conversation refreshes the previous conversation title from the server", async (t) => {
  let created = false;
  const first = detail(1);
  first.conversation.title = "新对话";
  const second = detail(2, "READY");
  second.turns = [];
  const client = new ConversationSession({ request: async <T>(path: string, options?: RequestInit) => {
    if (options?.method === "POST") { created = true; return second as T; }
    if (path.endsWith("conversations")) return (created ? [second.conversation, first.conversation] : [first.conversation]) as T;
    return structuredClone(first) as T;
  }, stream: async () => { throw new Error("unused"); }, current: () => true });
  t.after(client.clear);
  await client.open(1, 1);
  first.conversation.title = "第一段的实际标题";
  await client.create();
  assert.equal(client.getSnapshot().items.find((item) => item.id === 1)?.title, "第一段的实际标题");
});
