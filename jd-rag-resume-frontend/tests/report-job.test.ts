import assert from "node:assert/strict";
import test from "node:test";
import { jobContentForReport } from "../app/report-job.ts";
import type { Job } from "../app/lib/api.ts";

const current: Job = {
  id: 1, title: "Java/Python", companyName: "新公司", description: "新岗位",
  requirements: "Python",
};
const snapshot = {
  title: "Java 后端", companyName: "旧公司", location: "深圳",
  description: "Java 服务开发", requirements: "Java, Spring Boot",
};

test("历史报告始终使用分析时保存的 JD，覆盖率不能取最新 requirements", () => {
  assert.deepEqual(jobContentForReport({ jobSnapshot: snapshot, jobInputChanged: true }, current), snapshot);
});

test("旧报告输入已变更时不展示当前岗位作为历史岗位", () => {
  assert.equal(jobContentForReport({ jobSnapshot: null, jobInputChanged: true }, current), null);
});

test("旧报告指纹仍一致时允许展示当前同版 JD", () => {
  assert.equal(jobContentForReport({ jobSnapshot: null, jobInputChanged: false }, current), current);
});

test("有快照时即使当前岗位不存在也可以打开报告", () => {
  assert.deepEqual(jobContentForReport({ jobSnapshot: snapshot, jobInputChanged: false }, null), snapshot);
});
