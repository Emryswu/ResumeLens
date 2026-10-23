import assert from "node:assert/strict";
import test from "node:test";
import { APPLICATION_OPTIONS, applicationWrite } from "../app/job-application.ts";

test("所有手动投递状态都有唯一选项，并允许清除记录", () => {
  assert.equal(APPLICATION_OPTIONS.length, 7);
  assert.equal(new Set(APPLICATION_OPTIONS.map((item) => item.value)).size, 7);
  assert.equal(APPLICATION_OPTIONS.find((item) => item.value === "NOT_INTERESTED")?.label, "不合适");
  assert.deepEqual(applicationWrite("UNTRACKED", "不应提交"), { method: "DELETE" });
});

test("反馈更新不会误做投递动作，备注去掉首尾空格", () => {
  assert.deepEqual(applicationWrite("NOT_INTERESTED", "  距离太远  "), {
    method: "PUT",
    body: JSON.stringify({ status: "NOT_INTERESTED", note: "距离太远" }),
  });
});
