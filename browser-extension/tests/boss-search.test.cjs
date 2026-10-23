const assert = require("node:assert/strict");
const test = require("node:test");

const search = require("../src/boss-search.js");


const valid = { taskId: "call_1", keyword: " Java  后端 实习 ", city: "深圳", maxPages: 2 };

test("a valid command is normalized and frozen", () => {
  const result = search.validateCommand(valid);
  assert.equal(result.ok, true);
  assert.deepEqual({ ...result.command }, { taskId: "call_1", keyword: "Java 后端 实习", city: "深圳", maxPages: 2, experience: "不限" });
  assert.ok(Object.isFrozen(result.command));
});

test("the search URL is built here, on BOSS, from the city table only", () => {
  const url = new URL(search.buildSearchUrl(search.validateCommand(valid).command));
  assert.equal(url.origin, "https://www.zhipin.com");
  assert.equal(url.pathname, "/web/geek/job");
  assert.equal(url.searchParams.get("query"), "Java 后端 实习");
  assert.equal(url.searchParams.get("city"), "101280600");
});

test("student and graduate filters use the website experience condition, independently of keywords", () => {
  for (const [experience, code] of [["在校生", "108"], ["应届生", "102"]]) {
    const result = search.validateCommand({ ...valid, keyword: "Java 后端", experience });
    assert.equal(result.ok, true);
    assert.equal(result.command.experience, experience);
    const url = new URL(search.buildSearchUrl(result.command));
    assert.equal(url.searchParams.get("city"), "101280600");
    assert.equal(url.searchParams.get("experience"), code);
    assert.equal(url.searchParams.get("query"), "Java 后端");
  }
  assert.equal(search.validateCommand({ ...valid, experience: "实习" }).ok, false);
  const withoutKeyword = search.validateCommand({ ...valid, keyword: "", experience: "在校生" });
  assert.equal(withoutKeyword.ok, true);
  assert.equal(new URL(search.buildSearchUrl(withoutKeyword.command)).searchParams.get("experience"), "108");
});

test("commands cannot smuggle a URL, an unknown city or extra pages", () => {
  const cases = [
    { ...valid, city: "https://evil.example/" },
    { ...valid, city: "toString" },
    { ...valid, maxPages: search.LIMITS.maxPages + 1 },
    { ...valid, maxPages: 0 },
    { ...valid, maxPages: 1.5 },
    { ...valid, maxPages: "2" },
    { ...valid, keyword: "" },
    { ...valid, keyword: "x".repeat(search.LIMITS.keywordMaxChars + 1) },
    { ...valid, taskId: "../etc" },
    { ...valid, taskId: "" },
    null,
    "collect",
  ];
  for (const command of cases) {
    assert.equal(search.validateCommand(command).ok, false, JSON.stringify(command));
  }
});

test("page delays stay inside the configured random window", () => {
  assert.equal(search.pageDelayMs(() => 0), search.LIMITS.pageDelayMinMs);
  assert.equal(search.pageDelayMs(() => 0.999999), search.LIMITS.pageDelayMaxMs);
  for (let index = 0; index < 200; index += 1) {
    const delay = search.pageDelayMs(Math.random);
    assert.ok(delay >= search.LIMITS.pageDelayMinMs && delay <= search.LIMITS.pageDelayMaxMs, String(delay));
  }
});

test("only https zhipin.com hosts count as BOSS", () => {
  assert.equal(search.isBossUrl("https://www.zhipin.com/web/geek/job"), true);
  assert.equal(search.isBossUrl("https://zhipin.com/"), true);
  assert.equal(search.isBossUrl("http://www.zhipin.com/"), false);
  assert.equal(search.isBossUrl("https://zhipin.com.evil.example/"), false);
  assert.equal(search.isBossUrl("not a url"), false);
});


test("detail command only accepts a matching BOSS job detail URL", () => {
  const command = { taskId: "detail_1", mode: "detail", jobId: 1,
    sourceJobId: "abc123", sourceUrl: "https://www.zhipin.com/job_detail/abc123.html" };
  assert.equal(search.validateDetailCommand(command).ok, true);
  assert.equal(search.validateDetailCommand({ ...command, sourceJobId: "other" }).ok, false);
  assert.equal(search.validateDetailCommand({ ...command,
    sourceUrl: "https://evil.example/job_detail/abc123.html" }).ok, false);
  assert.equal(search.validateDetailCommand({ ...command,
    sourceUrl: "https://www.zhipin.com/job_detail/abc123.html?next=1" }).ok, false);
});
