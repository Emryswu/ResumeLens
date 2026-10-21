const assert = require("node:assert/strict");
const test = require("node:test");

const list = require("../src/boss-list-extractor.js");

const SEARCH_URL = "https://www.zhipin.com/web/geek/job?query=Java&city=101280600";

/** A minimal element: text plus a selector → children table, enough for the extractor's queries. */
function element(text = "", { attributes = {}, children = {} } = {}) {
  return {
    innerText: text,
    textContent: text,
    getAttribute: (name) => attributes[name] ?? null,
    querySelector: (selector) => (children[selector] || [])[0] || null,
    querySelectorAll: (selector) => children[selector] || [],
  };
}

function card({ id, title, company, salary = "15-25K", area = "深圳·南山区", tags = ["1-3年", "本科"], href }) {
  const link = element(title, { attributes: { href: href ?? (id ? `/job_detail/${id}.html?lid=volatile&securityId=x` : "") } });
  return element("", {
    children: {
      "a[href*='/job_detail/']": href === null ? [] : [link],
      ".job-name": [element(title)],
      ".salary": [element(salary)],
      ".company-name": [element(company)],
      ".job-area": [element(area)],
      ".tag-list li": tags.map((tag) => element(tag)),
    },
  });
}

function page(cards, extra = {}, bodyText = "") {
  return {
    ...element("", { children: { ".job-list-box .job-card-wrapper": cards, ...extra } }),
    body: { innerText: bodyText },
  };
}

test("reads list cards into capture-ready jobs keyed by the BOSS job id", () => {
  const documentRef = page([
    card({ id: "abc123", title: "Java 后端实习生", company: "示例科技" }),
    card({ id: "def456", title: "Java 开发工程师", company: "示例智能", tags: ["3-5年", "本科", "Spring"] }),
  ]);
  const { jobs, skippedWithoutId } = list.extractCards(documentRef, SEARCH_URL);

  assert.equal(skippedWithoutId, 0);
  assert.equal(jobs.length, 2);
  const [first, second] = jobs;
  assert.equal(first.sourceJobId, "abc123");
  assert.equal(first.sourceUrl, "https://www.zhipin.com/job_detail/abc123.html");
  assert.equal(first.sourcePlatform, "BOSS");
  assert.equal(first.employmentType, "实习");
  assert.equal(second.employmentType, "全职");
  assert.equal(second.requirements, "3-5年、本科、Spring");
  assert.match(first.description, /^（BOSS 搜索列表摘要，未读取完整职位描述）/);
  assert.match(first.description, /薪资：15-25K/);
});

test("uses the same source id as the single-page detail capture", () => {
  const detail = require("../src/boss-extractor.js");
  const [job] = list.extractCards(page([card({ id: "same%20id", title: "A", company: "B" })]), SEARCH_URL).jobs;
  assert.equal(job.sourceJobId, detail.sourceJobIdFromUrl("https://www.zhipin.com/job_detail/same%20id.html"));
});

test("cards without a stable id are skipped, never fingerprinted", () => {
  const { jobs, skippedWithoutId } = list.extractCards(page([
    card({ title: "无链接", company: "示例", href: null }),
    card({ title: "只有 securityId", company: "示例", href: "/web/geek/job?securityId=volatile" }),
    card({ id: "ok1", title: "有 id", company: "示例" }),
  ]), SEARCH_URL);
  assert.deepEqual(jobs.map((job) => job.sourceJobId), ["ok1"]);
  assert.equal(skippedWithoutId, 2);
});

test("duplicate cards collapse and obfuscated salary digits are dropped", () => {
  const { jobs } = list.extractCards(page([
    card({ id: "dup", title: "A", company: "B", salary: "-K" }),
    card({ id: "dup", title: "A", company: "B" }),
  ]), SEARCH_URL);
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].salary, "");
  assert.doesNotMatch(jobs[0].description, /薪资/);
});

test("inspect tells cards, empty results, blockers and unknown layouts apart", () => {
  assert.deepEqual(list.inspect(page([card({ id: "a", title: "A", company: "B" })]), SEARCH_URL), { kind: "ready", cardCount: 1 });
  assert.deepEqual(list.inspect(page([], { ".job-empty-wrapper": [element("")] }), SEARCH_URL), { kind: "empty" });
  assert.deepEqual(list.inspect(page([], {}, "没有找到相关职位，换个关键词试试"), SEARCH_URL), { kind: "empty" });
  assert.deepEqual(list.inspect(page([], { ".login-dialog": [element("")] }), SEARCH_URL), { kind: "blocked", code: "BOSS_NOT_LOGGED_IN" });
  assert.deepEqual(list.inspect(page([], { ".geetest_panel": [element("")] }), SEARCH_URL), { kind: "blocked", code: "CAPTCHA" });
  assert.deepEqual(list.inspect(page([], {}, "当前 IP 存在异常访问行为"), SEARCH_URL), { kind: "blocked", code: "CAPTCHA" });
  assert.deepEqual(list.inspect(page([], { ".some-new-layout": [element("")] }), SEARCH_URL), { kind: "unknown" });
});

test("a captcha overlay wins even when cards are still rendered underneath", () => {
  const documentRef = page([card({ id: "a", title: "A", company: "B" })], { ".verify-wrap": [element("")] });
  assert.deepEqual(list.inspect(documentRef, SEARCH_URL), { kind: "blocked", code: "CAPTCHA" });
});

test("redirects to login or verification pages are recognized from the URL alone", () => {
  assert.equal(list.blockerFromUrl("https://www.zhipin.com/web/user/?ka=header-login"), "BOSS_NOT_LOGGED_IN");
  assert.equal(list.blockerFromUrl("https://login.zhipin.com/"), "BOSS_NOT_LOGGED_IN");
  assert.equal(list.blockerFromUrl("https://www.zhipin.com/web/user/safe/verify-slider?callbackUrl=x"), "CAPTCHA");
  assert.equal(list.blockerFromUrl("https://www.zhipin.com/web/common/security-check.html"), "CAPTCHA");
  assert.equal(list.blockerFromUrl(SEARCH_URL), null);
});
