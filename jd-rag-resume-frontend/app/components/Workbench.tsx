"use client";

import { BriefcaseBusiness, ChartNoAxesColumn, ChevronRight, Eye, FileText, Lightbulb, LoaderCircle, Play, RotateCw, Search, Upload } from "lucide-react";
import { useState, type ReactNode } from "react";
import type { AnalysisSummary, Job, Resume } from "../lib/api";
import { companyMark } from "../job-card";
import { filterJobs, PASTE_DESCRIPTION_MAX, type JobSource, type PasteJobForm, type WorkbenchAction } from "../workbench";
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
  onClear: () => void;
  onAnalyze: () => void;
  onViewReport: () => void;
  onManageResumes: () => void;
  onManageJobs: () => void;
};

const PASTE_PLACEHOLDER = `请粘贴完整的职位描述，包括工作职责、任职要求等内容。

例如：
工作职责：
1. 负责招聘业务核心服务的设计与开发
2. 参与高并发接口优化与稳定性建设

任职要求：
1. 熟悉 Java、Spring Boot、MySQL
2. 有检索或 RAG 相关项目经验优先`;

const STEPS = [
  { title: "选择你的简历", text: "从你的简历库中选择最合适的版本" },
  { title: "添加职位描述", text: "粘贴职位描述，或从职位库选择目标职位" },
  { title: "开始分析", text: "AI 逐条核对要求，生成带证据的报告" },
];

function updatedLabel(resume: Resume) {
  const stamp = Date.parse(resume.updatedAt || resume.createdAt || "");
  return Number.isFinite(stamp) ? `最近更新：${new Date(stamp).toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric" })}` : "";
}

function scoreBadge(item?: AnalysisSummary) {
  if (!item) return null;
  if (item.status === "PENDING") return <em className="wb-score pending">分析中</em>;
  if (item.status === "FAILED") return <em className="wb-score failed">上次失败</em>;
  const score = Number(item.matchScore);
  return Number.isFinite(score) ? <em className="wb-score">{score.toFixed(0)} 分</em> : null;
}

