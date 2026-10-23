/**
 * Turns one assistant turn into the rows of its step bar. Every row comes from a server event or from the
 * pending confirmation; durations and result sizes are only ever the numbers the server reported.
 */
import {
  BriefcaseBusiness, FileSearch, FileStack, Gauge, History, ListOrdered, ScanSearch, Search, Wrench, type LucideIcon,
} from "lucide-react";
import type { AgentTurn } from "./agent-stream";

export type StepStatus =
  /** The server said the tool is about to run and no result has arrived yet. */
  | "running"
  | "done"
  | "failed"
  /** The user refused; the tool never ran. */
  | "rejected"
  /** Waiting for the user to approve or refuse. */
  | "awaiting"
  /** The approval was sent and the request is still open; no result yet. */
  | "approving"
  /** The approval was sent but the request ended without a result: the server may or may not have run it. */
  | "unknown"
  /** Asked for confirmation, but no approval was sent; the tool never ran. */
  | "unconfirmed"
  /** Started, but the request ended before a result arrived. Not counted as done. */
  | "unfinished"
  /** Handed to the browser extension; its report has not been sent yet. */
  | "browser"
  /** The page sent the browser's report and the request is still open; no result yet. */
  | "reporting";

export type TimelineStep = {
  id: string;
  tool: string;
  label: string;
  /** Raw JSON arguments as the model sent them. */
  arguments: string;
  status: StepStatus;
  /** Only for steps the server reported as finished. */
  latencyMs?: number;
  resultChars?: number;
  resultPreview?: string;
  errorCode?: string | null;
  facts?: Record<string, unknown> | null;
};

export type ToolMeta = { label: string; icon: LucideIcon };

/**
 * Every tool the backend registers (classes implementing AgentTool), with its Chinese name and icon.
 * tests/agent-tools.test.ts reads the backend sources and fails when a tool is missing here.
 */
export const AGENT_TOOLS: Record<string, ToolMeta> = {
  list_resumes: { label: "读取简历列表", icon: FileStack },
  get_resume: { label: "读取简历正文", icon: FileSearch },
  search_jobs: { label: "搜索职位库", icon: Search },
  get_job: { label: "读取职位详情", icon: BriefcaseBusiness },
  list_job_applications: { label: "读取投递进度", icon: History },
  rank_jobs_for_resume: { label: "按语义相似度排序职位", icon: ListOrdered },
  search_resume_evidence: { label: "检索简历证据（RAG）", icon: FileSearch },
  get_latest_analysis: { label: "读取最近一次分析", icon: History },
  start_analysis: { label: "发起完整分析", icon: Gauge },
  collect_boss_jobs: { label: "去 BOSS 采集岗位", icon: ScanSearch },
  verify_boss_job: { label: "核实完整职位 JD", icon: FileSearch },
};

/** A tool this page does not know yet still shows, under its raw name and a generic icon. */
export function toolMeta(tool: string): ToolMeta {
  return AGENT_TOOLS[tool] ?? { label: tool, icon: Wrench };
}

export const STATUS_LABELS: Record<StepStatus, string> = {
  running: "进行中",
  done: "完成",
  failed: "失败",
  rejected: "你已拒绝，未执行",
  awaiting: "等待你确认",
  approving: "已同意，等待执行",
  unknown: "已同意，结果未知",
  unconfirmed: "未确认，未执行",
  unfinished: "未完成，没有返回结果",
  browser: "插件执行中",
  reporting: "正在回传采集结果",
};

export function toolLabel(tool: string): string {
  return toolMeta(tool).label;
}

/** Statuses that must stay visible in the one-line summary, in the order they are listed. */
const ISSUE_LABELS: Partial<Record<StepStatus, string>> = {
  failed: "失败",
  rejected: "已拒绝",
  unknown: "结果未知",
  unfinished: "未完成",
  unconfirmed: "未执行",
};

export type ProcessSummary = {
  /** Tools in order of first use, each listed once. */
  tools: string[];
  /** Their Chinese names joined with 、. */
  text: string;
  /** The tool whose icon stands for the turn, or "multiple" (shown as a clock) when more than one tool ran. */
  icon: string | "multiple";
  /** Total reported tool time; only given for several tools, null when no finished call reported one. */
  totalMs: number | null;
  /** Steps that did not simply finish, e.g. "1 步失败". Never folded away by the summary. */
  issues: { status: StepStatus; label: string }[];
  steps: number;
};

/** The one line a finished turn's process collapses to. Null when the turn used no tools. */
export function processSummary(rows: TimelineStep[]): ProcessSummary | null {
  if (rows.length === 0) return null;
  const tools = [...new Set(rows.map((row) => row.tool))];
  const multiple = tools.length > 1;
  const issues = Object.entries(ISSUE_LABELS).flatMap(([status, label]) => {
    const count = rows.filter((row) => row.status === status).length;
    return count > 0 ? [{ status: status as StepStatus, label: `${count} 步${label}` }] : [];
  });
  return {
    tools,
    text: tools.map(toolLabel).join("、"),
    icon: multiple ? "multiple" : tools[0],
    totalMs: multiple ? toolTimeMs(rows) : null,
    issues,
    steps: rows.length,
  };
}


