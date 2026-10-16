"use client";

import { ArrowLeftRight, Ban, CircleCheck, CircleDashed, Hand } from "lucide-react";
import type { AgentConfirmation } from "../../agent-stream";
import { toolLabel } from "../../agent-steps";

/** A write action the server paused on. Only the active, undecided card can approve or refuse. */
export function ConfirmationCard({ confirmation, decision, active, disabled, onDecide }: {
  confirmation: AgentConfirmation;
  decision?: "approved" | "rejected";
  active: boolean;
  disabled: boolean;
  onDecide: (approved: boolean) => void;
}) {
  const preview = confirmation.preview ?? {};
  const resumeTitle = String(preview.resumeTitle ?? `简历 #${String(confirmation.arguments.resumeId ?? "")}`);
  const jobTitle = String(preview.jobTitle ?? `职位 #${String(confirmation.arguments.jobId ?? "")}`);
  const company = preview.companyName ? `（${String(preview.companyName)}）` : "";
  const open = active && !decision;
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
      </div>
      {open ? (
        <div className="agent-confirm-actions">
          <button className="ghost" type="button" disabled={disabled} onClick={() => onDecide(false)}>拒绝</button>
          <button className="primary" type="button" disabled={disabled} onClick={() => onDecide(true)}>同意并发起</button>
        </div>
      ) : (
        <span className={`agent-confirm-result ${decision ?? "none"}`}>
          {decision === "approved" ? <CircleCheck size={16} aria-hidden="true" />
            : decision === "rejected" ? <Ban size={16} aria-hidden="true" /> : <CircleDashed size={16} aria-hidden="true" />}
          {decision === "approved" ? "已同意" : decision === "rejected" ? "已拒绝" : "未确认"}
        </span>
      )}
    </div>
  );
}
