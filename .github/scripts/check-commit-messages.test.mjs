// 运行：node --test .github/scripts/check-commit-messages.test.mjs
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { findViolations } from "./check-commit-messages.mjs";

const script = join(dirname(fileURLToPath(import.meta.url)), "check-commit-messages.mjs");
const labels = (message) => findViolations([{ sha: "a".repeat(40), message }]).map((violation) => violation.label);

test("正常的提交信息能通过", () => {
  assert.deepEqual(labels("feat(assistant): 处理过程收成一行摘要\n\n- 点开在侧边面板看完整时间线"), []);
  // 只是提到 Claude 或 co-author 这个词，不算署名
  assert.deepEqual(labels("docs: 说明如何用 Claude Code 跑测试\n\nco-author 字段的含义见 git 文档"), []);
});

test("Co-Authored-By 尾注会被拦下，不分大小写", () => {
  assert.deepEqual(labels("fix: 修复\n\nCo-Authored-By: Claude <noreply@anthropic.com>"), ["Co-Authored-By"]);
  assert.deepEqual(labels("fix: 修复\n\nco-authored-by: someone <a@b.c>"), ["Co-Authored-By"]);
});

test("Generated with Claude Code 会被拦下，包括 Markdown 链接写法", () => {
  assert.deepEqual(labels("fix: 修复\n\nGenerated with Claude Code"), ["Generated with Claude Code"]);
  assert.deepEqual(labels("fix: 修复\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)"), ["Generated with Claude Code"]);
});

test("会话链接 claude.ai/code/session 会被拦下", () => {
  assert.deepEqual(labels("fix: 修复\n\nhttps://claude.ai/code/session_01AbCdEf"), ["claude.ai/code/session"]);
});

test("报告写明是哪个提交、命中哪个字样、在第几行", () => {
  const [violation, ...rest] = findViolations([
    { sha: "1".repeat(40), message: "chore: 正常" },
    { sha: "2".repeat(40), message: "fix: 修复\n\nCo-Authored-By: Claude <noreply@anthropic.com>" },
  ]);
  assert.equal(rest.length, 0);
  assert.deepEqual(violation, {
    sha: "2".repeat(40), subject: "fix: 修复", label: "Co-Authored-By", line: 3,
    text: "Co-Authored-By: Claude <noreply@anthropic.com>",
  });
  // 一个提交同时命中多种字样时，每种都报
  assert.deepEqual(
    labels("fix: 修复\n\nGenerated with Claude Code\nhttps://claude.ai/code/session_x\nCo-Authored-By: Claude"),
    ["Co-Authored-By", "Generated with Claude Code", "claude.ai/code/session"],
  );
});

/** 在临时仓库里真实提交，再按 CI 的方式运行脚本。 */
function withRepo(run) {
  const dir = mkdtempSync(join(tmpdir(), "commit-check-"));
  const git = (...args) => execFileSync("git", ["-c", "user.name=Tester", "-c", "user.email=t@example.com", ...args], { cwd: dir, encoding: "utf8" }).trim();
  const commit = (message) => { git("commit", "--allow-empty", "-q", "-m", message); return git("rev-parse", "HEAD"); };
  const check = (base, head) => spawnSync(process.execPath, [script, base, head], { cwd: dir, encoding: "utf8", env: { ...process.env, GITHUB_ACTIONS: "" } });
  try {
    git("init", "-q");
    run({ commit, check });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("脚本只检查 base..head 新增的提交：命中就以非零退出并报出提交和字样", () => {
  withRepo(({ commit, check }) => {
    // base 之前的历史即使带署名也不算本次 PR 的提交
    const base = commit("chore: 旧提交\n\nCo-Authored-By: Someone <s@example.com>");
    commit("feat: 正常提交");
    const bad = commit("fix: 修复\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)");

    const failed = check(base, "HEAD");
    assert.equal(failed.status, 1);
    assert.match(failed.stderr, new RegExp(`提交 ${bad.slice(0, 7)}「fix: 修复」的提交信息第 3 行命中「Generated with Claude Code」`));
    assert.doesNotMatch(failed.stderr, /Co-Authored-By/);

    const passed = check(base, "HEAD~1");
    assert.equal(passed.status, 0, passed.stderr);
    assert.match(passed.stdout, /已检查 1 个提交/);
  });
});

test("缺少参数时提示用法，不当作通过", () => {
  const result = spawnSync(process.execPath, [script], { encoding: "utf8" });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /用法/);
});
