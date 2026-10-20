"use client";

import { Bookmark, Building2, FileText, MapPin, ScrollText } from "lucide-react";
import type { Analysis, Job, Resume } from "../lib/api";
import { analysisTime, historyStatusLabel } from "../history-view";
import { resumeUpdatedLabel } from "../resume-list";
import { MiniRing } from "./JobCard";

/**
 * One row of 分析记录. The record only carries the resume and job titles; company, location and the resume's update
 * time come from the lists already loaded, and are simply left out when the item is not among them.
 */
export function HistoryRow({ item, resume, job, saved, onOpen }: {
  item: Analysis;
  resume?: Resume;
  job?: Job;
  saved: boolean;
  onOpen: () => void;
}) {
  const score = item.status === "COMPLETED" && item.matchScore != null && Number.isFinite(Number(item.matchScore)) ? Number(item.matchScore) : null;
  const resumeMeta = resume ? resumeUpdatedLabel(resume) : "";
  return (
    <div className="history-item" role="row">
      <div className="history-cell history-resume" role="cell">
        <span className="history-icon" aria-hidden="true"><FileText size={20} /></span>
        <span className="history-copy">
          <strong title={item.resumeTitle}>{item.resumeTitle}</strong>
          <small>{resumeMeta ? `更新于 ${resumeMeta.split(" ")[0]}` : `简历 #${item.resumeId}`}</small>
        </span>
      </div>
      <div className="history-cell history-job" role="cell">
        <Building2 className="history-job-icon" size={22} aria-hidden="true" />
        <span className="history-copy">
          <strong title={item.jobTitle}>{item.jobTitle}</strong>
          {job?.companyName && <small title={job.companyName}>{job.companyName}</small>}
        </span>
        {job?.location && <span className="history-chip"><MapPin size={12} aria-hidden="true" />{job.location}</span>}
      </div>
      <div className="history-cell history-score" role="cell">
        <MiniRing value={score} label={score != null ? "分" : item.status === "PENDING" ? "分析中" : "失败"} />
      </div>
      <div className="history-cell history-time" role="cell">
        <span>{analysisTime(item.createdAt)}</span>
        <small className={`history-status ${item.status.toLowerCase()}`}>
          {historyStatusLabel(item.status)}{saved && <><Bookmark size={12} aria-hidden="true" />已收藏岗位</>}
        </small>
      </div>
      <div className="history-cell history-actions" role="cell">
        <button type="button" className="ghost" onClick={onOpen}><ScrollText size={16} aria-hidden="true" />{item.status === "COMPLETED" ? "查看报告" : item.status === "PENDING" ? "查看进度" : "查看详情"}</button>
      </div>
    </div>
  );
}