export function timelineSteps(turn: AgentTurn, pendingId: string | null): TimelineStep[] {
  const finished = new Set(turn.steps.map((step) => step.toolCallId));
  const rows: TimelineStep[] = turn.steps.map((step) => ({
    id: step.toolCallId,
    tool: step.tool,
    label: toolLabel(step.tool),
    arguments: step.arguments,
    status: step.ok ? "done" : step.errorCode === "USER_REJECTED" ? "rejected" : "failed",
    // A refused call never ran, so it has no duration to show.
    latencyMs: step.errorCode === "USER_REJECTED" ? undefined : step.latencyMs,
    resultChars: step.resultChars,
    resultPreview: step.resultPreview,
    errorCode: step.errorCode,
    facts: step.facts,
  }));
  for (const start of turn.started) {
    if (finished.has(start.toolCallId)) continue;
    rows.push({
      id: start.toolCallId,
      tool: start.tool,
      label: toolLabel(start.tool),
      arguments: start.arguments,
      status: turn.unfinished.includes(start.toolCallId) ? "unfinished" : "running",
    });
  }
  const task = turn.browserTask;
  if (task && !finished.has(task.toolCallId) && !turn.started.some((start) => start.toolCallId === task.toolCallId)) {
    rows.push({
      id: task.toolCallId,
      tool: task.tool,
      label: toolLabel(task.tool),
      arguments: JSON.stringify(task.command),
      status: turn.browserOutcome ? (turn.unfinished.includes(task.toolCallId) ? "unfinished" : "reporting") : "browser",
    });
  }
  const confirmation = turn.confirmation;
  if (confirmation && !finished.has(confirmation.toolCallId)
      && !turn.started.some((start) => start.toolCallId === confirmation.toolCallId)) {
    rows.push({
      id: confirmation.toolCallId,
      tool: confirmation.tool,
      label: toolLabel(confirmation.tool),
      arguments: JSON.stringify(confirmation.arguments),
      status: confirmationStatus(turn, confirmation.toolCallId, pendingId),
    });
  }
  return rows;
}

function confirmationStatus(turn: AgentTurn, id: string, pendingId: string | null): StepStatus {
  if (turn.approvedCallId === id) return pendingId === id ? "approving" : "unknown";
  return pendingId === id ? "awaiting" : "unconfirmed";
}

/** Sum of the latencies the server reported for finished tool calls; null when there are none. */
export function toolTimeMs(rows: TimelineStep[]): number | null {
  const reported = rows.filter((row) => row.latencyMs != null && (row.status === "done" || row.status === "failed"));
  return reported.length === 0 ? null : reported.reduce((sum, row) => sum + (row.latencyMs ?? 0), 0);
}

export function formatDuration(ms: number): string {
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;
}

const ARGUMENT_TEXT: Record<string, (value: unknown) => string> = {
  resumeId: (value) => `简历 #${String(value)}`,
  jobId: (value) => `职位 #${String(value)}`,
  limit: (value) => `前 ${String(value)} 个`,
  keyword: (value) => `关键词「${String(value)}」`,
  city: (value) => String(value),
  maxPages: (value) => `最多 ${String(value)} 页`,
  page: (value) => `第 ${Number(value) + 1} 页`,
};

/** A short Chinese reading of the arguments; unknown keys are kept as key=value. */
export function describeArguments(raw: string): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return raw;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return raw;
  const parts = Object.entries(parsed as Record<string, unknown>)
    .filter(([, value]) => value != null && value !== "")
    .map(([key, value]) => ARGUMENT_TEXT[key]?.(value) ?? `${key}=${typeof value === "object" ? JSON.stringify(value) : String(value)}`);
  return parts.length > 0 ? parts.join(" · ") : "无参数";
}

const count = (value: unknown) => (typeof value === "number" && Number.isFinite(value) ? value : null);

/** One line per tool that reports facts, built only from those server-sent fields. */
const OUTCOME_TEXT: Record<string, (facts: Record<string, unknown>) => string | null> = {
  collect_boss_jobs: (facts) => {
    const pages = count(facts.pagesRead);
    const created = count(facts.created);
    const existing = count(facts.alreadyInLibrary);
    const notSaved = count(facts.notSaved);
    if (pages == null || created == null || existing == null) return null;
    const parts = [`读了 ${pages} 页`, `新入库 ${created} 个`, `库里已有 ${existing} 个`];
    if (notSaved) parts.push(`${notSaved} 个未保存（职位库已满）`);
    if (typeof facts.stopMessage === "string" && facts.stopMessage) parts.push(`提前停止：${facts.stopMessage}`);
    return parts.join(" · ");
  },
};

/** What a finished step found, e.g. "读了 2 页 · 新入库 3 个 · 库里已有 1 个"; null when the tool reports no facts. */
export function describeOutcome(row: Pick<TimelineStep, "tool" | "status" | "facts">): string | null {
  if (row.status !== "done" || !row.facts || typeof row.facts !== "object") return null;
  return OUTCOME_TEXT[row.tool]?.(row.facts) ?? null;
}

/** Arguments pretty-printed for the expanded view. */
export function formatArguments(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
