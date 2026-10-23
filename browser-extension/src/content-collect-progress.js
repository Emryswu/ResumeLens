(function installCollectProgress() {
  "use strict";
  if (globalThis.ResumeLensCollectProgress) return;

  globalThis.ResumeLensCollectProgress = {
    render(task, returnUrl) {
      let host = document.getElementById("resumelens-collect-progress");
      if (!host) {
        host = document.createElement("aside");
        host.id = "resumelens-collect-progress";
        document.documentElement.append(host);
        host.attachShadow({ mode: "open" });
      }
      const root = host.shadowRoot;
      root.replaceChildren();
      const style = document.createElement("style");
      style.textContent = ":host{position:fixed;right:20px;top:20px;z-index:2147483647;width:min(390px,calc(100vw - 40px));font:14px/1.6 system-ui;color:#193b39}section{background:#f0faf7;border:1px solid #b5d9cf;border-radius:16px;padding:16px;box-shadow:0 8px 32px #16393224}h2{font-size:15px;margin:0 0 8px}p{margin:4px 0}a{display:inline-block;margin-top:10px;padding:6px 12px;border-radius:999px;background:#236b5b;color:white;text-decoration:none}ol{padding-left:20px;margin:8px 0}";
      const box = document.createElement("section");
      box.setAttribute("aria-live", "polite");
      const title = document.createElement("h2");
      title.textContent = task.status === "running" ? "ResumeLens 正在读取 BOSS" : "ResumeLens 本次读取已结束";
      const summary = document.createElement("p");
      summary.textContent = `已读 ${task.pagesRead} 页，共 ${task.jobs.length} 条岗位`;
      const log = document.createElement("ol");
      for (const line of globalThis.ResumeLensCollectStatus.logLines(task)) {
        const item = document.createElement("li");
        item.textContent = line;
        log.append(item);
      }
      const back = document.createElement("a");
      back.textContent = "返回 ResumeLens";
      back.href = returnUrl;
      back.target = "_blank";
      back.rel = "noopener";
      box.append(title, summary, log, back);
      root.append(style, box);
    },
  };
})();
