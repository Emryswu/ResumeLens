import http from "node:http";
import { timingSafeEqual, randomUUID } from "node:crypto";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";
import { createRequire } from "node:module";
import { fileStore } from "./file-store.mjs";
import { createPatchrightBrowser, userProfileDir } from "./patchright-browser.mjs";
const require = createRequire(import.meta.url);
const runnerModule = require("../../browser-extension/src/collect-runner.js");
const search = require("../../browser-extension/src/boss-search.js");

export async function createCollectorService({ token, dataDir, chromium, browserFactory = createPatchrightBrowser,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)) }) {
  if (!token) throw new Error("COLLECTOR_TOKEN is required");
  const owners = await fileStore(join(dataDir, "task-owners.json"));
  const runners = new Map();
  const browsers = [];
  async function runnerFor(userId) {
    if (!runners.has(userId)) runners.set(userId, (async () => {
      const storage = await fileStore(join(dataDir, "state", `${userId}.json`));
      const browser = await browserFactory({ chromium, profileDir: userProfileDir(dataDir, userId) });
      browsers.push(browser);
      const runner = runnerModule.create({ browser, storage, daily: storage,
        random: Math.random, now: Date.now, epoch: randomUUID(), sleep,
      });
      await runner.recoverStaleTasks();
      return runner;
    })());
    return runners.get(userId);
  }
  const server = http.createServer(async (req, res) => {
    const send = (status, value) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(value)); };
    try {
      const provided = Buffer.from(req.headers.authorization || "");
      const expected = Buffer.from(`Bearer ${token}`);
      if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) return send(401, { error: "unauthorized" });
      if (req.method === "POST" && req.url === "/tasks") {
        if (!req.headers["content-type"]?.startsWith("application/json")) return send(415, { error: "JSON required" });
        let body = "";
        for await (const chunk of req) { body += chunk; if (body.length > 8192) return send(413, { error: "request too large" }); }
        let command;
        try { command = JSON.parse(body); } catch { return send(400, { error: "invalid JSON" }); }
        if (!Number.isSafeInteger(command.userId) || command.userId < 1 || typeof command.conversationId !== "string") return send(400, { error: "invalid owner" });
        if (!Number.isInteger(command.pageAllowance) || command.pageAllowance < 1 || command.pageAllowance > 3) return send(400, { error: "page allowance required" });
        const validation = command.mode === "detail" ? search.validateDetailCommand(command) : search.validateCommand(command);
        if (!validation.ok) return send(400, { error: validation.error });
        const owner = await owners.get(command.taskId);
        if (owner != null && owner !== command.userId) return send(409, { error: "task already owned" });
        const runner = await runnerFor(command.userId);
        await owners.set(command.taskId, command.userId);
        const result = await runner.start(command);
        return send(200, result.ok ? { type: "accepted", task: result.task } : { type: "rejected", code: result.code, error: result.error });
      }
      const match = req.url.match(/^\/tasks\/([A-Za-z0-9_-]{1,100})(\/cancel)?$/);
      if (!match) return send(404, { error: "unknown route" });
      const owner = await owners.get(match[1]);
      if (owner == null) return send(404, { error: "unknown task" });
      const runner = await runnerFor(owner);
      if (req.method === "POST" && match[2]) return send(200, { task: await runner.cancel(match[1]) });
      if (req.method !== "GET" || match[2]) return send(405, { error: "method not allowed" });
      const task = await runner.status(match[1]);
      return send(200, { type: task.status === "running" ? "progress" : "result", task });
    } catch (error) {
      console.error("Collector request failed:", error.message);
      return send(500, { error: "collector request failed" });
    }
  });
  return { server, async close() { await Promise.all(browsers.map((browser) => browser.close())); await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())); } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const { chromium } = await import("patchright");
  const service = await createCollectorService({ chromium, token: process.env.COLLECTOR_TOKEN,
    dataDir: resolve(process.env.COLLECTOR_DATA_DIR || "data") });
  service.server.listen(Number(process.env.COLLECTOR_PORT || 3108), "127.0.0.1", () => console.log("ResumeLens collector listening on 127.0.0.1:3108"));
  for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, async () => { await service.close(); process.exit(0); });
}
