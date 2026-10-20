"use client";

import { Bookmark, BookmarkCheck, MapPin, Pencil, Send, Trash2 } from "lucide-react";
import Link from "next/link";
import type { AnalysisSummary, Job } from "../lib/api";
import { companyMark, jobFitLabel, jobMetaParts, jobRing } from "../job-card";

/** A small progress ring with the number inside; `muted` marks a first-pass score rather than an analysis. */
export function MiniRing({ value, label, muted }: { value: number | null; label: string; muted?: boolean }) {
  const radius = 30;
  const length = 2 * Math.PI * radius;
  const clamped = value == null ? 0 : Math.max(0, Math.min(100, value));
  return (
    <div className={`mini-ring${muted ? " muted" : ""}`} role="img" aria-label={value == null ? label : `${label} ${Math.round(clamped)}`}>
      <svg viewBox="0 0 72 72" width="72" height="72" aria-hidden="true">
        <circle cx="36" cy="36" r={radius} className="mini-ring-track" />
        {value != null && <circle cx="36" cy="36" r={radius} className="mini-ring-value" strokeDasharray={`${(clamped / 100) * length} ${length}`} transform="rotate(-90 36 36)" />}
      </svg>
      <div aria-hidden="true">{value == null ? <span className="mini-ring-none">—</span> : <strong>{Math.round(clamped)}</strong>}<small>{label}</small></div>
    </div>
  );
}

export function JobCard({ job, analysis, similarity, prefer, selected, saved, disabled, onUse, onToggleSave, onEdit, onDelete }: {
  job: Job;
  /** Latest analysis of this job against the current resume, if any. */
  analysis?: AnalysisSummary;
  /** Whole-document cosine against the current resume, 0..1. */
  similarity?: number;
  /** Which number leads when both exist: the ring follows the sort the user picked. */
  prefer: "score" | "semantic";
  selected?: boolean;
  saved: boolean;
  disabled?: boolean;
  onUse: () => void;
  onToggleSave: () => void;
  onEdit?: () => void;
  onDelete?: () => void;
}) {
  const mark = companyMark(job.companyName);
  const fit = jobFitLabel(analysis?.status === "COMPLETED" ? analysis.matchScore : null);
  const [company, ...rest] = jobMetaParts(job);
  return (
    <article className={`job-card${selected ? " selected" : ""}`}>
      <span className={`company-mark tone-${mark.tone}`} aria-hidden="true">{mark.initial}</span>
      <div className="job-card-main">
        <div className="job-card-title">
          <h3 title={job.title}><Link href={`/jobs/${job.id}`}>{job.title}</Link></h3>
          {fit && <span className={`fit-badge ${fit.tone}`}>{fit.text}</span>}
        </div>
        <p className="job-card-company" title={company}>{company}</p>
        <p className="job-card-meta">
          {job.location && <span><MapPin size={14} aria-hidden="true" />{job.location}</span>}
          {rest.filter((part) => part !== job.location).map((part) => <span key={part}>{part}</span>)}
          {job.sourcePlatform && <span className="job-source">{job.sourcePlatform}</span>}
        </p>
      </div>
      <p className="job-card-desc" title={job.description}>{job.description}</p>
      <MiniRing {...jobRing({ analysis, similarity, prefer })} />
      <div className="job-card-actions">
        <Link className="ghost job-detail-link" href={`/jobs/${job.id}`}>查看详情</Link>
        <button type="button" className="primary" disabled={disabled} onClick={onUse}><Send size={15} aria-hidden="true" />用于匹配</button>
        <button type="button" className="icon-button" aria-pressed={saved} aria-label={saved ? "取消保存" : "保存岗位"} title={saved ? "取消保存" : "保存岗位"} onClick={onToggleSave}>
          {saved ? <BookmarkCheck size={18} aria-hidden="true" /> : <Bookmark size={18} aria-hidden="true" />}
        </button>
        {onEdit && <button type="button" className="icon-button" aria-label="编辑岗位" title="编辑岗位" disabled={disabled} onClick={onEdit}><Pencil size={17} aria-hidden="true" /></button>}
        {onDelete && <button type="button" className="icon-button danger" aria-label="删除岗位" title="删除岗位" disabled={disabled} onClick={onDelete}><Trash2 size={17} aria-hidden="true" /></button>}
      </div>
    </article>
  );
}
