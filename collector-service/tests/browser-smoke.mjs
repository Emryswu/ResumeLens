import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { chromium } from "patchright";
import { createPatchrightBrowser } from "../src/patchright-browser.mjs";

test("real Patchright uses one tab per conversation, renders the banner and reuses profile cookies after restart", async () => {
  const profileDir = await mkdtemp(join(tmpdir(), "resumelens-patchright-smoke-"));
  const headed = process.env.COLLECTOR_SMOKE_HEADED === "1";
  const options = { chromium, profileDir, launchOptions: { channel: headed ? "chrome" : undefined, headless: !headed } };
  let browser = await createPatchrightBrowser(options);
  try {
    await browser.context.route("https://www.zhipin.com/**", (route) => route.fulfill({ contentType: "text/html", body: `<!doctype html><meta charset="utf-8"><div class="job-card-wrapper"><a class="job-name" href="/job_detail/example.html">Java 后端</a><div class="company-name">测试公司</div><div class="job-area">深圳·南山</div></div>` }));
    const first = await browser.openTab("https://www.zhipin.com/web/geek/job?query=Java", "42");
    const read = await browser.readPage(first, "read", { waitMs: 2000 });
    assert.equal(read.jobs.length, 1);
    assert.equal(read.jobs[0].sourceJobId, "example");
    await browser.showProgress(first, { status: "completed", pagesRead: 1, phase: "read", jobs: read.jobs,
      pageLog: [{ page: 1, api: 0, dom: 1 }], finishReason: "NO_MORE_RESULTS" });
    assert.match(await browser.context.pages()[0].locator("#resumelens-collect-progress section").innerText(), /可能未登录 BOSS/);
    assert.equal(await browser.openTab("https://www.zhipin.com/job_detail/example.html", "42"), first);
    assert.equal(browser.context.pages().length, 1);
    await browser.context.pages()[0].close();
    assert.notEqual(await browser.openTab("https://www.zhipin.com/web/geek/job", "42"), first);
    await browser.openTab("https://www.zhipin.com/web/geek/job", "43");
    assert.equal(browser.context.pages().length, 2);
    await browser.context.addCookies([{ name: "login_fixture", value: "persisted", domain: "www.zhipin.com", path: "/", expires: Date.now() / 1000 + 3600 }]);
    await browser.close();
    browser = await createPatchrightBrowser(options);
    assert.equal((await browser.context.cookies("https://www.zhipin.com")).find((cookie) => cookie.name === "login_fixture")?.value, "persisted");
  } finally {
    await browser.close();
    await rm(profileDir, { recursive: true, force: true });
  }
});
