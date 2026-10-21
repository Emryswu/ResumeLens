(function runPopup() {
  "use strict";

  const RESUMELENS_MATCHES = ["http://localhost:3000/*", "http://127.0.0.1:3000/*"];
  const BOSS_ORIGINS = [...globalThis.ResumeLensBossSearch.PERMISSION_ORIGINS];
  const requestGate = globalThis.ResumeLensPopupRequestGate.create();
  const { asList } = globalThis.ResumeLensReportList;
  const state = {
    job: null,
    existingJob: null,
    bridgeTab: null,
    latestAnalysis: null,
    busy: false,
  };

  const element = (id) => document.getElementById(id);
  const form = element("job-form");
  const status = element("status");
  const duplicate = element("duplicate");
  const warnings = element("warnings");
  const resumeSelect = element("resumeId");
  const analyzeButton = element("analyze");
  const reanalyzeButton = element("reanalyze");

  document.addEventListener("DOMContentLoaded", () => {
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      void analyze(false);
    });
    element("refresh-job").addEventListener("click", () => void initialize());
    element("open-resumelens").addEventListener("click", () => {
      void chrome.tabs.create({ url: "http://localhost:3000" });
    });
    resumeSelect.addEventListener("change", () => void loadPreviousAnalysis());
    reanalyzeButton.addEventListener("click", () => void analyze(true));
    element("collect-permission-toggle").addEventListener("click", () => void toggleCollectPermission());
    void renderCollectPermission();
    void initialize();
  });

  // Optional, so the extension still holds no standing BOSS access until the user opts in here.
  // chrome.permissions.request must run inside the click handler's user gesture.
  async function toggleCollectPermission() {
    const granted = await chrome.permissions.contains({ origins: BOSS_ORIGINS });
    if (granted) await chrome.permissions.remove({ origins: BOSS_ORIGINS });
    else await chrome.permissions.request({ origins: BOSS_ORIGINS });
    await renderCollectPermission();
  }

  async function renderCollectPermission() {
    const granted = await chrome.permissions.contains({ origins: BOSS_ORIGINS });
    element("collect-permission-text").textContent = granted
      ? "已允许 AI 助手在 BOSS 直聘只读采集岗位：打开搜索页、滚动、读取列表，不会点击或发送消息。"
      : "允许后，ResumeLens 的 AI 助手可以在这个浏览器里打开 BOSS 搜索页并读取岗位列表。";
    element("collect-permission-toggle").textContent = granted ? "撤销授权" : "允许访问 BOSS 直聘";
    show(element("collect-permission"));
  }

  async function initialize() {
    requestGate.cancel();
    state.job = null;
    state.existingJob = null;
    state.bridgeTab = null;
    state.latestAnalysis = null;
    setBusy(true);
    hide(form);
    hide(element("open-resumelens"));
    hide(duplicate);
    hide(warnings);
    hide(element("result"));
    setStatus("正在读取当前 BOSS 岗位…", "working");
    try {
      const activeTab = await activeBossTab();
      const extraction = await sendWithInjection(
        activeTab.id,
        { type: "RESUMELENS_EXTRACT_BOSS_JOB" },
        ["src/boss-extractor.js", "src/content-boss.js"],
      );
      if (!extraction || !extraction.ok) throw new Error(extraction?.error || "岗位抓取失败");
      state.job = extraction.job;
      fillJobForm(extraction.job);
      show(form);
      renderWarnings(extraction.warnings || []);
      await connectResumeLens();
    } catch (reason) {
      setStatus(friendlyError(reason), "error");
    } finally {
      setBusy(false);
    }
  }

  async function connectResumeLens() {
    const tabs = await chrome.tabs.query({ url: RESUMELENS_MATCHES });
    state.bridgeTab = tabs.filter((tab) => Number.isInteger(tab.id))
      .sort((left, right) => Number(right.lastAccessed || 0) - Number(left.lastAccessed || 0))[0] || null;
    if (!state.bridgeTab) {
      show(element("open-resumelens"));
      throw new Error("还没找到 ResumeLens 网页：先打开并登录，再点一次刷新");
    }

    const prepared = await bridgeCall("prepare", {
      sourcePlatform: state.job.sourcePlatform,
      sourceJobId: state.job.sourceJobId,
    });
    fillResumes(prepared.resumes || []);
    state.existingJob = prepared.existingJob || null;
    if (state.existingJob) {
      duplicate.textContent = `这个岗位已在个人岗位库中（#${state.existingJob.id}），不会重复建档。`;
      show(duplicate);
    }
    setStatus(`已抓取「${state.job.title || "未识别职位"}」，可以先修改再分析`, "success");
    await loadPreviousAnalysis();
  }

  async function loadPreviousAnalysis() {
    const resumeId = Number(resumeSelect.value);
    const token = requestGate.begin(resumeId);
    state.latestAnalysis = null;
    hide(reanalyzeButton);
    hide(element("result"));
    if (!state.existingJob || !resumeId || !state.bridgeTab) return;
    duplicate.textContent = `这个岗位已在个人岗位库中（#${state.existingJob.id}），不会重复建档。`;
    show(duplicate);
    setStatus("正在读取当前简历的历史分析…", "working");
    try {
      const analysis = await bridgeCall("latestAnalysis", {
        resumeId,
        jobDescriptionId: state.existingJob.id,
      });
      if (!requestIsCurrent(token)) return;
      if (!analysis) {
        duplicate.textContent = "这个岗位已在个人岗位库中，当前简历还没有分析记录。";
        show(duplicate);
        setStatus("可以为当前简历启动一次新分析", "success");
        return;
      }
      state.latestAnalysis = analysis;
      renderAnalysis(analysis);
      if (analysis.status === "COMPLETED") {
        duplicate.textContent = "这个岗位和当前简历以前分析过，已直接载入历史结果。";
        show(reanalyzeButton);
        setStatus("已载入当前简历的历史分析", "success");
      } else if (analysis.status === "PENDING") {
        duplicate.textContent = "这个岗位正在分析，已接回现有任务，不会重复提交。";
        void pollAnalysis(analysis, token).catch((reason) => {
          if (requestIsCurrent(token)) setStatus(friendlyError(reason), "error");
        });
      } else {
        setStatus(analysis.summary || "上次分析失败，可以重新分析", "error");
        show(reanalyzeButton);
      }
      show(duplicate);
    } catch (reason) {
      if (requestIsCurrent(token)) setStatus(friendlyError(reason), "error");
    }
  }

  async function analyze(forceReanalyze) {
    if (state.busy) return;
    if (!form.reportValidity()) return;
    if (!state.bridgeTab) {
      setStatus("请先打开并登录 ResumeLens", "error");
      show(element("open-resumelens"));
      return;
    }

    const resumeId = Number(resumeSelect.value);
    const token = requestGate.begin(resumeId);
    setBusy(true);
    setStatus(forceReanalyze ? "正在重新提交分析…" : "正在保存岗位并启动分析…", "working");
    try {
      const response = await bridgeCall("analyze", {
        resumeId,
        forceReanalyze,
        job: jobFromForm(),
      });
      if (!requestIsCurrent(token)) return;
      state.existingJob = response.job;
      state.latestAnalysis = response.analysis;
      duplicate.textContent = response.reusedAnalysis
        ? "识别到已有岗位，已复用上次分析，没有重复调用模型。"
        : response.contentChanged
          ? "岗位内容有变化，已更新个人岗位库并创建新分析。"
          : "岗位已存入个人岗位库，分析任务已创建。";
      show(duplicate);
      renderAnalysis(response.analysis);
      if (response.analysis.status === "PENDING") {
        void pollAnalysis(response.analysis, token).catch((reason) => {
          if (requestIsCurrent(token)) setStatus(friendlyError(reason), "error");
        });
      } else {
        setStatus(response.reusedAnalysis ? "已载入历史分析" : "分析完成", "success");
      }
    } catch (reason) {
      if (requestIsCurrent(token)) setStatus(friendlyError(reason), "error");
    } finally {
      if (requestIsCurrent(token)) setBusy(false);
    }
  }

  async function pollAnalysis(initial, token) {
    let current = initial;
    if (!requestIsCurrent(token)) return null;
    renderAnalysis(current);
    for (let attempt = 0; attempt < 300 && current.status === "PENDING"; attempt += 1) {
      setStatus(`分析进行中${"·".repeat((attempt % 3) + 1)}`, "working");
      await delay(2_000);
      if (!requestIsCurrent(token)) return null;
      const next = await bridgeCall("getAnalysis", { id: current.id });
      if (!requestIsCurrent(token)) return null;
      current = next;
      state.latestAnalysis = current;
      renderAnalysis(current);
    }
    if (current.status === "COMPLETED") {
      setStatus("分析完成，结果也已经写进个人岗位库啦", "success");
      show(reanalyzeButton);
    } else if (current.status === "FAILED") {
      throw new Error(current.summary || "AI 分析失败");
    } else {
      setStatus("分析仍在后台跑，稍后重新打开扩展就能接着看", "working");
    }
    return requestIsCurrent(token) ? current : null;
  }

  async function bridgeCall(action, payload) {
    if (!state.bridgeTab || !Number.isInteger(state.bridgeTab.id)) throw new Error("ResumeLens 网页未连接");
    const response = await sendWithInjection(
      state.bridgeTab.id,
      { type: "RESUMELENS_BRIDGE_CALL", action, payload },
      ["src/command-relay.js", "src/content-resumelens.js"],
    );
    if (!response || !response.ok) throw new Error(response?.error || "ResumeLens 请求失败");
    return response.result;
  }

  async function sendWithInjection(tabId, message, files) {
    try {
      return await chrome.tabs.sendMessage(tabId, message);
    } catch {
      for (const file of files) {
        await chrome.scripting.executeScript({ target: { tabId }, files: [file] });
      }
      return chrome.tabs.sendMessage(tabId, message);
    }
  }

  async function activeBossTab() {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab || !Number.isInteger(tab.id) || !/^https:\/\/(?:[^./]+\.)*zhipin\.com\//i.test(tab.url || "")) {
      throw new Error("请先打开一个 BOSS 直聘职位详情页");
    }
    return tab;
  }

  function fillJobForm(job) {
    for (const key of ["title", "companyName", "location", "employmentType", "description", "requirements"]) {
      element(key).value = job[key] || (key === "employmentType" ? "全职" : "");
    }
  }

  function fillResumes(resumes) {
    resumeSelect.replaceChildren();
    if (!resumes.length) {
      const option = new Option("请先在 ResumeLens 保存一份简历", "");
      resumeSelect.add(option);
      analyzeButton.disabled = true;
      return;
    }
    for (const resume of resumes) {
      resumeSelect.add(new Option(
        `${resume.title}${resume.candidateName ? ` · ${resume.candidateName}` : ""}`,
        String(resume.id),
      ));
    }
    analyzeButton.disabled = false;
  }

  function jobFromForm() {
    return {
      title: element("title").value.trim(),
      companyName: element("companyName").value.trim(),
      location: element("location").value.trim(),
      employmentType: element("employmentType").value,
      description: element("description").value.trim(),
      requirements: element("requirements").value.trim(),
      sourcePlatform: state.job.sourcePlatform,
      sourceUrl: state.job.sourceUrl,
      sourceJobId: state.job.sourceJobId,
    };
  }

  function renderWarnings(items) {
    if (!items.length) {
      hide(warnings);
      return;
    }
    warnings.textContent = items.join("；");
    show(warnings);
  }

  function renderAnalysis(analysis) {
    show(element("result"));
    const score = Number(analysis.matchScore);
    element("score").textContent = Number.isFinite(score) ? `${score.toFixed(0)}%` : "—";
    element("analysis-status").textContent = statusLabel(analysis.status);
    element("summary").textContent = analysis.summary || (analysis.status === "PENDING" ? "正在检索简历证据并生成分析…" : "");

    const details = element("result-details");
    details.replaceChildren();
    appendList(details, "优势", analysis.strengths);
    appendList(details, "缺口", analysis.missingSkills);
    appendList(details, "改进建议", analysis.improvementSuggestions);
    appendList(details, "面试题", analysis.interviewQuestions);

    const report = element("full-report");
    if (analysis.id && state.bridgeTab?.url) {
      const origin = new URL(state.bridgeTab.url).origin;
      report.href = `${origin}/?resumeId=${analysis.resumeId}&jobId=${analysis.jobDescriptionId}&analysisId=${analysis.id}#result`;
      show(report);
    }
  }

  function appendList(container, title, raw) {
    const items = asList(raw);
    if (!items.length) return;
    const block = document.createElement("section");
    block.className = "detail-block";
    const heading = document.createElement("h2");
    heading.textContent = title;
    const list = document.createElement("ul");
    for (const item of items.slice(0, 5)) {
      const row = document.createElement("li");
      row.textContent = item;
      list.append(row);
    }
    block.append(heading, list);
    container.append(block);
  }

  function statusLabel(value) {
    if (value === "COMPLETED") return "已完成";
    if (value === "FAILED") return "失败";
    return "分析中";
  }

  function setBusy(value) {
    state.busy = value;
    for (const id of ["title", "companyName", "location", "employmentType", "description", "requirements"]) {
      element(id).disabled = value;
    }
    analyzeButton.disabled = value || !resumeSelect.value;
    reanalyzeButton.disabled = value;
    resumeSelect.disabled = value;
    element("refresh-job").disabled = value;
  }

  function requestIsCurrent(token) {
    return requestGate.isCurrent(token, Number(resumeSelect.value));
  }

  function setStatus(message, tone) {
    status.textContent = message;
    status.dataset.tone = tone;
  }

  function friendlyError(reason) {
    const message = reason instanceof Error ? reason.message : String(reason || "操作失败");
    if (/401|Unauthorized|authentication|登录已过期/i.test(message)) {
      show(element("open-resumelens"));
      return "ResumeLens 登录已失效，请回到网页重新登录后再刷新";
    }
    return message;
  }

  function show(node) { node.classList.remove("hidden"); }
  function hide(node) { node.classList.add("hidden"); }
  function delay(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
})();