function PanelHead({ step, id, title, text, action }: { step: number; id: string; title: string; text: string; action?: ReactNode }) {
  return (
    <header className="wb-head">
      <span className="wb-step" aria-hidden="true">{step}</span>
      <div className="wb-head-copy">
        <h2 id={id}>{title}</h2>
        <p>{text}</p>
      </div>
      {action}
    </header>
  );
}

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
  const descriptionLength = paste.description.trim().length;

  return <>
    <div className="workbench">
      <section className="wb-panel" aria-labelledby="wb-resume-title">
        <PanelHead step={1} id="wb-resume-title" title="选择你的简历" text="从你的简历库中选择一份简历，用于本次匹配分析"
          action={<button type="button" className="ghost wb-head-action" onClick={props.onManageResumes}><FileText size={16} aria-hidden="true" />管理我的简历</button>} />
        {loading ? <Skeleton className="skeleton-rows" label="正在读取简历" lines={3} />
          : resumes.length === 0 ? <EmptyState compact icon={FileText} title="还没有简历" text="先上传或粘贴一份简历，保存后就能在这里选择。"
              action={<button type="button" className="ghost" onClick={props.onManageResumes}><Upload size={16} aria-hidden="true" />上传简历</button>} />
          : <div className="wb-options" role="radiogroup" aria-labelledby="wb-resume-title">
              {resumes.map((resume) => {
                const checked = selectedResumeId === resume.id;
                const source = [resume.candidateName, resume.originalFileName].filter(Boolean).join(" · ");
                return (
                  <label key={resume.id} className={`wb-option wb-resume${checked ? " checked" : ""}`}>
                    <input type="radio" name="wb-resume" checked={checked} disabled={busy} onChange={() => props.onChooseResume(resume.id)} />
                    <span className="wb-radio" aria-hidden="true" />
                    <span className="wb-option-icon" aria-hidden="true"><FileText size={24} /></span>
                    <span className="wb-option-copy">
                      <strong title={resume.title}>{resume.title}</strong>
                      {source && <small title={source}>{source}</small>}
                      <small>{updatedLabel(resume)}</small>
                    </span>
                  </label>
                );
              })}
            </div>}
      </section>

      <section className="wb-panel" aria-labelledby="wb-job-title">
        <PanelHead step={2} id="wb-job-title" title="添加职位描述" text="粘贴职位描述，或从职位库中选择，来进行匹配分析"
          action={<button type="button" className="ghost wb-head-action" onClick={props.onManageJobs}><BriefcaseBusiness size={16} aria-hidden="true" />职位库</button>} />
        <div className="wb-tabs" role="tablist" aria-label="职位来源">
          {([["paste", "粘贴职位描述", FileText], ["library", "从职位库选择", ChartNoAxesColumn]] as const).map(([value, label, Icon]) => (
            <button key={value} id={`wb-tab-${value}`} type="button" role="tab" aria-selected={source === value} aria-controls="wb-job-panel"
              onClick={() => props.onSourceChange(value)}><Icon size={16} aria-hidden="true" />{label}</button>
          ))}
        </div>
        <div id="wb-job-panel" role="tabpanel" aria-labelledby={`wb-tab-${source}`}>
          {source === "paste" ? (
            <div className="wb-paste">
              <div className="field-row">
                <label>职位名称<input required maxLength={160} placeholder="如：Java 后端开发工程师" {...field("title")} /></label>
                <label>公司名称<input required maxLength={120} placeholder="如：星河云科技" {...field("companyName")} /></label>
              </div>
              <label className="wb-jd">
                <textarea required rows={10} aria-label="职位描述" aria-describedby="wb-jd-count" placeholder={PASTE_PLACEHOLDER} {...field("description")} />
                <small id="wb-jd-count" className={descriptionLength > PASTE_DESCRIPTION_MAX ? "over" : undefined}>{descriptionLength} / {PASTE_DESCRIPTION_MAX}</small>
              </label>
              <p className="wb-hint">开始分析时会先把这个职位保存到职位库，之后可以直接选择。</p>
            </div>
          ) : loading ? <Skeleton className="skeleton-rows" label="正在读取职位库" lines={3} />
            : jobs.length === 0 ? <EmptyState compact icon={BriefcaseBusiness} title="职位库还是空的" text="直接粘贴一段职位描述也能开始分析，保存后会进入职位库。"
                action={<button type="button" className="ghost" onClick={() => props.onSourceChange("paste")}><FileText size={16} aria-hidden="true" />粘贴职位描述</button>} />
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
                            <span className="wb-radio" aria-hidden="true" />
                            <span className={`company-mark small tone-${mark.tone}`} aria-hidden="true">{mark.initial}</span>
                            <span className="wb-option-copy">
                              <strong title={job.title}>{job.title}</strong>
                              <small title={meta}>{meta}</small>
                            </span>
                            {scoreBadge(latestByJob.get(job.id))}
                          </label>
                        );
                      })}
                    </div>}
                {props.moreJobs > 0 && !query.trim() && (
                  <button type="button" className="ghost wb-more" disabled={props.loadingMoreJobs} onClick={props.onLoadMoreJobs}>
                    {props.loadingMoreJobs ? <><LoaderCircle className="spin" size={14} aria-hidden="true" />加载中…</> : `继续加载（还有 ${props.moreJobs} 个）`}
                  </button>
                )}
              </>}
        </div>
      </section>
    </div>

    <section className="wb-steps" aria-labelledby="wb-steps-title">
      <h2 id="wb-steps-title"><Lightbulb size={20} aria-hidden="true" />简单三步，获得专属匹配分析</h2>
      <div className="wb-steps-body">
        <ol>
          {STEPS.map(({ title, text }, index) => (
            <li key={title}>
              <span className="wb-steps-no" aria-hidden="true">{index + 1}</span>
              <div><strong>{title}</strong><small>{text}</small></div>
              {index < STEPS.length - 1 && <ChevronRight className="wb-steps-arrow" size={18} aria-hidden="true" />}
            </li>
          ))}
        </ol>
        <div className="wb-steps-aside">
          <BrandImage name="illus-workbench" className="wb-steps-art" />
          <p>完成设置后，点击「开始分析」，我们会为你生成专属的匹配报告，帮你看清和岗位的距离。</p>
        </div>
      </div>
    </section>

    <div className="wb-action">
      <p className={action.kind === "blocked" ? "wb-action-note" : "wb-action-note ready"} role="status">
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
      ) : <>
        <button type="button" className="ghost" disabled={busy} onClick={props.onClear}>清空内容</button>
        <button type="button" className="primary" disabled={busy || action.kind === "blocked"} onClick={props.onAnalyze}>
          {starting ? <><LoaderCircle className="spin" size={16} aria-hidden="true" />正在提交…</> : <><Play size={16} aria-hidden="true" />开始分析</>}
        </button>
      </>}
    </div>
  </>;
}

