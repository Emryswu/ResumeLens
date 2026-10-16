/**
 * Turns one assistant turn into the rows of its step bar. Every row comes from a server event or from the
 * pending confirmation; durations and result sizes are only ever the numbers the server reported.
 */
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
  | "unfinished";

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
};

export const TOOL_LABELS: Record<string, string> = {
  list_resumes: "读取简历列表",
  search_jobs: "搜索职位库",
  get_job: "读取职位详情",
  rank_jobs_for_resume: "按语义相似度排序职位",
  search_resume_evidence: "检索简历证据（RAG）",
  get_latest_analysis: "读取最近一次分析",
  start_analysis: "发起完整分析",
};

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
};

export function toolLabel(tool: string): string {
  return TOOL_LABELS[tool] ?? tool;
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

/** Arguments pretty-printed for the expanded view. */
export function formatArguments(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
