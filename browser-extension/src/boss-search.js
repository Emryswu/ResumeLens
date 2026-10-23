(function exposeBossSearch(root, factory) {
  const search = factory();
  if (typeof module === "object" && module.exports) module.exports = search;
  root.ResumeLensBossSearch = search;
})(typeof globalThis !== "undefined" ? globalThis : this, function createBossSearch() {
  "use strict";

  /**
   * The only cities a command may name. The extension builds the search URL itself from this
   * table, so a command can never point the browser at an arbitrary page.
   */
  const CITY_CODES = Object.freeze({
    全国: "100010000",
    北京: "101010100",
    上海: "101020100",
    广州: "101280100",
    深圳: "101280600",
    杭州: "101210100",
    南京: "101190100",
    苏州: "101190400",
    成都: "101270100",
    武汉: "101200100",
    西安: "101110100",
    天津: "101030100",
    重庆: "101040100",
    长沙: "101250100",
    厦门: "101230200",
  });

  // BOSS /wapi/zpgeek/pc/all/filter/conditions.json, verified 2026-10-22.
  // These are work-experience filters. 在校生 narrows the pool; it does not certify an internship.
  const EXPERIENCE_CODES = Object.freeze({
    不限: "", 在校生: "108", 应届生: "102", 经验不限: "101", "1年以内": "103",
    "1-3年": "104", "3-5年": "105", "5-10年": "106", "10年以上": "107",
  });

  const LIMITS = Object.freeze({
    /** Hard ceiling enforced here, whatever the backend asks for. */
    maxPages: 3,
    maxJobs: 45,
    /** BOSS pages one browser may load for collection per local day, across all tasks. */
    maxPagesPerDay: 30,
    keywordMaxChars: 50,
    /** Random pause before each additional page, in milliseconds. */
    pageDelayMinMs: 3_000,
    pageDelayMaxMs: 7_000,
  });

  const SEARCH_ORIGIN = "https://www.zhipin.com";
  /** Must equal optional_host_permissions in manifest.json (checked by tests/manifest.test.cjs). */
  const PERMISSION_ORIGINS = Object.freeze(["https://*.zhipin.com/*"]);
  const TASK_ID_PATTERN = /^[A-Za-z0-9_-]{1,100}$/;

  /** Returns { ok: true, command } or { ok: false, error } — never throws on untrusted input. */
  function validateCommand(value) {
    if (!value || typeof value !== "object") return invalid("采集指令格式不正确");
    const taskId = typeof value.taskId === "string" ? value.taskId : "";
    if (!TASK_ID_PATTERN.test(taskId)) return invalid("taskId 只能包含字母、数字、下划线和短横线，最长 100 个字符");
    const keyword = typeof value.keyword === "string" ? value.keyword.replace(/\s+/g, " ").trim() : "";
    const experience = value.experience === undefined ? "不限" : value.experience;
    if (typeof experience !== "string" || !Object.prototype.hasOwnProperty.call(EXPERIENCE_CODES, experience)) {
      return invalid("experience 必须是 BOSS 网页支持的工作经验选项");
    }
    if ((!keyword && experience === "不限") || keyword.length > LIMITS.keywordMaxChars) {
      return invalid(`请指定岗位关键词或工作经验筛选；关键词不超过 ${LIMITS.keywordMaxChars} 个字符`);
    }
    const city = typeof value.city === "string" ? value.city.trim() : "";
    if (!Object.prototype.hasOwnProperty.call(CITY_CODES, city)) {
      return invalid(`暂不支持城市「${city}」，可选：${Object.keys(CITY_CODES).join("、")}`);
    }
    const maxPages = value.maxPages;
    if (!Number.isInteger(maxPages) || maxPages < 1 || maxPages > LIMITS.maxPages) {
      return invalid(`maxPages 必须是 1 到 ${LIMITS.maxPages} 之间的整数`);
    }
    return { ok: true, command: Object.freeze({ taskId, keyword, city, maxPages, experience }) };
  }

  /** Detail verification accepts only an existing BOSS posting, never an arbitrary URL. */
  function validateDetailCommand(value) {
    const taskId = typeof value?.taskId === "string" ? value.taskId : "";
    if (!TASK_ID_PATTERN.test(taskId)) return invalid("taskId 格式不正确");
    const jobId = value.jobId;
    const sourceJobId = value.sourceJobId;
    if (!Number.isSafeInteger(jobId) || jobId < 1) return invalid("jobId 格式不正确");
    if (typeof sourceJobId !== "string" || !/^[^\s/?#]{1,160}$/.test(sourceJobId)) {
      return invalid("sourceJobId 格式不正确");
    }
    let url;
    try {
      url = new URL(value.sourceUrl);
    } catch {
      return invalid("sourceUrl 格式不正确");
    }
    const match = url.pathname.match(/^\/job_detail\/([^/]+)\.html\/?$/i);
    let urlJobId = "";
    try {
      if (match) urlJobId = decodeURIComponent(match[1]);
    } catch {
      return invalid("sourceUrl 岗位 ID 编码不正确");
    }
    if (!isBossUrl(url.href) || !match || urlJobId !== sourceJobId
        || url.search || url.hash || url.username || url.password) {
      return invalid("只能核实 BOSS 岗位详情页，且岗位 ID 必须一致");
    }
    return { ok: true, command: Object.freeze({
      taskId, mode: "detail", jobId, sourceJobId, sourceUrl: url.href,
    }) };
  }

  function buildSearchUrl(command) {
    const url = new URL("/web/geek/job", SEARCH_ORIGIN);
    url.searchParams.set("query", command.keyword);
    url.searchParams.set("city", CITY_CODES[command.city]);
    if (EXPERIENCE_CODES[command.experience]) url.searchParams.set("experience", EXPERIENCE_CODES[command.experience]);
    return url.toString();
  }

  /** {@code random} returns a number in [0, 1), like Math.random. */
  function pageDelayMs(random) {
    const span = LIMITS.pageDelayMaxMs - LIMITS.pageDelayMinMs;
    return LIMITS.pageDelayMinMs + Math.floor(random() * (span + 1));
  }

  function isBossUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === "https:" && /(^|\.)zhipin\.com$/i.test(url.hostname);
    } catch {
      return false;
    }
  }

  function invalid(error) {
    return { ok: false, error };
  }

  return {
    CITY_CODES,
    EXPERIENCE_CODES,
    LIMITS,
    PERMISSION_ORIGINS,
    buildSearchUrl,
    isBossUrl,
    pageDelayMs,
    validateCommand,
    validateDetailCommand,
  };
});
