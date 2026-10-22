(function exposeBossListExtractor(root, factory) {
  const base = typeof module === "object" && module.exports
    ? require("./boss-extractor.js")
    : root.ResumeLensBossExtractor;
  const extractor = factory(base);
  if (typeof module === "object" && module.exports) module.exports = extractor;
  root.ResumeLensBossListExtractor = extractor;
})(typeof globalThis !== "undefined" ? globalThis : this, function createBossListExtractor(base) {
  "use strict";

  if (!base) throw new Error("boss-extractor.js must be loaded before boss-list-extractor.js");

  /** Several generations of the BOSS search page; the first selector that matches wins. */
  const SELECTORS = Object.freeze({
    card: [
      ".job-list-box .job-card-wrapper",
      ".job-list-container .job-card-box",
      ".rec-job-list .job-card-box",
      ".job-card-wrapper",
      ".job-card-box",
      ".job-card-wrap",
    ],
    link: ["a.job-name[href*='/job_detail/']", "a[href*='/job_detail/']"],
    title: [".job-name", ".job-title .job-name", ".job-title"],
    salary: [".salary", ".job-salary", ".job-title .red"],
    company: [".company-name a", ".company-name", ".boss-name", ".company-info .name"],
    location: [".job-area", ".company-location", ".job-area-wrapper"],
    tags: [".job-info .tag-list li", ".tag-list li", ".job-card-footer .tag-list li", ".job-card-footer li"],
    welfare: [".info-desc", ".job-card-footer .info-desc"],
    empty: [".job-empty-wrapper", ".job-empty-box", ".search-job-result .job-empty", "[class*='job-empty']"],
    login: [".login-dialog", ".boss-login-dialog", ".sign-form", "[class*='login-dialog']", ".login-register-content"],
    captcha: [".geetest_panel", ".geetest_holder", "#captcha", ".verify-wrap", "[class*='verify-slider']", ".page-verify"],
  });

  const EMPTY_TEXT = /没有找到相关职位|暂无(?:相关)?职位/;
  const CAPTCHA_TEXT = /异常访问|安全验证|请完成验证|滑动验证/;
  /** BOSS renders salary digits with a private font; those code points carry no readable meaning. */
  const PRIVATE_USE = /[-]/;

  function blockerFromUrl(href) {
    let url;
    try {
      url = new URL(href);
    } catch {
      return null;
    }
    const path = url.pathname.toLowerCase();
    if (/verify|security-check|captcha/.test(path)) return "CAPTCHA";
    if (path.startsWith("/web/user") || /login|passport/.test(path) || /^login\./i.test(url.hostname)) {
      return "BOSS_NOT_LOGGED_IN";
    }
    return null;
  }

  /**
   * Classifies the page: "blocked" (login or captcha, with a code), "empty" (the search
   * legitimately found nothing), "ready" (cards present) or "unknown" (none of the known
   * markers — treated as a layout change once a wait expires).
   */
  function inspect(documentRef, href) {
    const urlBlocker = blockerFromUrl(href);
    if (urlBlocker) return { kind: "blocked", code: urlBlocker };
    if (anyVisible(documentRef, SELECTORS.captcha) || CAPTCHA_TEXT.test(shortBodyText(documentRef))) {
      return { kind: "blocked", code: "CAPTCHA" };
    }
    const cards = findCards(documentRef);
    if (cards.length > 0) return { kind: "ready", cardCount: cards.length };
    if (anyVisible(documentRef, SELECTORS.login)) return { kind: "blocked", code: "BOSS_NOT_LOGGED_IN" };
    if (anyVisible(documentRef, SELECTORS.empty) || EMPTY_TEXT.test(shortBodyText(documentRef))) {
      return { kind: "empty" };
    }
    return { kind: "unknown" };
  }

  function findCards(documentRef) {
    for (const selector of SELECTORS.card) {
      const cards = queryAll(documentRef, selector);
      if (cards.length > 0) return cards;
    }
    return [];
  }

  /**
   * Reads every card on the page. Cards without a stable BOSS job id are counted but not
   * returned: a list summary is too thin to fingerprint safely, and guessing would create
   * duplicates on the next run.
   */
  function extractCards(documentRef, href) {
    const jobs = [];
    const seen = new Set();
    let skippedWithoutId = 0;
    for (const card of findCards(documentRef)) {
      const job = cardToJob(card, href);
      if (!job) {
        skippedWithoutId += 1;
        continue;
      }
      if (seen.has(job.sourceJobId)) continue;
      seen.add(job.sourceJobId);
      jobs.push(job);
    }
    return { jobs, skippedWithoutId };
  }

  function cardToJob(card, href) {
    const linkNode = first(card, SELECTORS.link);
    const rawLink = attribute(linkNode, "href");
    if (!rawLink) return null;
    return listingJob({
      detailUrl: absoluteUrl(rawLink, href),
      title: text(first(card, SELECTORS.title)) || text(linkNode),
      companyName: text(first(card, SELECTORS.company)),
      location: text(first(card, SELECTORS.location)),
      salary: text(first(card, SELECTORS.salary)),
      tags: queryTexts(card, SELECTORS.tags),
      welfare: text(first(card, SELECTORS.welfare)),
      captureSource: "dom",
    });
  }

  /**
   * Turns the fields of one list entry into a capture-ready job. The DOM cards and the
   * intercepted list API both go through here, so the two sources can only differ in where the
   * fields were read from, never in how they are cleaned, bounded or keyed. Returns null without
   * a stable BOSS job id, a title or a company.
   */
  function listingJob(fields) {
    const detailUrl = typeof fields.detailUrl === "string" ? fields.detailUrl : "";
    const sourceJobId = detailUrl ? base.sourceJobIdFromUrl(detailUrl) : "";
    if (!sourceJobId) return null;

    const title = bounded(fields.title, 160);
    const companyName = bounded(fields.companyName, 120);
    if (!title || !companyName) return null;
    const salaryText = bounded(fields.salary, 40);
    const salary = PRIVATE_USE.test(salaryText) ? "" : salaryText;
    const location = bounded(fields.location, 80);
    const welfare = bounded(fields.welfare, 300);
    const requirements = bounded(cleanTags(fields.tags).join("、"), 2000);

    return {
      title,
      companyName,
      location,
      employmentType: /实习/.test(`${title} ${requirements}`) ? "实习" : /兼职/.test(`${title} ${requirements}`) ? "兼职" : "全职",
      salary,
      description: summaryDescription({ title, companyName, location, salary, requirements, welfare }),
      requirements,
      sourcePlatform: "BOSS",
      sourceUrl: base.canonicalizeUrl(detailUrl),
      sourceJobId: sourceJobId.slice(0, 160),
      captureSource: fields.captureSource === "api" ? "api" : "dom",
    };
  }

  /** States plainly that this is a list summary, so nobody mistakes it for the full posting. */
  function summaryDescription(job) {
    return [
      "（BOSS 搜索列表摘要，未读取完整职位描述）",
      `职位：${job.title}`,
      `公司：${job.companyName}`,
      job.location ? `地点：${job.location}` : "",
      job.salary ? `薪资：${job.salary}` : "",
      job.requirements ? `标签：${job.requirements}` : "",
      job.welfare ? `福利：${job.welfare}` : "",
    ].filter(Boolean).join("\n");
  }

  function queryTexts(root, selectors) {
    return selectors.flatMap((selector) => queryAll(root, selector).map(text));
  }

  /** Short, readable, de-duplicated tags; obfuscated salary glyphs and long blurbs are dropped. */
  function cleanTags(values) {
    const tags = [];
    const seen = new Set();
    for (const raw of Array.isArray(values) ? values : []) {
      const value = bounded(typeof raw === "string" ? raw : "", 41);
      const key = value.toLowerCase();
      if (value && value.length <= 40 && !PRIVATE_USE.test(value) && !seen.has(key)) {
        seen.add(key);
        tags.push(value);
      }
    }
    return tags;
  }

  function anyVisible(documentRef, selectors) {
    return selectors.some((selector) => queryAll(documentRef, selector).some(isVisible));
  }

  function isVisible(node) {
    if (!node) return false;
    // Real elements have layout; test doubles opt out by omitting getClientRects.
    if (typeof node.getClientRects === "function") return node.getClientRects().length > 0;
    return true;
  }

  function shortBodyText(documentRef) {
    const body = documentRef && documentRef.body;
    return body ? String(body.innerText || body.textContent || "").slice(0, 2000) : "";
  }

  function first(root, selectors) {
    for (const selector of selectors) {
      const node = query(root, selector);
      if (node) return node;
    }
    return null;
  }

  function query(root, selector) {
    try {
      return root && root.querySelector ? root.querySelector(selector) : null;
    } catch {
      return null;
    }
  }

  function queryAll(root, selector) {
    try {
      return root && root.querySelectorAll ? Array.from(root.querySelectorAll(selector)) : [];
    } catch {
      return [];
    }
  }

  function text(node) {
    if (!node) return "";
    return base.normalizeText(typeof node.innerText === "string" ? node.innerText : node.textContent);
  }

  function attribute(node, name) {
    if (!node) return "";
    if (typeof node.getAttribute === "function") return String(node.getAttribute(name) || "");
    return String(node[name] || "");
  }

  function bounded(value, maxLength) {
    return base.normalizeText(value).replace(/\n/g, " ").slice(0, maxLength);
  }

  function absoluteUrl(value, href) {
    try {
      return new URL(value, href).toString();
    } catch {
      return "";
    }
  }

  return {
    SELECTORS,
    blockerFromUrl,
    extractCards,
    findCards,
    inspect,
    listingJob,
  };
});
