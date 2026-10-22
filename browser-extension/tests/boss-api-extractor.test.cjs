const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const api = require("../src/boss-api-extractor.js");
const hook = require("../src/boss-api-hook.js");
const list = require("../src/boss-list-extractor.js");
const detail = require("../src/boss-extractor.js");
const { sanitize } = require("../scripts/sanitize-boss-sample.cjs");

const fixtures = path.join(__dirname, "fixtures");
const SAMPLE = fs.readFileSync(path.join(fixtures, "boss-joblist.sample.json"), "utf8");
const EXPECTED = JSON.parse(fs.readFileSync(path.join(fixtures, "boss-joblist.expected.json"), "utf8"));
const LIST_PATH = api.LIST_API_PATHS[0];

function domJob(id, extra = {}) {
  return { title: `DOM ${id}`, companyName: "示例", sourceJobId: id, captureSource: "dom", ...extra };
}

function ready(jobs, skippedWithoutId = 0) {
  return { state: { kind: "ready", cardCount: jobs.length }, jobs, skippedWithoutId };
}

test("the sanitized list API sample parses to the stored snapshot", () => {
  assert.deepEqual(api.parseListResponse(SAMPLE), EXPECTED);
});

test("the committed sample is already sanitized", () => {
  // A raw capture pasted over the sample changes under the sanitizer and fails here.
  assert.deepEqual(sanitize(JSON.parse(SAMPLE)), JSON.parse(SAMPLE));
});

test("the sanitizer drops identifying values and keeps descriptive ones", () => {
  const raw = {
    code: 0,
    zpData: {
      jobList: [
        { encryptJobId: "a1b2c3", bossName: "张三", bossAvatar: "https://img.example/a.png", gps: { longitude: 113.9, latitude: 22.5 }, jobName: "Java 开发", brandName: "某某科技", salaryDesc: "15-25K" },
        { encryptJobId: "d4e5f6", bossName: "李四", brandName: "某某科技" },
      ],
    },
  };
  const [first, second] = sanitize(raw).zpData.jobList;
  assert.equal(first.bossName, "REDACTED");
  assert.equal(first.bossAvatar, "REDACTED");
  assert.deepEqual(first.gps, { longitude: 0, latitude: 0 });
  assert.equal(first.encryptJobId, "sampleJob1");
  assert.equal(second.encryptJobId, "sampleJob2");
  assert.equal(first.brandName, "示例公司1");
  assert.equal(second.brandName, "示例公司1");
  assert.equal(first.jobName, "Java 开发");
  assert.equal(first.salaryDesc, "15-25K");
});

test("API jobs use the same source id and URL as DOM cards and the detail page", () => {
  const body = JSON.stringify({ code: 0, zpData: { jobList: [{ encryptJobId: "abc~123", jobName: "A", brandName: "B" }] } });
  const [job] = api.parseListResponse(body).jobs;
  const url = "https://www.zhipin.com/job_detail/abc~123.html";
  assert.equal(job.sourceJobId, detail.sourceJobIdFromUrl(url));
  assert.equal(job.sourceUrl, url);
});

test("API and DOM jobs have exactly the same fields", () => {
  const [apiJob] = api.parseListResponse(SAMPLE).jobs;
  const domJob = list.listingJob({ detailUrl: "https://www.zhipin.com/job_detail/x.html", title: "A", companyName: "B", captureSource: "dom" });
  assert.deepEqual(Object.keys(apiJob).sort(), Object.keys(domJob).sort());
});

test("ids that could leave the detail path are refused", () => {
  const body = JSON.stringify({ code: 0, zpData: { jobList: [
    { encryptJobId: "../web/user", jobName: "A", brandName: "B" },
    { encryptJobId: "x?y=1", jobName: "A", brandName: "B" },
    { encryptJobId: 42, jobName: "A", brandName: "B" },
  ] } });
  assert.deepEqual(api.parseListResponse(body), { jobs: [], skippedWithoutId: 3 });
});

test("bodies that are not a recognized list response parse to null", () => {
  assert.equal(api.parseListResponse("not json"), null);
  assert.equal(api.parseListResponse(JSON.stringify({ code: 37, message: "访问行为异常" })), null);
  assert.equal(api.parseListResponse(JSON.stringify({ code: 0, zpData: { list: [] } })), null);
  assert.equal(api.parseListResponse(null), null);
});

test("combine prefers API fields, keeps uncovered DOM cards and marks each job's source", () => {
  const dom = ready([domJob("sampleJob1"), domJob("onlyInDom")], 2);
  const combined = api.combine(dom, [{ path: LIST_PATH, body: SAMPLE }]);

  assert.deepEqual(combined.state, dom.state);
  assert.deepEqual(combined.jobs.map((job) => [job.sourceJobId, job.captureSource]), [
    ["sampleJob1", "api"],
    ["sampleJob2", "api"],
    ["sampleJob3", "api"],
    ["onlyInDom", "dom"],
  ]);
  assert.equal(combined.jobs[0].title, "Java 后端开发实习生");
  assert.equal(combined.skippedWithoutId, 1);
});

test("with nothing usable captured, the DOM read is returned unchanged", () => {
  const dom = ready([domJob("a")], 1);
  assert.equal(api.combine(dom, null), dom);
  assert.equal(api.combine(dom, []), dom);
  assert.equal(api.combine(dom, [{ path: LIST_PATH, body: "{\"code\":37}" }]), dom);
  assert.equal(api.combine(dom, [{ path: "/wapi/other.json", body: SAMPLE }]), dom);
  assert.equal(api.combine(dom, [{ path: LIST_PATH, body: "x".repeat(api.MAX_BODY_CHARS + 1) }]), dom);
});

test("the DOM still decides the page state: blocked or empty pages ignore captured data", () => {
  const blocked = { state: { kind: "blocked", code: "CAPTCHA" }, jobs: [], skippedWithoutId: 0 };
  assert.equal(api.combine(blocked, [{ path: LIST_PATH, body: SAMPLE }]), blocked);
  const empty = { state: { kind: "empty" }, jobs: [], skippedWithoutId: 0 };
  assert.equal(api.combine(empty, [{ path: LIST_PATH, body: SAMPLE }]), empty);
});

test("the hook and the extractor agree on the buffer and the captured paths", () => {
  assert.equal(hook.BUFFER_KEY, api.BUFFER_KEY);
  assert.deepEqual(hook.LIST_API_PATHS, api.LIST_API_PATHS);
  assert.equal(hook.MAX_BODY_CHARS, api.MAX_BODY_CHARS);
  assert.equal(hook.MAX_ENTRIES, api.MAX_ENTRIES);
});
