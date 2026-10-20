"use client";

import { ArrowRight, BriefcaseBusiness, ClipboardPaste, Eye, FileCheck, FileText, LibraryBig, LoaderCircle, RotateCw, Search, Upload } from "lucide-react";
import { useState } from "react";
import type { AnalysisSummary, Job, Resume } from "../lib/api";
import { companyMark } from "../job-card";
import { filterJobs, type JobSource, type PasteJobForm, type WorkbenchAction } from "../workbench";
import { BrandImage } from "./BrandArt";
import { EmptyState, Skeleton } from "./StateBlock";

type Props = {
  loading: boolean;
  resumes: Resume[];
  jobs: Job[];
  /** More saved jobs exist than are loaded; the picker offers to fetch them. */
  moreJobs: number;
  loadingMoreJobs: boolean;
  onLoadMoreJobs: () => void;
  selectedResumeId: number | "";
  selectedJobId: number | "";
  latestByJob: Map<number, AnalysisSummary>;
  source: JobSource;
  paste: PasteJobForm;
  action: WorkbenchAction;
  busy: boolean;
  /** The main button is running: saving the pasted job or starting the analysis. */
  starting: boolean;
  onChooseResume: (id: number) => void;
  onChooseJob: (id: number) => void;
  onSourceChange: (source: JobSource) => void;
  onPasteChange: (form: PasteJobForm) => void;
  onAnalyze: () => void;
  onViewReport: () => void;
  onManageResumes: () => void;
  onManageJobs: () => void;
};

function updatedLabel(resume: Resume) {
  const stamp = Date.parse(resume.updatedAt || resume.createdAt || "");
  return Number.isFinite(stamp) ? `更新于 ${new Date(stamp).toLocaleDateString("zh-CN")}` : "";
}

function scoreBadge(item?: AnalysisSummary) {
  if (!item) return null;
  if (item.status === "PENDING") return <em className="wb-score pending">分析中</em>;
  if (item.status === "FAILED") return <em className="wb-score failed">上次失败</em>;
  const score = Number(item.matchScore);
  return Number.isFinite(score) ? <em className="wb-score">{score.toFixed(0)} 分</em> : null;
}

const STEPS = [
  { icon: FileText, title: "选择简历", text: "从已保存的简历里选一份" },
  { icon: BriefcaseBusiness, title: "选择或粘贴职位", text: "职位库里挑，或直接粘贴招聘描述" },
  { icon: FileCheck, title: "查看匹配报告", text: "优势、缺口和简历原文证据一目了然" },
];

