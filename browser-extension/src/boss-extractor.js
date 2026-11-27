(function exposeBossExtractor(root, factory) {
  const extractor = factory();
  if (typeof module === "object" && module.exports) module.exports = extractor;
  root.ResumeLensBossExtractor = extractor;
})(typeof globalThis !== "undefined" ? globalThis : this, function createBossExtractor() {
  "use strict";

  const SELECTORS = Object.freeze({
    title: [
      ".job-detail-header h1",
      ".job-detail-box h1",
      ".job-info .name",
      ".job-primary .job-name",
      "h1",
    ],
    company: [
      ".company-info-box .company-name",
      ".company-info-box h3",
      ".sider-company .company-info a",
      ".job-detail-company .name",
      ".company-card .company-name",
      ".company-info .name",
      ".company-name",
    ],
    location: [
      ".job-address .location-address",
      ".job-location .location-address",
      ".job-info .text-city",
      ".job-primary .job-area",
      ".job-address",
    ],
    description: [
      ".job-detail-section .job-sec-text",
      ".job-detail .job-sec-text",
      ".job-detail-box .job-sec-text",
      ".job-detail-content",
      ".job-description",
      "[class*='job-detail'] [class*='description']",
    ],
    requirements: [
      ".job-keyword-list li",
      ".job-tags span",
      ".job-tags li",
      ".tag-list li",
      ".job-detail-tags span",
      ".job-detail-tags li",
    ],
    selectedJobLink: [
      ".job-card-wrapper.active a[href*='/job_detail/']",
      ".job-list-box .job-card-wrapper.active a[href*='/job_detail/']",
      ".job-card-box.active a[href*='/job_detail/']",
      ".job-card-wrapper.selected a[href*='/job_detail/']",
      ".job-card-wrapper.cur a[href*='/job_detail/']",
      "[aria-selected='true'] a[href*='/job_detail/']",
      "a[aria-current='true'][href*='/job_detail/']",
      "link[rel='canonical']",
    ],
  });

  function extract(documentRef, locationHref) {
    const rawSourceUrl = selectedJobUrl(documentRef, locationHref);
    const title = bounded(
      firstText(documentRef, SELECTORS.title) || titleFromDocument(documentRef),
      160,
    );
    const companyName = bounded(firstText(documentRef, SELECTORS.company), 120);
    const location = bounded(firstText(documentRef, SELECTORS.location), 80);
    const description = bounded(bestText(documentRef, SELECTORS.description), 20000);
    const tags = uniqueTexts(documentRef, SELECTORS.requirements);
    const requirements = bounded(tags.join("、"), 20000);
    const sourceUrl = canonicalizeUrl(rawSourceUrl, locationHref);
    const extractedSourceJobId = sourceJobIdFromUrl(rawSourceUrl);
    const sourceJobId = extractedSourceJobId
      || `fallback-v2-${stableHash([
        sourceUrl,
        title,
        companyName,
        location,
        description,
        requirements,
      ].map(normalizeText).join("\n"))}`;
    const warnings = [];

    if (!title) warnings.push("没有自动识别到职位名称，请手动补充");
    if (!companyName) warnings.push("没有自动识别到公司名称，请手动补充");
    if (description.length < 20) warnings.push("JD 正文过短，请确认已打开职位详情页");
    if (!extractedSourceJobId) warnings.push("没有读到稳定的 BOSS 岗位 ID，本次会用完整岗位内容生成指纹；岗位内容变化时会作为新岗位保存");

    return {
      job: {
        title,
        companyName,
        location,
        employmentType: inferEmploymentType(tags, title),
        description,
        requirements,
        sourcePlatform: "BOSS",
        sourceUrl,
        sourceJobId,
      },
      warnings,
    };
  }

  function selectedJobUrl(documentRef, locationHref) {
    for (const selector of SELECTORS.selectedJobLink) {
      const node = safeQuery(documentRef, selector);
      const href = attribute(node, "href");
      if (href && /\/job_detail\//i.test(href)) return absoluteUrl(href, locationHref);
    }
    return locationHref;
  }

  function sourceJobIdFromUrl(value) {
    try {
      const url = new URL(value);
      const match = url.pathname.match(/\/job_detail\/([^/]+?)(?:\.html)?\/?$/i);
      if (match && match[1]) return decodeURIComponent(match[1]);
      for (const key of ["encryptJobId", "jobId"]) {
        const candidate = url.searchParams.get(key);
        if (candidate) return candidate.slice(0, 160);
      }
    } catch {
      return "";
    }
    return "";
  }

  function canonicalizeUrl(value, base) {
    try {
      const url = new URL(value, base);
      url.hash = "";
      url.search = "";
      return url.toString();
    } catch {
      return String(value || base || "").slice(0, 2048);
    }
  }

  function firstText(documentRef, selectors) {
    for (const selector of selectors) {
      const value = nodeText(safeQuery(documentRef, selector));
      if (value) return value;
    }
    return "";
  }

  function bestText(documentRef, selectors) {
    const values = [];
    for (const selector of selectors) {
      for (const node of safeQueryAll(documentRef, selector)) {
        const value = nodeText(node);
        if (value) values.push(value);
      }
    }
    values.sort((left, right) => right.length - left.length);
    return values[0] || metaDescription(documentRef);
  }

  function uniqueTexts(documentRef, selectors) {
    const values = [];
    const seen = new Set();
    for (const selector of selectors) {
      for (const node of safeQueryAll(documentRef, selector)) {
        const value = nodeText(node);
        const key = value.toLowerCase();
        if (value && value.length <= 80 && !seen.has(key)) {
          seen.add(key);
          values.push(value);
        }
      }
    }
    return values;
  }

  function inferEmploymentType(tags, title) {
    const text = `${tags.join(" ")} ${title}`;
    if (/实习/.test(text)) return "实习";
    if (/兼职/.test(text)) return "兼职";
    return "全职";
  }

  function titleFromDocument(documentRef) {
    const ogTitle = attribute(safeQuery(documentRef, "meta[property='og:title']"), "content");
    const raw = ogTitle || String(documentRef && documentRef.title || "");
    return normalizeText(raw)
      .replace(/[-_|].*BOSS直聘.*$/i, "")
      .replace(/招聘\s*$/i, "")
      .trim();
  }

  function metaDescription(documentRef) {
    return normalizeText(
      attribute(safeQuery(documentRef, "meta[name='description']"), "content")
      || attribute(safeQuery(documentRef, "meta[property='og:description']"), "content"),
    );
  }

  function nodeText(node) {
    if (!node) return "";
    return normalizeText(typeof node.innerText === "string" ? node.innerText : node.textContent);
  }

  function normalizeText(value) {
    return String(value || "")
      .replace(/[\u200b-\u200d\ufeff]/g, "")
      .replace(/\r/g, "")
      .split("\n")
      .map((line) => line.replace(/[\t ]+/g, " ").trim())
      .filter(Boolean)
      .join("\n")
      .trim();
  }

  function bounded(value, maxLength) {
    return normalizeText(value).slice(0, maxLength);
  }

  function stableHash(value) {
    let first = 0x811c9dc5;
    let second = 0x9e3779b9;
    for (let index = 0; index < value.length; index += 1) {
      const code = value.charCodeAt(index);
      first = Math.imul(first ^ code, 0x01000193);
      second = Math.imul(second ^ code, 0x85ebca6b);
    }
    return `${(first >>> 0).toString(16).padStart(8, "0")}${(second >>> 0).toString(16).padStart(8, "0")}`;
  }

  function safeQuery(documentRef, selector) {
    try {
      return documentRef && documentRef.querySelector ? documentRef.querySelector(selector) : null;
    } catch {
      return null;
    }
  }

  function safeQueryAll(documentRef, selector) {
    try {
      return documentRef && documentRef.querySelectorAll
        ? Array.from(documentRef.querySelectorAll(selector))
        : [];
    } catch {
      return [];
    }
  }

  function attribute(node, name) {
    if (!node) return "";
    if (typeof node.getAttribute === "function") return String(node.getAttribute(name) || "");
    return String(node[name] || "");
  }

  function absoluteUrl(value, base) {
    try {
      return new URL(value, base).toString();
    } catch {
      return value;
    }
  }

  return {
    SELECTORS,
    attribute,
    canonicalizeUrl,
    extract,
    inferEmploymentType,
    nodeText,
    normalizeText,
    safeQuery,
    safeQueryAll,
    sourceJobIdFromUrl,
    stableHash,
  };
});
