"use client";

import Link from "next/link";
import { ArrowLeftRight, Ban, CircleCheck, CircleDashed, CircleHelp, Hand, LoaderCircle } from "lucide-react";
import type { AgentConfirmation } from "../../agent-stream";
import { toolLabel } from "../../agent-steps";
import { workspaceHref } from "../../workspace-nav";

/** A write action the server paused on. Only the active, undecided card can approve or refuse. */
export function ConfirmationCard({ confirmation, decision, approvalSent, active, disabled, onDecide }: {
  confirmation: AgentConfirmation;
  decision?: "approved" | "rejected";
  /** The user approved and the request went out; without an acknowledged step the outcome is unknown. */
  approvalSent: boolean;
  active: boolean;
  disabled: boolean;
  onDecide: (approved: boolean) => void;
}) {
  const preview = confirmation.preview ?? {};
  const resumeTitle = String(preview.resumeTitle ?? `简历 #${String(confirmation.arguments.resumeId ?? "")}`);
  const jobTitle = String(preview.jobTitle ?? `职位 #${String(confirmation.arguments.jobId ?? "")}`);
  const company = preview.companyName ? `（${String(preview.companyName)}）` : "";
  const open = active && !decision && !approvalSent;
  const unknown = !decision && approvalSent && !active;
  return (
    <div className={`agent-confirm${open ? " open" : ""}`} role="group" aria-label="待确认的操作">
      <Hand className="agent-confirm-icon" size={20} aria-hidden="true" />
      <div className="agent-confirm-body">
        <h3>需要你确认：{toolLabel(confirmation.tool)}</h3>
        <p className="agent-confirm-pair">
          <strong>{resumeTitle}</strong>
          <ArrowLeftRight size={16} aria-label="对照" />
          <strong>{jobTitle}{company}</strong>
        </p>
        <small>这会消耗一次分析配额并调用一次大模型。</small>
        {unknown && (
          <p className="agent-confirm-unknown">
            连接在返回结果前中断，无法确定分析是否已经发起。请先到<Link href={workspaceHref("history")}>分析记录</Link>核实，不要直接重新发起。
          </p>
        )}
      </div>
      {open ? (
        <div className="agent-confirm-actions">
          <button className="ghost" type="button" disabled={disabled} onClick={() => onDecide(false)}>拒绝</button>
          <button className="primary" type="button" disabled={disabled} onClick={() => onDecide(true)}>同意并发起</button>
        </div>
      ) : (
        <span className={`agent-confirm-result ${decision ?? (approvalSent ? (active ? "sending" : "unknown") : "none")}`}>
          {decision === "approved" ? <><CircleCheck size={16} aria-hidden="true" />已同意</>
            : decision === "rejected" ? <><Ban size={16} aria-hidden="true" />已拒绝</>
              : approvalSent && active ? <><LoaderCircle className="spin" size={16} aria-hidden="true" />已同意，正在提交</>
                : approvalSent ? <><CircleHelp size={16} aria-hidden="true" />已同意，结果未知</>
                  : <><CircleDashed size={16} aria-hidden="true" />未确认</>}
        </span>
      )}
    </div>
  );
}
