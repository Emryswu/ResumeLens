import assert from "node:assert/strict";
import test from "node:test";

import {
  agentChatReducer,
  initialAgentChatState,
  nextRequest,
  type AgentBrowserTask,
  type AgentChatState,
} from "../app/agent-stream.ts";
import { timelineSteps } from "../app/agent-steps.ts";
import { COLLECT_CHANNEL, DEADLINE_MARGIN_MS, PING_TIMEOUT_MS, runExtensionCollect } from "../app/extension-collect.ts";
import { EXTENSION_BRIDGE_CHANNEL } from "../app/extension-bridge-core.ts";

const ORIGIN = "http://localhost:3000";

const TASK: AgentBrowserTask = {
  toolCallId: "b1",
  tool: "collect_boss_jobs",
  taskId: "bt_1",
  executor: "browser-extension",
  action: "collectBossJobs",
  command: { keyword: "Java", city: "深圳", maxPages: 2 },
  expiresAt: 181_000,
  timeoutSeconds: 180,
};

type Posted = { requestId: string; action: string; payload?: Record<string, unknown> };

/** A window whose posted commands are recorded and whose replies are delivered as if from the extension. */
function fakeWindow() {
  const listeners = new Set<(event: MessageEvent) => void>();
  const posted: Posted[] = [];
  const target = {
    location: { origin: ORIGIN },
    postMessage(message: { direction: string; requestId: string; action: string; payload?: Record<string, unknown> }) {
      if (message.direction === "command") posted.push({ requestId: message.requestId, action: message.action, payload: message.payload });
    },
    addEventListener: (_type: string, listener: (event: MessageEvent) => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: (event: MessageEvent) => void) => listeners.delete(listener),
  };
  const reply = (requestId: string, event: unknown, overrides: Partial<{ source: unknown; origin: string }> = {}) => {
    for (const listener of [...listeners]) {
      listener({
        source: "source" in overrides ? overrides.source : target,
        origin: overrides.origin ?? ORIGIN,
        data: { channel: EXTENSION_BRIDGE_CHANNEL, direction: "event", requestId, event },
      } as unknown as MessageEvent);
    }
  };
  return { target, posted, reply, listeners };
}

/** Manual timers: fire(ms) runs every timer due at or before that offset. */
function fakeClock() {
  let time = 1_000;
  const timers: { at: number; callback: () => void; cleared: boolean }[] = [];
  return {
    now: () => time,
    setTimer: (callback: () => void, ms: number) => {
      const timer = { at: time + ms, callback, cleared: false };
      timers.push(timer);
      return timer;
    },
    clearTimer: (handle: unknown) => {
      (handle as { cleared: boolean }).cleared = true;
    },
    advance(ms: number) {
      time += ms;
      for (const timer of timers) {
        if (!timer.cleared && timer.at <= time) {
          timer.cleared = true;
          timer.callback();
        }
      }
    },
  };
}

function start(overrides: Partial<{ task: AgentBrowserTask; controller: AbortController }> = {}) {
  const win = fakeWindow();
  const clock = fakeClock();
  const controller = overrides.controller ?? new AbortController();
  let ids = 0;
  const progress: unknown[] = [];
  const result = runExtensionCollect({
    task: overrides.task ?? TASK,
    target: win.target as never,
    signal: controller.signal,
    onProgress: (item) => progress.push(item),
    now: clock.now,
    setTimer: clock.setTimer,
    clearTimer: clock.clearTimer,
    requestId: () => `r${++ids}`,
  });
  return { ...win, clock, controller, result, progress };
}

test("talks on the same channel as the extension bridge", () => {
  assert.equal(COLLECT_CHANNEL, EXTENSION_BRIDGE_CHANNEL);
});

test("pings first, then sends the task with the server's taskId and reports the final event", async () => {
  const run = start();
  assert.deepEqual(run.posted.map((item) => item.action), ["ping"]);
  run.reply("r1", { type: "pong", version: "0.3.0", capabilities: ["collectBossJobs"] });
  assert.deepEqual(run.posted[1], { requestId: "r2", action: "collectBossJobs", payload: { ...TASK.command, taskId: "bt_1" } });

  run.reply("r2", { type: "progress", phase: "reading", page: 1, jobCount: 0 });
  const final = { type: "result", task: { status: "completed", jobs: [] } };
  run.reply("r2", final);

  assert.deepEqual(await run.result, { outcome: "completed", report: final });
  assert.deepEqual(run.progress, [{ phase: "reading", page: 1, jobCount: 0 },
    { phase: "finished", pageLog: [], pagesRead: 0, finishReason: null, stopReason: null }]);
  assert.equal(run.listeners.size, 0, "the listener is removed once settled");
});

