"use client";

import { Eye, FileText, Pencil, Send, Trash2, UserRound } from "lucide-react";
import Link from "next/link";
import type { Resume } from "../lib/api";
import { formatFileSize, resumeFileKind, resumeUpdatedLabel } from "../resume-list";

/** One row of 我的简历: file icon with its type, title and source, then view / edit / use / delete. */
export function ResumeCard({ resume, selected, disabled, onUse, onEdit, onDelete }: {
  resume: Resume;
  /** The resume the workbench is currently set to. */
  selected: boolean;
  disabled: boolean;
  onUse: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const kind = resumeFileKind(resume);
  const source = resume.originalFileName
    ? [resume.originalFileName, formatFileSize(resume.fileSize)].filter(Boolean).join(" · ")
    : "粘贴文本保存";
  const updated = resumeUpdatedLabel(resume);
  return (
    <article className={`resume-card${selected ? " selected" : ""}`}>
      <span className="resume-file" aria-hidden="true">
        <FileText size={30} strokeWidth={1.5} />
        <em className={`resume-file-kind ${kind.tone}`}>{kind.label}</em>
      </span>
      <div className="resume-card-main">
        <h3 title={resume.title}><Link href={`/resumes/${resume.id}`}>{resume.title}</Link>{selected && <span className="resume-current">当前用于匹配</span>}</h3>
        <p className="resume-card-meta">
          <span><UserRound size={14} aria-hidden="true" />{resume.candidateName}</span>
          <span title={source}>{source}</span>
        </p>
        {updated && <small>更新时间：{updated}</small>}
      </div>
      <div className="resume-card-actions">
        <Link className="ghost" href={`/resumes/${resume.id}`}><Eye size={16} aria-hidden="true" />查看</Link>
        <button type="button" className="ghost" disabled={disabled} onClick={onEdit}><Pencil size={15} aria-hidden="true" />编辑</button>
        <button type="button" className="primary" disabled={disabled} onClick={onUse}><Send size={15} aria-hidden="true" />用于匹配</button>
        <button type="button" className="icon-button danger" aria-label="删除简历" title="删除简历" disabled={disabled} onClick={onDelete}><Trash2 size={17} aria-hidden="true" /></button>
      </div>
    </article>
  );
}
