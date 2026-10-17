#!/usr/bin/env node
// 检查一段提交范围内每个 commit 的提交信息：commit 只署作者本人，出现以下任一署名字样即失败。
// 用法：node .github/scripts/check-commit-messages.mjs <base> <head>
//   检查 <base>..<head>，即 head 有、base 没有的提交（PR 新增的提交）。
//   本地自查：node .github/scripts/check-commit-messages.mjs origin/main HEAD
import { execFileSync } from "node:child_process";
import { pathToFileURL } from "node:url";

/** 禁止出现在提交信息里的字样。不区分大小写；label 是报错时显示的名字。 */
export const FORBIDDEN = [
  { label: "Co-Authored-By", pattern: /co-authored-by/i },
  // 也拦 Markdown 链接写法：Generated with [Claude Code](https://claude.com/claude-code)
  { label: "Generated with Claude Code", pattern: /generated with \[?claude code\]?/i },
  { label: "claude.ai/code/session", pattern: /claude\.ai\/code\/session/i },
];

/**
 * 找出每个提交命中的字样，每处命中一条：哪个提交、命中什么、在提交信息的第几行。
 * @param {{ sha: string, message: string }[]} commits
 */
export function findViolations(commits) {
  return commits.flatMap(({ sha, message }) => {
    const lines = message.split(/\r?\n/);
    const subject = lines[0] ?? "";
    return FORBIDDEN.flatMap(({ label, pattern }) => {
      const index = lines.findIndex((line) => pattern.test(line));
      return index < 0 ? [] : [{ sha, subject, label, line: index + 1, text: lines[index].trim() }];
    });
  });
}

/** base..head 范围内的提交，按 git log 的顺序（新的在前）。 */
export function readCommits(base, head, cwd = process.cwd()) {
  const output = execFileSync("git", ["log", "--format=%H%x00%B%x1e", `${base}..${head}`], { cwd, encoding: "utf8" });
  return output.split("\x1e").map((record) => record.replace(/^\n/, "")).filter(Boolean).map((record) => {
    const [sha, message] = record.split("\x00");
    return { sha, message: message.replace(/\n+$/, "") };
  });
}

export function formatViolation({ sha, subject, label, line, text }) {
  return `提交 ${sha.slice(0, 7)}「${subject}」的提交信息第 ${line} 行命中「${label}」：${text}`;
}

function main([base, head]) {
  if (!base || !head) {
    console.error("用法：node .github/scripts/check-commit-messages.mjs <base> <head>");
    return 2;
  }
  const commits = readCommits(base, head);
  const violations = findViolations(commits);
  if (violations.length === 0) {
    console.log(`已检查 ${commits.length} 个提交，没有发现署名字样。`);
    return 0;
  }
  for (const violation of violations) {
    const message = formatViolation(violation);
    console.error(message);
    // GitHub Actions 里显示为错误注解；本地运行时只是多一行文字
    if (process.env.GITHUB_ACTIONS) console.log(`::error title=提交信息含署名字样::${message}`);
  }
  console.error(`共 ${violations.length} 处。commit 只署作者本人：请用 git rebase 改写这些提交信息，去掉上述字样后再推送。`);
  return 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
