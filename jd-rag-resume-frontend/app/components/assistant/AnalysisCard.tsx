"use client";

import Link from "next/link";
import { ArrowRight, CircleAlert, CircleCheck, LoaderCircle } from "lucide-react";
import type { Analysis } from "../../lib/api";
import { scoreLabel } from "../../report-export";
import { reportHref } from "../../workspace-nav";

/** Progress of an analysis the assistant started, followed until the report settles. */
export function AnalysisCard({ analysisId, analysis }: { analysisId: number; analysis?: Analysis }) {
  const href = analysis
    ? reportHref({ resumeId: analysis.resumeId, jobId: analysis.jobDescriptionId, analysisId })
    : reportHref({ analysisId });
  const status = !analysis || analysis.status === "PENDING" ? "pending" : analysis.status === "COMPLETED" ? "completed" : "failed";
  const score = analysis?.matchScore != null && Number.isFinite(Number(analysis.matchScore)) ? Number(analysis.matchScore) : null;
  return (
    <div className={`agent-analysis ${status}`} role="status">
      {status === "pending" ? <LoaderCircle className="spin" size={20} aria-hidden="true" />
        : status === "completed" ? <CircleCheck size={20} aria-hidden="true" /> : <CircleAlert size={20} aria-hidden="true" />}
      <div>
        <strong>
          {status === "pending" ? "完整分析生成中" : status === "completed" ? "完整分析已完成" : "完整分析没有完成"}
        </strong>
        <span>
          {status === "pending" ? "生成后这里会更新，也可以先离开，稍后在分析记录里查看。"
            : status === "completed" ? (score != null ? `匹配分 ${score} · ${scoreLabel(score)}` : "这份报告没有给出匹配分。")
              : analysis?.summary || "原因未知，可以在报告页重试。"}
        </span>
      </div>
      <Link className="ghost" href={href}>查看完整报告<ArrowRight size={16} aria-hidden="true" /></Link>
    </div>
  );
}
