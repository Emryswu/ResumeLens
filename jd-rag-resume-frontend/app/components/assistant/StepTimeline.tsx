"use client";

import { Ban, CircleCheck, CircleDashed, CircleX, Hand, LoaderCircle, type LucideIcon } from "lucide-react";
import type { AgentTurn } from "../../agent-stream";
import {
  describeArguments,
  formatArguments,
  formatDuration,
  STATUS_LABELS,
  timelineSteps,
  toolTimeMs,
  type StepStatus,
  type TimelineStep,
} from "../../agent-steps";

const STATUS_ICONS: Record<StepStatus, LucideIcon> = {
  running: LoaderCircle,
  done: CircleCheck,
  failed: CircleX,
  rejected: Ban,
  awaiting: Hand,
  unconfirmed: CircleDashed,
  unfinished: CircleDashed,
};

const NO_RESULT: Partial<Record<StepStatus, string>> = {
  running: "工具还在运行",
  awaiting: "还没有执行：等待你确认",
  unconfirmed: "没有执行",
  unfinished: "这一步没有返回结果",
};

/** The tools one turn called, in order, with the state of each; the answer is rendered elsewhere. */
export function StepTimeline({ turn, pendingId, thinking }: {
  turn: AgentTurn;
  pendingId: string | null;
  /** The request is still open and no tool is running: the model is deciding what to do next. */
  thinking: boolean;
}) {
  const rows = timelineSteps(turn, pendingId);
  if (rows.length === 0 && !thinking) return null;
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
      <ol className="agent-timeline">
        {rows.map((row) => <StepRow key={row.id} row={row} />)}
        {thinking && !rows.some((row) => row.status === "running") && (
          <li className="agent-step running thinking">
            <div className="agent-step-line">
              <LoaderCircle className="agent-step-icon spin" size={18} aria-hidden="true" />
              <span>正在思考下一步</span>
            </div>
          </li>
        )}
      </ol>
    </section>
  );
}

function StepRow({ row }: { row: TimelineStep }) {
  const Icon = STATUS_ICONS[row.status];
  const status = row.status === "failed" && row.errorCode ? `${STATUS_LABELS.failed} · ${row.errorCode}` : STATUS_LABELS[row.status];
  const finished = row.status === "done" || row.status === "failed" || row.status === "rejected";
  return (
    <li className={`agent-step ${row.status}`}>
      <details>
        <summary className="agent-step-line">
          <Icon className={`agent-step-icon${row.status === "running" ? " spin" : ""}`} size={18} aria-hidden="true" />
          <strong>{row.label}</strong>
          <span className="agent-step-args">{describeArguments(row.arguments)}</span>
          <span className="agent-step-meta">
            <span className="agent-step-status">{status}</span>
            {row.latencyMs != null && <span>{formatDuration(row.latencyMs)}</span>}
          </span>
        </summary>
        <dl className="agent-step-detail">
          <div><dt>工具</dt><dd><code>{row.tool}</code></dd></div>
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