test("no extension answering the ping is reported as EXTENSION_NOT_FOUND", async () => {
  const run = start();
  run.clock.advance(PING_TIMEOUT_MS);
  assert.deepEqual(await run.result, { outcome: "completed", report: { type: "rejected", code: "EXTENSION_NOT_FOUND" } });
  assert.deepEqual(run.posted.map((item) => item.action), ["ping"], "nothing to cancel when nothing was started");
});

test("an extension without the capability is reported as outdated and gets no command", async () => {
  const run = start();
  run.reply("r1", { type: "pong", version: "0.1.0", capabilities: [] });
  assert.deepEqual(await run.result, { outcome: "completed", report: { type: "rejected", code: "EXTENSION_OUTDATED" } });
  assert.equal(run.posted.length, 1);
});

test("experience filtering requires a capable extension and reaches it unchanged", async () => {
  const task = { ...TASK, command: { ...TASK.command, keyword: "", experience: "在校生" } };
  const old = start({ task });
  old.reply("r1", { type: "pong", capabilities: ["collectBossJobs"] });
  assert.deepEqual(await old.result, { outcome: "completed", report: { type: "rejected", code: "EXTENSION_OUTDATED" } });
  assert.equal(old.posted.length, 1);
  const current = start({ task });
  current.reply("r1", { type: "pong", capabilities: ["collectBossJobs", "experienceFilter"] });
  assert.equal(current.posted[1].payload?.experience, "在校生");
  assert.equal(current.posted[1].payload?.keyword, "");
  current.reply("r2", { type: "result", task: {} });
  await current.result;
});

test("the user's cancel stops the extension and reports cancelled", async () => {
  const run = start();
  run.reply("r1", { type: "pong", capabilities: ["collectBossJobs"] });
  run.controller.abort();
  assert.deepEqual(await run.result, { outcome: "cancelled" });
  assert.deepEqual(run.posted.at(-1)?.action, "collectCancel");
  assert.deepEqual(run.posted.at(-1)?.payload, { taskId: "bt_1" });
});

test("running out of time stops the extension a little before the server's deadline", async () => {
  const run = start();
  run.reply("r1", { type: "pong", capabilities: ["collectBossJobs"] });
  run.clock.advance(TASK.timeoutSeconds * 1000 - DEADLINE_MARGIN_MS - 1);
  run.reply("r2", { type: "progress", phase: "waiting", page: 2 });
  run.clock.advance(1);
  assert.deepEqual(await run.result, { outcome: "expired" });
  assert.equal(run.posted.at(-1)?.action, "collectCancel");
  // A result that turns up afterwards changes nothing.
  run.reply("r2", { type: "result", task: {} });
});

test("events from another window, another origin or another request are ignored", async () => {
  const run = start();
  run.reply("r1", { type: "pong", capabilities: ["collectBossJobs"] }, { source: {} });
  run.reply("r1", { type: "pong", capabilities: ["collectBossJobs"] }, { origin: "https://evil.example" });
  assert.equal(run.posted.length, 1, "a forged pong does not start the task");
  run.reply("r1", { type: "pong", capabilities: ["collectBossJobs"] });
  run.reply("other", { type: "result", task: { forged: true } });
  run.reply("r2", { type: "result", task: { forged: true } }, { origin: "https://evil.example" });
  run.reply("r2", { type: "rejected", code: "BUSY" });
  assert.deepEqual(await run.result, { outcome: "completed", report: { type: "rejected", code: "BUSY" } });
});

test("a task for another executor is not run at all", async () => {
  const run = start({ task: { ...TASK, executor: "server" } });
  assert.deepEqual(await run.result, { outcome: "completed", report: { type: "rejected", code: "EXTENSION_OUTDATED" } });
  assert.equal(run.posted.length, 0);
});

