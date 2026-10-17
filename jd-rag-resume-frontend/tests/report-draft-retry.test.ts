import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testsDir = dirname(fileURLToPath(import.meta.url));
const REPORT = "../app/components/MatchReport.tsx";
const rawSource = readFileSync(join(testsDir, REPORT), "utf8");
/**
 * Comments may legitimately mention setDraft(); only real code is checked.
 * CRLF is normalised first: `.` never matches \r, so a line-comment pattern
 * anchored with $ would silently strip nothing on a CRLF checkout.
 */
const source = rawSource
  .replace(/\r\n/g, "\n")
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/\/\/[^\n]*/g, "");

/**
 * The dependency arrays are read out of MatchReport.tsx instead of being copied
 * here, so merging the two retry counters back into one immediately reproduces
 * the draft loss below rather than silently drifting away from this test.
 */
function effectDependencies(marker: string): string[] {
  const arrays = [...source.matchAll(/\}, \[([^\]]*)\]\);/g)].map((match) => match[1]);
  const found = arrays.filter((entry) => entry.includes(marker));
  assert.equal(found.length, 1, `expected exactly one effect depending on ${marker}`);
  return found[0].split(",").map((name) => name.trim()).filter(Boolean);
}

const RESUME_DEPS = effectDependencies("analysis.resumeId");
const JOB_DEPS = effectDependencies("analysis.jobDescriptionId");

type Store = Record<string, unknown>;
type Effect = { name: string; deps: string[]; run: () => void };

/**
 * Mirrors the only React rule this bug depends on: an effect re-runs exactly
 * when one of its dependency values changed since the previous commit.
 */
function createRenderer(store: Store, effects: Effect[]) {
  const previous = new Map<string, unknown[]>();
  return function render() {
    for (const effect of effects) {
      const next = effect.deps.map((name) => store[name]);
      const last = previous.get(effect.name);
      if (last && last.length === next.length && last.every((value, index) => Object.is(value, next[index]))) {
        continue;
      }
      previous.set(effect.name, next);
      effect.run();
    }
  };
}

const SERVER_RESUME_TEXT = "服务端保存的简历原文";

function mountReport({ jobRequestFails }: { jobRequestFails: boolean }) {
  const store: Store = {
    "analysis.resumeId": 1,
    "analysis.jobDescriptionId": 7,
    "job?.id": undefined,
    resumeRetry: 0,
    jobRetry: 0,
    retry: 0,
  };
  const state = { draft: "", original: null as string | null, jobError: "" };
  const loads = { resume: 0, job: 0 };

  const render = createRenderer(store, [
    {
      name: "resume",
      deps: RESUME_DEPS,
      run: () => {
        loads.resume += 1;
        state.original = SERVER_RESUME_TEXT;
        // The real effect calls setDraft(resume.rawText || "") on every run.
        state.draft = SERVER_RESUME_TEXT;
      },
    },
    {
      name: "job",
      deps: JOB_DEPS,
      run: () => {
        loads.job += 1;
        state.jobError = jobRequestFails ? "无法读取岗位原文，岗位可能已删除。" : "";
      },
    },
  ]);

  render();
  return {
    state,
    loads,
    editDraft: (text: string) => {
      state.draft = text;
      render();
    },
    clickJobRetry: () => {
      for (const name of JOB_DEPS) {
        if (/retry/i.test(name)) store[name] = (store[name] as number) + 1;
      }
      render();
    },
  };
}

test("JD 重试不会把未保存的草稿冲回服务端原文", () => {
  const report = mountReport({ jobRequestFails: true });
  assert.equal(report.state.draft, SERVER_RESUME_TEXT);
  assert.equal(report.state.jobError, "无法读取岗位原文，岗位可能已删除。");

  const edited = `${SERVER_RESUME_TEXT}\n补充：2026 年完成一次压测，QPS 从 120 提升到 260。`;
  report.editDraft(edited);
  assert.equal(report.state.draft, edited);

  report.clickJobRetry();

  assert.equal(report.state.draft, edited, "JD 重试后草稿必须原样保留");
  assert.equal(report.loads.job, 2, "JD 重试应当重新拉取岗位");
  assert.equal(report.loads.resume, 1, "JD 重试不应重新拉取简历");
});

test("简历重试与 JD 重试用互相独立的计数器", () => {
  const resumeCounters = RESUME_DEPS.filter((name) => /retry/i.test(name));
  const jobCounters = JOB_DEPS.filter((name) => /retry/i.test(name));

  assert.equal(resumeCounters.length, 1, `简历 effect 应恰好依赖一个重试计数：${RESUME_DEPS.join(", ")}`);
  assert.equal(jobCounters.length, 1, `JD effect 应恰好依赖一个重试计数：${JOB_DEPS.join(", ")}`);
  assert.notEqual(
    resumeCounters[0],
    jobCounters[0],
    "共用同一个重试计数会让 JD 重试重新执行 setDraft，冲掉未保存的草稿",
  );
});

test("两个重试按钮各自只推进自己的计数器", () => {
  const [resumeCounter] = RESUME_DEPS.filter((name) => /retry/i.test(name));
  const [jobCounter] = JOB_DEPS.filter((name) => /retry/i.test(name));
  const setterOf = (counter: string) => `set${counter[0].toUpperCase()}${counter.slice(1)}`;

  const resumeButton = source.match(/retryLabel="重试读取" onRetry=\{\(\) => \{ setLoading\(true\); (set\w+)\(value => value \+ 1\); \}\}/);
  assert.ok(resumeButton, "找不到简历的「重试读取」按钮");
  assert.equal(resumeButton[1], setterOf(resumeCounter));

  const jobButton = source.match(/text=\{jobError\} onRetry=\{\(\) => \{ setJobError\(""\); (set\w+)\(value => value \+ 1\); \}\}/);
  assert.ok(jobButton, "找不到岗位要求的「重试」按钮");
  assert.equal(jobButton[1], setterOf(jobCounter));
});

test("只有简历 effect 与草稿输入框可以写 draft", () => {
  const writes = [...source.matchAll(/setDraft\(/g)].length;
  assert.equal(writes, 2, `setDraft 只应出现在简历 effect 与 textarea 的 onChange 中，实际 ${writes} 处`);
  assert.match(source, /setDraft\(resume\.rawText \|\| ""\)/);
  assert.match(source, /onChange=\{event => setDraft\(event\.target\.value\)\}/);
});
