(function exposeBossApiExtractor(root, factory) {
  const node = typeof module === "object" && module.exports;
  const extractor = factory(node ? require("./boss-list-extractor.js") : root.ResumeLensBossListExtractor);
  if (node) module.exports = extractor;
  root.ResumeLensBossApiExtractor = extractor;
})(typeof globalThis !== "undefined" ? globalThis : this, function createBossApiExtractor(list) {
  "use strict";

  if (!list) throw new Error("boss-list-extractor.js must be loaded before boss-api-extractor.js");

  /** Must equal the constants in boss-api-hook.js (checked by tests). */
  const BUFFER_KEY = "__resumeLensBossListCapture";
  const LIST_API_PATHS = Object.freeze(["/wapi/zpgeek/search/joblist.json"]);
  const DETAIL_ORIGIN = "https://www.zhipin.com";
  const MAX_ENTRIES = 10;
  const MAX_BODY_CHARS = 1_000_000;
  /** encryptJobId is opaque; anything that could break out of the detail URL path is refused. */
  const JOB_ID_PATTERN = /^[A-Za-z0-9_~.-]{1,160}$/;

  /**
   * Parses one captured list response. Returns null when the body is not a list response this
   * version recognizes (a changed shape is treated like no capture at all, so the DOM reading
   * takes over), otherwise the jobs and how many entries had no usable id.
   */
  function parseListResponse(body) {
    let parsed;
    try {
      parsed = typeof body === "string" ? JSON.parse(body) : null;
    } catch {
      return null;
    }
    const data = parsed && parsed.code === 0 && parsed.zpData;
    if (!data || !Array.isArray(data.jobList)) return null;
    const jobs = [];
    let skippedWithoutId = 0;
    for (const item of data.jobList) {
      const job = itemToJob(item);
      if (job) jobs.push(job);
      else skippedWithoutId += 1;
    }
    return { jobs, skippedWithoutId };
  }

  function itemToJob(item) {
    if (!item || typeof item !== "object") return null;
    const id = typeof item.encryptJobId === "string" ? item.encryptJobId.trim() : "";
    if (!JOB_ID_PATTERN.test(id)) return null;
    return list.listingJob({
      detailUrl: `${DETAIL_ORIGIN}/job_detail/${encodeURIComponent(id)}.html`,
      title: item.jobName,
      companyName: item.brandName,
      location: [item.cityName, item.areaDistrict, item.businessDistrict].filter(isText).join("·"),
      salary: item.salaryDesc,
      tags: [item.jobExperience, item.jobDegree, ...texts(item.jobLabels), ...texts(item.skills)],
      welfare: texts(item.welfareList).join("，"),
      captureSource: "api",
    });
  }

  /**
   * Combines one DOM read with the list responses captured since the previous read. The DOM still
   * decides the page state (ready, empty, login, captcha) and whether the list grew; the captured
   * responses only replace where the job fields come from. Jobs the responses cover are marked
   * "api"; cards they do not cover stay "dom". With no recognized response, the DOM read is
   * returned unchanged.
   */
  function combine(domSnapshot, captured) {
    if (!domSnapshot || !domSnapshot.state || domSnapshot.state.kind !== "ready") return domSnapshot;
    const apiJobs = [];
    const apiIds = new Set();
    let skippedWithoutId = 0;
    let recognized = 0;
    for (const entry of (Array.isArray(captured) ? captured : []).slice(-MAX_ENTRIES)) {
      if (!entry || typeof entry.body !== "string" || entry.body.length > MAX_BODY_CHARS) continue;
      if (!LIST_API_PATHS.includes(entry.path)) continue;
      const parsed = parseListResponse(entry.body);
      if (!parsed) continue;
      recognized += 1;
      skippedWithoutId += parsed.skippedWithoutId;
      for (const job of parsed.jobs) {
        if (apiIds.has(job.sourceJobId)) continue;
        apiIds.add(job.sourceJobId);
        apiJobs.push(job);
      }
    }
    if (recognized === 0) return domSnapshot;
    const domOnly = (domSnapshot.jobs || []).filter((job) => !apiIds.has(job.sourceJobId));
    return { state: domSnapshot.state, jobs: [...apiJobs, ...domOnly], skippedWithoutId };
  }

  function texts(value) {
    return Array.isArray(value) ? value.filter(isText) : [];
  }

  function isText(value) {
    return typeof value === "string" && value.trim() !== "";
  }

  return { BUFFER_KEY, LIST_API_PATHS, MAX_BODY_CHARS, MAX_ENTRIES, combine, parseListResponse };
});