function awaitingBrowser(): AgentChatState {
  let state = agentChatReducer(initialAgentChatState, { type: "ask", question: "去 BOSS 找 Java" });
  const transcript = [
    { role: "user" as const, content: "去 BOSS 找 Java" },
    { role: "assistant" as const, content: null, tool_calls: [{ id: "b1", type: "function" as const, function: { name: "collect_boss_jobs", arguments: "{}" } }] },
  ];
  state = agentChatReducer(state, { type: "event", event: { type: "browser_task_required", data: TASK } });
  return agentChatReducer(state, { type: "event", event: { type: "done", data: { state: "AWAITING_BROWSER", steps: 1, transcript } } });
}

test("a turn that ends waiting on the browser keeps the task and locks retry", () => {
  const state = awaitingBrowser();
  assert.equal(state.pendingBrowser?.taskId, "bt_1");
  assert.equal(state.busy, false);
  const [row] = timelineSteps(state.turns[0], null);
  assert.equal(row.status, "browser");
});

test("an interrupted stream drops a browser task whose transcript was never received", () => {
  let state = agentChatReducer(initialAgentChatState, { type: "ask", question: "去 BOSS 找 Java" });
  state = agentChatReducer(state, { type: "event", event: { type: "browser_task_required", data: TASK } });
  assert.equal(state.busy, true);
  state = agentChatReducer(state, { type: "failed", code: "AGENT_STREAM_INTERRUPTED", message: "连接中断" });
  state = agentChatReducer(state, { type: "settled" });
  assert.equal(state.pendingBrowser, null);
  assert.equal("transcript" in state, false);
  assert.throws(() => nextRequest(state, { browser: { outcome: "cancelled" } }), /no browser task/);
});

test("the report request carries the server's ids and only a completed outcome carries the report", () => {
  const state = awaitingBrowser();
  const completed = nextRequest(state, { browser: { outcome: "completed", report: { type: "result" } } });
  assert.ok("browserResult" in completed);
  assert.deepEqual(completed.browserResult, { toolCallId: "b1", taskId: "bt_1", outcome: "completed", report: { type: "result" } });
  assert.equal("messages" in completed, false);
  const cancelled = nextRequest(state, { browser: { outcome: "cancelled" } });
  assert.ok("browserResult" in cancelled);
  assert.deepEqual(cancelled.browserResult, { toolCallId: "b1", taskId: "bt_1", outcome: "cancelled" });
  assert.equal("approval" in cancelled, false);
});

test("sending the report clears the pending task and shows it as reporting until the result arrives", () => {
  const state = agentChatReducer(awaitingBrowser(), { type: "browser_report", outcome: "expired" });
  assert.equal(state.pendingBrowser, null);
  assert.equal(state.busy, true);
  assert.equal(timelineSteps(state.turns[0], null)[0].status, "reporting");
  assert.throws(() => nextRequest({ ...state, busy: false }, { browser: { outcome: "cancelled" } }), /no browser task/);
});

test("an interrupted browser report is unfinished after the server records done", () => {
  let state = agentChatReducer(awaitingBrowser(), { type: "browser_report", outcome: "expired" });
  state = agentChatReducer(state, { type: "event", event: { type: "done", data: { state: "FAILED", steps: 0 } } });
  assert.equal(timelineSteps(state.turns[0], null)[0].status, "unfinished");
});

test("a new question or a finished turn clears a pending browser task", () => {
  assert.equal(agentChatReducer(awaitingBrowser(), { type: "ask", question: "换个问题" }).pendingBrowser, null);
  const done = agentChatReducer(awaitingBrowser(), {
    type: "event", event: { type: "done", data: { state: "COMPLETED", steps: 2, transcript: [] } },
  });
  assert.equal(done.pendingBrowser, null);
});

test("restoring a task uses its original deadline instead of restarting the timeout", async () => {
  const run = start({ task: { ...TASK, expiresAt: 11_000 } });
  run.reply(run.posted[0].requestId, { type: "pong", capabilities: ["collectBossJobs"] });
  run.clock.advance(10_000 - DEADLINE_MARGIN_MS);
  assert.deepEqual(await run.result, { outcome: "expired" });
});

test("an already expired task never contacts the extension", async () => {
  const run = start({ task: { ...TASK, expiresAt: 999 } });
  assert.deepEqual(await run.result, { outcome: "expired" });
  assert.equal(run.posted.length, 0);
});
