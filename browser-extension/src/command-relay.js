(function exposeCommandRelay(root, factory) {
  const relay = factory();
  if (typeof module === "object" && module.exports) module.exports = relay;
  root.ResumeLensCommandRelay = relay;
})(typeof globalThis !== "undefined" ? globalThis : this, function createCommandRelayModule() {
  "use strict";

  const PORT_NAME = "resumelens-collect";
  const ACTIONS = Object.freeze(["ping", "collectBossJobs", "verifyBossJob", "collectStatus", "collectCancel"]);
  /** Events after which the worker has nothing more to say on this port. */
  const FINAL_EVENTS = new Set(["result", "rejected", "cancel_requested"]);

  /**
   * Relays commands from the ResumeLens page to the service worker over a long-lived port and
   * forwards every event back. Results never ride on a single sendMessage callback: if the port
   * drops before a final event (the worker was restarted), the relay reconnects once and asks
   * for the task's stored status, so the page still learns how the task ended.
   *
   * Dependencies: connect() → Port, postToPage(requestId, event), version, capabilities.
   */
  function create({ connect, postToPage, version, capabilities }) {
    function handle(request) {
      const { requestId, action } = request;
      const payload = request.payload && typeof request.payload === "object" ? request.payload : {};
      const taskId = typeof payload.taskId === "string" ? payload.taskId : "";
      switch (action) {
        case "ping":
          postToPage(requestId, { type: "pong", version, capabilities });
          return;
        case "collectBossJobs":
          open(requestId, taskId, { type: "start", taskId, command: payload }, true);
          return;
        case "verifyBossJob":
          open(requestId, taskId, { type: "start", taskId, command: { ...payload, mode: "detail" } }, true);
          return;
        case "collectStatus":
          open(requestId, taskId, { type: "status", taskId }, true);
          return;
        case "collectCancel":
          open(requestId, taskId, { type: "cancel", taskId }, false);
          return;
        default:
          postToPage(requestId, { type: "rejected", taskId, code: "UNKNOWN_ACTION", error: "插件不认识这条指令" });
      }
    }

    function open(requestId, taskId, message, recoverable) {
      let port;
      try {
        port = connect();
      } catch {
        // Thrown after the extension was reloaded or updated: this content script is orphaned.
        postToPage(requestId, {
          type: "rejected",
          taskId,
          code: "EXTENSION_RELOADED",
          error: "插件刚被重新加载，请刷新 ResumeLens 网页后再试",
        });
        return;
      }
      let settled = false;
      port.onMessage.addListener((event) => {
        if (settled || !event || typeof event !== "object") return;
        postToPage(requestId, event);
        if (FINAL_EVENTS.has(event.type)) {
          settled = true;
          port.disconnect();
        }
      });
      port.onDisconnect.addListener(() => {
        if (settled) return;
        settled = true;
        if (recoverable && taskId) {
          open(requestId, taskId, { type: "status", taskId }, false);
          return;
        }
        postToPage(requestId, {
          type: "rejected",
          taskId,
          code: "WORKER_UNREACHABLE",
          error: "插件后台没有响应，请在 chrome://extensions 里确认插件已启用",
        });
      });
      port.postMessage(message);
    }

    return { handle };
  }

  function isAction(value) {
    return ACTIONS.includes(value);
  }

  return { ACTIONS, PORT_NAME, create, isAction };
});
