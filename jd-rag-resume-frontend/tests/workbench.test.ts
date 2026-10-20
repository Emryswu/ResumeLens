import assert from "node:assert/strict";
import test from "node:test";
import { EMPTY_PASTE_JOB, filterJobs, pasteJobPayload, workbenchAction } from "../app/workbench.ts";

const filled = { ...EMPTY_PASTE_JOB, title: " Java 后端 ", companyName: "星河云", description: "负责招聘服务" };

test("粘贴职位：职位名称、公司名称、职位描述必填，空的可选字段不发送", () => {
  const missing = pasteJobPayload(EMPTY_PASTE_JOB);
  assert.equal(missing.ok, false);
  assert.deepEqual(!missing.ok && missing.problems, ["请填写职位名称", "请填写公司名称", "请填写职位描述"]);

  const result = pasteJobPayload({ ...filled, location: "  ", requirements: "" });
  assert.deepEqual(result, { ok: true, payload: { title: "Java 后端", companyName: "星河云", employmentType: "全职", description: "负责招聘服务" } });

  const withExtras = pasteJobPayload({ ...filled, location: "深圳", requirements: "熟悉 Spring" });
  assert.ok(withExtras.ok && withExtras.payload.location === "深圳" && withExtras.payload.requirements === "熟悉 Spring");
});

test("粘贴职位：长度上限与后端 JobDescriptionRequest 一致", () => {
  const result = pasteJobPayload({ ...filled, title: "职".repeat(161), companyName: "公".repeat(121) });
  assert.deepEqual(!result.ok && result.problems, ["职位名称最多 160 个字", "公司名称最多 120 个字"]);
  assert.equal(pasteJobPayload({ ...filled, title: "职".repeat(160) }).ok, true);
});

test("职位搜索按职位、公司、地点多词同时命中", () => {
  const jobs = [
    { title: "Java 后端开发", companyName: "星河云", location: "深圳" },
    { title: "AI 应用开发", companyName: "青禾智能", location: "深圳" },
    { title: "后端实习", companyName: "鹿鸣网络" },
  ];
  assert.equal(filterJobs(jobs, "  ").length, 3);
  assert.deepEqual(filterJobs(jobs, "深圳 java").map((job) => job.companyName), ["星河云"]);
  assert.deepEqual(filterJobs(jobs, "后端").map((job) => job.companyName), ["星河云", "鹿鸣网络"]);
});

test("主按钮：没选简历先拦，已有完整报告改为查看报告，分析中改为查看进度", () => {
  const base = { resumeId: 3 as const, source: "library" as const, jobId: 16 as const, paste: EMPTY_PASTE_JOB };
  assert.equal(workbenchAction({ ...base, resumeId: "" }).kind, "blocked");
  assert.equal(workbenchAction({ ...base, jobId: "" }).kind, "blocked");
  assert.deepEqual(workbenchAction(base), { kind: "analyze" });

  const done = { id: 24, status: "COMPLETED" as const, resumeId: 3, jobDescriptionId: 16 };
  assert.deepEqual(workbenchAction({ ...base, latest: done }), { kind: "view-report", analysisId: 24 });
  assert.deepEqual(workbenchAction({ ...base, latest: { ...done, status: "PENDING" } }), { kind: "view-progress", analysisId: 24 });
  assert.deepEqual(workbenchAction({ ...base, latest: { ...done, status: "FAILED" } }), { kind: "analyze" });
  // A report of another pair never turns into "查看报告" for this one.
  assert.deepEqual(workbenchAction({ ...base, latest: { ...done, jobDescriptionId: 15 } }), { kind: "analyze" });
});

test("主按钮：粘贴模式只看表单，不复用职位库里选中岗位的旧报告", () => {
  const done = { id: 24, status: "COMPLETED" as const, resumeId: 3, jobDescriptionId: 16 };
  const blocked = workbenchAction({ resumeId: 3, source: "paste", jobId: 16, paste: EMPTY_PASTE_JOB, latest: done });
  assert.deepEqual(blocked, { kind: "blocked", reason: "请填写职位名称" });
  assert.deepEqual(workbenchAction({ resumeId: 3, source: "paste", jobId: 16, paste: filled, latest: done }), { kind: "analyze" });
});
