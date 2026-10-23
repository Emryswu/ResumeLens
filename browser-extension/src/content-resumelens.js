(function installResumeLensBridgeContentScript() {
  "use strict";
  if (globalThis.__resumeLensBridgeContentInstalled) return;
  globalThis.__resumeLensBridgeContentInstalled = true;

  const CHANNEL = "resumelens-browser-extension-v1";

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || message.type !== "RESUMELENS_BRIDGE_CALL") return false;
    const requestId = requestIdentifier();
    let settled = false;

    const cleanup = () => {
      window.removeEventListener("message", onWindowMessage);
      window.clearTimeout(timer);
    };
    const finish = (response) => {
      if (settled) return;
      settled = true;
      cleanup();
      sendResponse(response);
    };
    const onWindowMessage = (event) => {
      if (event.source !== window || event.origin !== window.location.origin) return;
      const data = event.data;
      if (!data || data.channel !== CHANNEL || data.direction !== "response" || data.requestId !== requestId) return;
      finish(data.ok ? { ok: true, result: data.result } : { ok: false, error: data.error });
    };
    const timer = window.setTimeout(() => {
      finish({ ok: false, error: "ResumeLens 网页桥接超时，请刷新网页后重试" });
    }, 30_000);

    window.addEventListener("message", onWindowMessage);
    window.postMessage({
      channel: CHANNEL,
      direction: "request",
      requestId,
      action: message.action,
      payload: message.payload,
    }, window.location.origin);
    return true;
  });

  // Commands from the page (the assistant asking for a BOSS search) go to the service worker
  // over a port; every event the worker sends comes back as a window message.
  const relayModule = globalThis.ResumeLensCommandRelay;
  if (relayModule) {
    const relay = relayModule.create({
      connect: () => chrome.runtime.connect({ name: relayModule.PORT_NAME }),
      postToPage: (requestId, event) => {
        window.postMessage({ channel: CHANNEL, direction: "event", requestId, event }, window.location.origin);
      },
      version: chrome.runtime.getManifest().version,
      capabilities: ["collectBossJobs", "verifyBossJob", "listApiCapture", "dailyPageLimit", "experienceFilter"],
    });
    window.addEventListener("message", (event) => {
      if (event.source !== window || event.origin !== window.location.origin) return;
      const data = event.data;
      if (!data || data.channel !== CHANNEL || data.direction !== "command") return;
      if (typeof data.requestId !== "string" || !data.requestId || data.requestId.length > 100) return;
      if (!relayModule.isAction(data.action)) return;
      relay.handle({ requestId: data.requestId, action: data.action, payload: data.payload });
    });
  }

  function requestIdentifier() {
    if (globalThis.crypto && typeof globalThis.crypto.randomUUID === "function") {
      return globalThis.crypto.randomUUID();
    }
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }
})();
