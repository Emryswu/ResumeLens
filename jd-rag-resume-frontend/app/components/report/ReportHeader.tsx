"use client";

import { ArrowLeftRight, Bookmark, BookmarkCheck } from "lucide-react";
import { scoreLabel } from "../../report-export";

function ScoreRing({ score }: { score: number | null }) {
  const radius = 52;
  const length = 2 * Math.PI * radius;
  const value = score == null ? 0 : Math.max(0, Math.min(100, score));
  return (
    <div className="score-meter" role="img" aria-label={score == null ? "暂无匹配分" : `匹配分 ${Math.round(value)} / 100`}>
      <svg viewBox="0 0 120 120" width="120" height="120" aria-hidden="true">
        <circle cx="60" cy="60" r={radius} className="score-track" />
        <circle cx="60" cy="60" r={radius} className="score-value" strokeDasharray={`${(value / 100) * length} ${length}`} transform="rotate(-90 60 60)" />
      </svg>
      <div><strong>{score == null ? "—" : Math.round(value)}</strong><span>匹配分</span></div>
    </div>
  );
}

export function ReportHeader({ reportId, score, resumeTitle, jobTitle, jobMeta, summary, saved, onBookmark }: {
  reportId: number;
  score: number | null;
  resumeTitle: string;
  jobTitle: string;
  jobMeta: string;
  summary?: string;
  saved: boolean;
  onBookmark: () => void;
}) {
  return (
    <div className="report-header">
      <div className="report-score">
        <ScoreRing score={score} />
        {score != null && <span className="score-band">{scoreLabel(score)}</span>}
      </div>
      <div className="report-main">
        <div className="report-pair">
          <div className="report-side">
            <span>你的简历</span>
            <strong title={resumeTitle}>{resumeTitle}</strong>
          </div>
          <ArrowLeftRight className="report-pair-icon" size={20} aria-label="对照" />
          <div className="report-side">
            <span>目标职位</span>
            <strong title={jobTitle}>{jobTitle}</strong>
            {jobMeta && <small title={jobMeta}>{jobMeta}</small>}
          </div>
          <button type="button" className="ghost bookmark" onClick={onBookmark} aria-pressed={saved}>
            {saved ? <BookmarkCheck size={16} aria-hidden="true" /> : <Bookmark size={16} aria-hidden="true" />}
            {saved ? "已保存" : "保存岗位"}
          </button>
        </div>
        <div className="report-conclusion">
          <h2>结论</h2>
          <p>{summary || "这份报告没有给出结论，请查看下方的逐条结果。"}</p>
          <small>报告 #{reportId}</small>
        </div>
      </div>
    </div>
  );
}
