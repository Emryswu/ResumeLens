import assert from "node:assert/strict";
import test from "node:test";
import { assistantInput } from "../app/full-access.ts";
import type { AgentBrowserTask } from "../app/agent-stream.ts";

const task: AgentBrowserTask = { toolCallId: "c", tool: "collect_boss_jobs", taskId: "t1", executor: "browser-extension",
  action: "collectBossJobs", command: {}, expiresAt: 0, timeoutSeconds: 180 };
const idle = { loading: false, error: false, autoRunningTaskId: null };

test("a task the page started on its own leaves the text box open but still holds sending", () => {
  const input = assistantInput({ busy: false, pending: null, pendingBrowser: task }, { ...idle, autoRunningTaskId: "t1" });
  assert.equal(input.draftLocked, false);
  assert.equal(input.sendLocked, true, "a question now would close the browser task, so it waits for the result");
  assert.match(input.placeholder, /自动执行/);
});

test("a task waiting for the click, or one the user started, keeps today's lock", () => {
  for (const autoRunningTaskId of [null, "another-task"]) {
    const input = assistantInput({ busy: false, pending: null, pendingBrowser: task }, { ...idle, autoRunningTaskId });
    assert.equal(input.draftLocked, true);
    assert.equal(input.placeholder, "请先继续或结束上面的浏览器采集任务");
  }
});

test("a confirmation card, a running turn or a sync error always lock the text box", () => {
  const confirmation = { toolCallId: "r", tool: "relax_job_search_condition", arguments: {} };
  assert.equal(assistantInput({ busy: false, pending: confirmation, pendingBrowser: null }, idle).draftLocked, true);
  assert.equal(assistantInput({ busy: true, pending: null, pendingBrowser: task }, { ...idle, autoRunningTaskId: "t1" }).draftLocked, true);
  assert.equal(assistantInput({ busy: false, pending: null, pendingBrowser: task }, { ...idle, error: true, autoRunningTaskId: "t1" }).draftLocked, true);
  assert.deepEqual(assistantInput({ busy: false, pending: null, pendingBrowser: null }, idle),
    { sendLocked: false, draftLocked: false, placeholder: "问点什么，例如：我的简历最适合哪个职位？（Enter 发送，Shift+Enter 换行）" });
});
