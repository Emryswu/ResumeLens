const assert = require("node:assert/strict");
const test = require("node:test");

const relayModule = require("../src/command-relay.js");
const collectRunner = require("../src/collect-runner.js");

/** A port pair half: the relay holds this end, `worker` decides what the other end does. */
function fakePort(worker) {
  const messageListeners = [];
  const disconnectListeners = [];
  let connected = true;
  const port = {
    sent: [],
    disconnected: false,
    onMessage: { addListener: (listener) => messageListeners.push(listener) },
    onDisconnect: { addListener: (listener) => disconnectListeners.push(listener) },
    postMessage(message) {
      port.sent.push(message);
      queueMicrotask(() => worker(message, {
        send: (event) => { if (connected) messageListeners.forEach((listener) => listener(event)); },
        drop: () => {
          if (!connected) return;
          connected = false;
          disconnectListeners.forEach((listener) => listener());
        },
      }));
    },
    disconnect() {
      connected = false;
      port.disconnected = true;
    },
  };
  return port;
}

function setup(workers) {
  const pageEvents = [];
  const ports = [];
  const relay = relayModule.create({
    connect: () => {
      const worker = workers.shift();
      if (!worker) throw new Error("Extension context invalidated.");
      const port = fakePort(worker);
      ports.push(port);
      return port;
    },
    postToPage: (requestId, event) => pageEvents.push({ requestId, event }),
    version: "0.2.0",
    capabilities: ["collectBossJobs"],
  });
  return { relay, pageEvents, ports };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

test("ping answers with version and capabilities without waking the worker", () => {
  const { relay, pageEvents, ports } = setup([]);
  relay.handle({ requestId: "r1", action: "ping" });
  assert.deepEqual(pageEvents, [{ requestId: "r1", event: { type: "pong", version: "0.2.0", capabilities: ["collectBossJobs"] } }]);
  assert.equal(ports.length, 0);
});

test("progress streams over the port and the port closes after the result", async () => {
  const { relay, pageEvents, ports } = setup([(message, { send }) => {
    assert.equal(message.type, "start");
    send({ type: "accepted", task: { taskId: "t1", status: "running" } });
    send({ type: "progress", taskId: "t1", phase: "reading", page: 1, jobCount: 0 });
    send({ type: "result", task: { taskId: "t1", status: "completed" } });
    send({ type: "progress", taskId: "t1", phase: "late" });
  }]);
  relay.handle({ requestId: "r1", action: "collectBossJobs", payload: { taskId: "t1", keyword: "Java", city: "深圳", maxPages: 1 } });
  await settle();

  assert.deepEqual(pageEvents.map(({ event }) => event.type), ["accepted", "progress", "result"]);
  assert.deepEqual(ports[0].sent[0].command, { taskId: "t1", keyword: "Java", city: "深圳", maxPages: 1 });
  assert.equal(ports[0].disconnected, true);
});

test("a worker restart mid-task still reaches the page as WORKER_RESTARTED", async () => {
  // Storage the old worker wrote before it died: a running task with page 1 already read.
  const data = new Map([[`${collectRunner.KEY_PREFIX}t1`, {
    taskId: "t1", status: "running", pagesRead: 1, jobs: [{ sourceJobId: "a" }], epoch: "worker-1", updatedAt: 1,
  }]]);
  const storage = {
    async get(key) { return structuredClone(data.get(key)); },
    async set(key, value) { data.set(key, structuredClone(value)); },
    async remove(key) { data.delete(key); },
    async keys() { return [...data.keys()]; },
  };
  const restarted = collectRunner.create({
    browser: {}, storage, random: Math.random, sleep: async () => {}, now: () => 2, epoch: "worker-2",
  });

  const { relay, pageEvents, ports } = setup([
    // The original worker accepts the task, reports a page, then goes away without a result.
    (_message, { send, drop }) => {
      send({ type: "accepted", task: { taskId: "t1", status: "running" } });
      send({ type: "progress", taskId: "t1", phase: "read", page: 1, jobCount: 1 });
      drop();
    },
    // The restarted worker answers the status request from storage.
    async (message, { send }) => {
      assert.deepEqual(message, { type: "status", taskId: "t1" });
      send({ type: "result", task: await restarted.status("t1") });
    },
  ]);
  relay.handle({ requestId: "r1", action: "collectBossJobs", payload: { taskId: "t1", keyword: "Java", city: "深圳", maxPages: 3 } });
  await settle();
  await settle();

  assert.equal(ports.length, 2);
  const last = pageEvents.at(-1).event;
  assert.equal(last.type, "result");
  assert.equal(last.task.status, "stopped");
  assert.equal(last.task.stopReason, "WORKER_RESTARTED");
  assert.deepEqual(last.task.jobs, [{ sourceJobId: "a" }]);
});

test("if the worker cannot be reached twice, the page gets a definite failure", async () => {
  const { relay, pageEvents } = setup([
    (_message, { drop }) => drop(),
    (_message, { drop }) => drop(),
  ]);
  relay.handle({ requestId: "r1", action: "collectBossJobs", payload: { taskId: "t1" } });
  await settle();
  await settle();

  assert.deepEqual(pageEvents.map(({ event }) => event.code), ["WORKER_UNREACHABLE"]);
});

test("an orphaned content script reports EXTENSION_RELOADED instead of throwing", () => {
  const { relay, pageEvents } = setup([]);
  relay.handle({ requestId: "r1", action: "collectStatus", payload: { taskId: "t1" } });
  assert.equal(pageEvents[0].event.code, "EXTENSION_RELOADED");
});

test("only the known actions are accepted from the page", () => {
  assert.equal(relayModule.isAction("collectBossJobs"), true);
  assert.equal(relayModule.isAction("analyze"), false);
  assert.equal(relayModule.isAction("__proto__"), false);
});
