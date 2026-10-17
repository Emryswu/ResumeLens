"use client";

import { ChevronRight, Clock, LoaderCircle } from "lucide-react";
import type { AgentTurn } from "../../agent-stream";
import {
  describeArguments,
  formatArguments,
  formatDuration,
  processSummary,
  STATUS_LABELS,
  timelineSteps,
  toolMeta,
  toolTimeMs,
  type StepStatus,
  type TimelineStep,
} from "../../agent-steps";
import { SideDrawer } from "../SideDrawer";

const NO_RESULT: Partial<Record<StepStatus, string>> = {
  running: "工具还在运行",
  awaiting: "还没有执行：等待你确认",
  approving: "已提交你的同意，等待服务端执行",
  unknown: "已提交你的同意，但连接在返回结果前中断，无法确定是否已经执行。请先到「分析记录」核实，不要直接重新发起。",
  unconfirmed: "没有执行",
  unfinished: "这一步没有返回结果",
};

export const PROCESS_DRAWER_ID = "process-drawer";

/**
 * The tools one turn called. While the turn runs, the steps show live; once the answer is in, they fold into one
 * summary line that opens the full timeline in a side panel. The answer itself is rendered elsewhere.
 */
export function StepTimeline({ turn, pendingId, thinking, open, onOpen }: {
  turn: AgentTurn;
  pendingId: string | null;
  /** The request is still open and no tool is running: the model is deciding what to do next. */
  thinking: boolean;
  /** This turn's process is the one shown in the side panel. */
  open: boolean;
  onOpen: () => void;
}) {
  const rows = timelineSteps(turn, pendingId);
  if (rows.length === 0 && !thinking) return null;
  const summary = !thinking && turn.answer ? processSummary(rows) : null;
  if (summary) {
    const Icon = summary.icon === "multiple" ? Clock : toolMeta(summary.icon).icon;
    const spoken = [summary.text, ...summary.issues.map((issue) => issue.label)].join("，");
    return (
      <button type="button" className="process-summary" onClick={onOpen} aria-controls={PROCESS_DRAWER_ID} aria-expanded={open}
        aria-label={`查看处理过程：${spoken}`} title={summary.text}>
        <Icon className="process-summary-icon" size={18} aria-hidden="true" />
        <span className="process-summary-text">{summary.text}</span>
        {summary.issues.map((issue) => <span key={issue.status} className={`process-summary-issue ${issue.status}`}>{issue.label}</span>)}
        {summary.totalMs != null && <span className="process-summary-time">{formatDuration(summary.totalMs)}</span>}
        <ChevronRight className="process-summary-chevron" size={18} aria-hidden="true" />
      </button>
    );
  }
  const toolTime = toolTimeMs(rows);
  return (
    <section className="assistant-process" aria-label="处理过程">
      <header>
        <h3>处理过程</h3>
        <span>
          {rows.length > 0 && `${rows.length} 步`}
          {toolTime != null && ` · 工具耗时 ${formatDuration(toolTime)}`}
        </span>
      </header>
      <Timeline rows={rows} thinking={thinking} />
    </section>
  );
}

/** The full timeline of one finished turn, opened from its summary line. */
export function ProcessDrawer({ turn, pendingId, onClose }: { turn: AgentTurn; pendingId: string | null; onClose: () => void }) {
  const rows = timelineSteps(turn, pendingId);
  const summary = processSummary(rows);
  const toolTime = toolTimeMs(rows);
  return (
    <SideDrawer id={PROCESS_DRAWER_ID} title={summary?.text ?? "处理过程"} closeLabel="关闭处理过程" onClose={onClose}
      subtitle={`${rows.length} 步${toolTime != null ? ` · 工具耗时 ${formatDuration(toolTime)}` : ""}`}>
      <Timeline rows={rows} thinking={false} />
    </SideDrawer>
  );
}

function Timeline({ rows, thinking }: { rows: TimelineStep[]; thinking: boolean }) {
  return (
    <ol className="agent-timeline">
      {rows.map((row) => <StepRow key={row.id} row={row} />)}
      {thinking && !rows.some((row) => row.status === "running") && (
        <li className="agent-step running thinking">
          <div className="agent-step-line">
            <span className="agent-step-node" aria-hidden="true"><LoaderCircle className="spin" size={16} /></span>
            <span>正在思考下一步</span>
          </div>
        </li>
      )}
    </ol>
  );
}

function StepRow({ row }: { row: TimelineStep }) {
  const Icon = toolMeta(row.tool).icon;
  const spinning = row.status === "running" || row.status === "approving";
  const finished = row.status === "done" || row.status === "failed" || row.status === "rejected";
  return (
    <li className={`agent-step ${row.status}`}>
      <details>
        <summary className="agent-step-line">
          <span className="agent-step-node" aria-hidden="true">
            {spinning ? <LoaderCircle className="spin" size={16} /> : <Icon size={16} />}
          </span>
          <strong>{row.label}</strong>
          <span className="agent-step-args">{describeArguments(row.arguments)}</span>
          <span className="agent-step-meta">
            {/* Only a finished step goes without a word; every other state says what happened. */}
            <span className={`agent-step-status${row.status === "done" ? " quiet" : ""}`}>{STATUS_LABELS[row.status]}</span>
            {row.latencyMs != null && <span>{formatDuration(row.latencyMs)}</span>}
          </span>
          <ChevronRight className="agent-step-chevron" size={16} aria-hidden="true" />
        </summary>
        <dl className="agent-step-detail">
          <div><dt>工具</dt><dd><code>{row.tool}</code></dd></div>
          {row.status === "failed" && row.errorCode && <div><dt>错误码</dt><dd><code>{row.errorCode}</code></dd></div>}
          <div><dt>参数</dt><dd><pre>{formatArguments(row.arguments)}</pre></dd></div>
          <div>
            <dt>结果</dt>
            <dd>
              {finished
                ? <><span className="agent-step-size">工具返回 {row.resultChars ?? 0} 字符，预览：</span><pre>{row.resultPreview || "（空）"}</pre></>
                : <span className="agent-step-size">{NO_RESULT[row.status]}</span>}
            </dd>
          </div>
        </dl>
      </details>
    </li>
  );
}