/** The matching workbench: one resume on the left, one job on the right, then a single button to the report. */
export function Workbench(props: Props) {
  const { loading, resumes, jobs, selectedResumeId, selectedJobId, latestByJob, source, paste, action, busy, starting } = props;
  const [query, setQuery] = useState("");
  const shownJobs = filterJobs(jobs, query);
  const field = (key: keyof PasteJobForm) => ({
    value: paste[key],
    disabled: busy,
    onChange: (event: { target: { value: string } }) => props.onPasteChange({ ...paste, [key]: event.target.value }),
  });

  return <>
    <div className="workbench">
      <section className="wb-panel" aria-labelledby="wb-resume-title">
        <header className="wb-head">
          <span className="wb-step" aria-hidden="true">1</span>
          <h2 id="wb-resume-title">选择简历</h2>
          <button type="button" className="text-action" onClick={props.onManageResumes}>管理简历</button>
        </header>
        {loading ? <Skeleton className="skeleton-rows" label="正在读取简历" lines={3} />
          : resumes.length === 0 ? <EmptyState compact icon={FileText} title="还没有简历" text="先上传或粘贴一份简历，保存后就能在这里选择。"
              action={<button type="button" className="ghost" onClick={props.onManageResumes}><Upload size={16} aria-hidden="true" />上传简历</button>} />
          : <div className="wb-options" role="radiogroup" aria-labelledby="wb-resume-title">
              {resumes.map((resume) => {
                const checked = selectedResumeId === resume.id;
                const meta = [resume.candidateName, resume.originalFileName, updatedLabel(resume)].filter(Boolean).join(" · ");
                return (
                  <label key={resume.id} className={`wb-option${checked ? " checked" : ""}`}>
                    <input type="radio" name="wb-resume" checked={checked} disabled={busy} onChange={() => props.onChooseResume(resume.id)} />
                    <span className="wb-option-icon" aria-hidden="true"><FileText size={20} /></span>
                    <span className="wb-option-copy">
                      <strong title={resume.title}>{resume.title}</strong>
                      <small title={meta}>{meta}</small>
                    </span>
                    <span className="wb-radio" aria-hidden="true" />
                  </label>
                );
              })}
            </div>}
      </section>

      <section className="wb-panel" aria-labelledby="wb-job-title">
        <header className="wb-head">
          <span className="wb-step" aria-hidden="true">2</span>
          <h2 id="wb-job-title">选择职位</h2>
          <button type="button" className="text-action" onClick={props.onManageJobs}>去职位库</button>
        </header>
        <div className="wb-tabs" role="tablist" aria-label="职位来源">
          {([["library", "从职位库选择", LibraryBig], ["paste", "粘贴职位描述", ClipboardPaste]] as const).map(([value, label, Icon]) => (
            <button key={value} id={`wb-tab-${value}`} type="button" role="tab" aria-selected={source === value} aria-controls="wb-job-panel"
              onClick={() => props.onSourceChange(value)}><Icon size={16} aria-hidden="true" />{label}</button>
          ))}
        </div>
        <div id="wb-job-panel" role="tabpanel" aria-labelledby={`wb-tab-${source}`}>
          {source === "library" ? (
            loading ? <Skeleton className="skeleton-rows" label="正在读取职位库" lines={3} />
              : jobs.length === 0 ? <EmptyState compact icon={BriefcaseBusiness} title="职位库还是空的" text="直接粘贴一段职位描述也能开始分析，保存后会进入职位库。"
                  action={<button type="button" className="ghost" onClick={() => props.onSourceChange("paste")}><ClipboardPaste size={16} aria-hidden="true" />粘贴职位描述</button>} />
              : <>
                  <label className="wb-search">
                    <Search size={16} aria-hidden="true" />
                    <input type="search" aria-label="搜索职位" placeholder="搜索职位、公司或城市" value={query} onChange={(event) => setQuery(event.target.value)} />
                  </label>
                  {shownJobs.length === 0
                    ? <p className="wb-none">没有找到「{query.trim()}」相关的职位。</p>
                    : <div className="wb-options wb-job-options" role="radiogroup" aria-labelledby="wb-job-title">
                        {shownJobs.map((job) => {
                          const checked = selectedJobId === job.id;
                          const mark = companyMark(job.companyName);
                          const meta = [job.companyName, job.location].filter(Boolean).join(" · ");
                          return (
                            <label key={job.id} className={`wb-option${checked ? " checked" : ""}`}>
                              <input type="radio" name="wb-job" checked={checked} disabled={busy} onChange={() => props.onChooseJob(job.id)} />
                              <span className={`company-mark small tone-${mark.tone}`} aria-hidden="true">{mark.initial}</span>
                              <span className="wb-option-copy">
                                <strong title={job.title}>{job.title}</strong>
                                <small title={meta}>{meta}</small>
                              </span>
                              {scoreBadge(latestByJob.get(job.id))}
                              <span className="wb-radio" aria-hidden="true" />
                            </label>
                          );
                        })}
                      </div>}
                  {props.moreJobs > 0 && !query.trim() && (
                    <button type="button" className="ghost wb-more" disabled={props.loadingMoreJobs} onClick={props.onLoadMoreJobs}>
                      {props.loadingMoreJobs ? <><LoaderCircle className="spin" size={14} aria-hidden="true" />加载中…</> : `继续加载（还有 ${props.moreJobs} 个）`}
                    </button>
                  )}
                </>
          ) : (
            <div className="wb-paste">
              <div className="field-row">
                <label>职位名称<input required maxLength={160} placeholder="如：Java 后端开发工程师" {...field("title")} /></label>
                <label>公司名称<input required maxLength={120} placeholder="如：星河云科技" {...field("companyName")} /></label>
              </div>
              <label>工作地点（可选）<input maxLength={80} placeholder="如：深圳" {...field("location")} /></label>
              <label>职位描述<textarea required rows={6} placeholder="把招聘页上的岗位职责粘贴到这里" {...field("description")} /></label>
              <label>任职要求（可选）<textarea rows={3} placeholder="技术栈、经验要求等" {...field("requirements")} /></label>
              <p className="wb-hint">开始分析时会先把这个职位保存到职位库，之后可以直接从职位库选择。</p>
            </div>
          )}
        </div>
      </section>
    </div>

    <div className="wb-action">
      <p className={action.kind === "blocked" ? "wb-action-note" : "wb-action-note ready"}>
        {action.kind === "blocked" ? action.reason
          : action.kind === "view-report" ? "这个组合已经分析过，可以直接查看报告。"
          : action.kind === "view-progress" ? "这个组合正在分析中。"
          : "准备好了，开始生成你的匹配报告。"}
      </p>
      {action.kind === "view-report" ? <>
        <button type="button" className="ghost" disabled={busy} onClick={props.onAnalyze}><RotateCw size={16} aria-hidden="true" />重新分析</button>
        <button type="button" className="primary" disabled={busy} onClick={props.onViewReport}><Eye size={16} aria-hidden="true" />查看报告</button>
      </> : action.kind === "view-progress" ? (
        <button type="button" className="primary" onClick={props.onViewReport}><LoaderCircle className="spin" size={16} aria-hidden="true" />查看进度</button>
      ) : (
        <button type="button" className="primary" disabled={busy || action.kind === "blocked"} onClick={props.onAnalyze}>
          {starting ? <><LoaderCircle className="spin" size={16} aria-hidden="true" />正在提交…</> : <>开始分析<ArrowRight size={16} aria-hidden="true" /></>}
        </button>
      )}
    </div>

    <section className="wb-steps" aria-label="使用步骤">
      <BrandImage name="illus-workbench" className="wb-steps-art" />
      <div className="wb-steps-copy">
        <h2>简单三步，看清你和岗位的距离</h2>
        <ol>
          {STEPS.map(({ icon: Icon, title, text }, index) => (
            <li key={title}><span className="wb-steps-icon" aria-hidden="true"><Icon size={18} /></span><strong>{index + 1}. {title}</strong><small>{text}</small></li>
          ))}
        </ol>
      </div>
    </section>
  </>;
}
