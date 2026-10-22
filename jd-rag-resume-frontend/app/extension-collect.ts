/**
 * Runs a browser task the assistant handed to the page, through the ResumeLens extension's content script.
 * The page and the extension talk with window.postMessage on this page's own origin; only events from this
 * window, for this request, are read. Whatever happens, the promise settles with something the server can judge:
 * the extension's final event ("completed"), the user's cancel ("cancelled"), or running out of time ("expired").
 * A missing or outdated extension is reported as a rejected event, so the model can say so.
 */
import type { AgentBrowserTask, BrowserTaskOutcome, BrowserTaskProgress } from "./agent-stream";

/**
 * Same value as EXTENSION_BRIDGE_CHANNEL in extension-bridge-core.ts (tests/extension-collect.test.ts checks it).
 * Not imported: the unit tests load this file without a bundler, and only type imports resolve there.
 */
const EXTENSION_BRIDGE_CHANNEL = "resumelens-browser-extension-v1";

export const COLLECT_CHANNEL = EXTENSION_BRIDGE_CHANNEL;
export const PING_TIMEOUT_MS = 1_500;
/** Stop waiting a little before the server's deadline, so the report still arrives in time. */
export const DEADLINE_MARGIN_MS = 5_000;

type MessageTarget = Pick<Window, "postMessage" | "addEventListener" | "removeEventListener"> & {
  location: { origin: string };
};

export type ExtensionCollectOptions = {
  task: AgentBrowserTask;
  target: MessageTarget;
  /** Aborting it is the user's cancel. */
  signal: AbortSignal;
  onProgress?: (progress: BrowserTaskProgress) => void;
  now?: () => number;
  setTimer?: (callback: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  requestId?: () => string;
};

const FINAL_EVENTS = new Set(["result", "rejected"]);

export function runExtensionCollect(options: ExtensionCollectOptions): Promise<BrowserTaskOutcome> {
  const { task, target, signal, onProgress } = options;
  const now = options.now ?? Date.now;
  const setTimer = options.setTimer ?? ((callback: () => void, ms: number) => setTimeout(callback, ms));
  const clearTimer = options.clearTimer ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  const newId = options.requestId ?? (() => globalThis.crypto.randomUUID());
  // Counted from receipt on this machine's clock; the server's own clock still has the final say.
  const deadline = now() + Math.max(0, task.timeoutSeconds * 1000 - DEADLINE_MARGIN_MS);

  return new Promise((resolve) => {
    const pingId = newId();
    const commandId = newId();
    let commandSent = false;
    let settled = false;
    const timers: unknown[] = [];

    const finish = (outcome: BrowserTaskOutcome) => {
      if (settled) return;
      settled = true;
      timers.forEach(clearTimer);
      target.removeEventListener("message", onMessage as EventListener);
      signal.removeEventListener("abort", onAbort);
      resolve(outcome);
    };
    const rejected = (code: string) => finish({ outcome: "completed", report: { type: "rejected", code } });
    const send = (requestId: string, action: string, payload?: unknown) => {
      target.postMessage({ channel: EXTENSION_BRIDGE_CHANNEL, direction: "command", requestId, action, payload },
        target.location.origin);
    };
    const stopExtension = () => {
      if (commandSent) send(newId(), "collectCancel", { taskId: task.taskId });
    };

    function onMessage(event: MessageEvent) {
      if (event.source !== (target as unknown) || event.origin !== target.location.origin) return;
      const data = event.data as { channel?: unknown; direction?: unknown; requestId?: unknown; event?: unknown } | null;
      if (!data || data.channel !== EXTENSION_BRIDGE_CHANNEL || data.direction !== "event") return;
      const payload = data.event as Record<string, unknown> | null;
      if (!payload || typeof payload !== "object") return;
      if (data.requestId === pingId && payload.type === "pong") {
        const capabilities = Array.isArray(payload.capabilities) ? payload.capabilities : [];
        if (!capabilities.includes(task.action)) {
          rejected("EXTENSION_OUTDATED");
          return;
        }
        commandSent = true;
        send(commandId, task.action, { ...task.command, taskId: task.taskId });
        return;
      }
      if (data.requestId !== commandId) return;
      if (payload.type === "progress") {
        onProgress?.({
          phase: String(payload.phase ?? ""),
          page: typeof payload.page === "number" ? payload.page : undefined,
          jobCount: typeof payload.jobCount === "number" ? payload.jobCount : undefined,
        });
      } else if (FINAL_EVENTS.has(String(payload.type))) {
        finish({ outcome: "completed", report: payload });
      }
    }

    function onAbort() {
      stopExtension();
      finish({ outcome: "cancelled" });
    }

    if (signal.aborted) {
      finish({ outcome: "cancelled" });
      return;
    }
    if (task.executor !== "browser-extension") {
      rejected("EXTENSION_OUTDATED");
      return;
    }
    target.addEventListener("message", onMessage as EventListener);
    signal.addEventListener("abort", onAbort);
    timers.push(setTimer(() => {
      if (!commandSent) rejected("EXTENSION_NOT_FOUND");
    }, PING_TIMEOUT_MS));
    timers.push(setTimer(() => {
      stopExtension();
      finish({ outcome: "expired" });
    }, Math.max(0, deadline - now())));
    send(pingId, "ping");
  });
}
