import { ArrowDownWideNarrow, BriefcaseBusiness, FileText, ListChecks, LoaderCircle, Plus, Sparkles, Upload, X } from "lucide-react";
import type { FormEvent } from "react";
import { SAMPLE_BULK_JOBS, type AnalysisSummary, type Job, type Resume } from "../../lib/api";
import {
  JOB_SORT_OPTIONS,
  jobSortShortLabel,
  type JobAnalysesStatus,
  type JobFilter,
  type JobFormState,
  type JobSemanticStatus,
  type JobSort,
} from "../../workspace/workspace-helpers";

const DEFAULT_BULK_IMPORT_TEXT = JSON.stringify(SAMPLE_BULK_JOBS, null, 2);
import { ChoiceMenu } from "../ChoiceMenu";
import { JobCard } from "../JobCard";
import { EmptyState, ErrorState, LoadingState, Skeleton } from "../StateBlock";

type JobLibrarySectionProps = {
  hidden: boolean;
  workspaceLoading: boolean;
  busy: string;
  resumes: Resume[];
  selectedResumeId: number | "";
  jobs: Job[];
  libraryJobs: Job[];
  effectiveJobsTotal: number;
  selectedJobId: number | "";
  savedJobs: number[];
  jobsLoadingMore: boolean;
  jobSort: JobSort;
  jobFilter: JobFilter;
  topMatchCount: number;
  jobAnalysesLoading: boolean;
  jobAnalysesStatus: JobAnalysesStatus;
  jobAnalysesError: string;
  jobSemanticStatus: JobSemanticStatus;
  jobSemanticError: string;
  jobAnalysisDataRequired: boolean;
  jobSemanticDataRequired: boolean;
  libraryRankingReady: boolean;
  latestAnalysisByJob: Map<number, AnalysisSummary>;
  semanticSimilarityByJob: Map<number, number>;
  showJobEditor: boolean;
  editingJobId: number | null;
  jobForm: JobFormState;
  showBulkImport: boolean;
  bulkImportText: string;
  onJobSortChange: (next: JobSort) => void;
  onJobFilterChange: (next: JobFilter) => void;
  onTopMatchCountChange: (count: number) => void;
  onLoadAllJobsForRanking: () => void;
  onRunTopMatchesAnalysis: () => void;
  onChooseResume: (id: number | "") => void;
  onResetJobEditor: () => void;
  onSetShowJobEditor: (show: boolean) => void;
  onSetShowBulkImport: (show: boolean | ((prev: boolean) => boolean)) => void;
  onSaveJob: (event: FormEvent) => void;
  onJobFormChange: (next: JobFormState) => void;
  onBulkImportTextChange: (text: string) => void;
  onBulkImportJobs: () => void;
  onSaveSampleAndAnalyze: () => void;
  onRetryJobAnalyses: (resumeId: number) => void;
  onRetrySemanticMatches: (resumeId: number) => void;
  onUseJob: (jobId: number) => void;
  onToggleBookmark: (jobId: number) => void;
  onBeginEditJob: (job: Job) => void;
  onDeleteJob: (jobId: number) => void;
  onLoadMoreJobs: () => void;
};

export function JobLibrarySection({
  hidden,
  workspaceLoading,
  busy,
  resumes,
  selectedResumeId,
  jobs,
  libraryJobs,
  effectiveJobsTotal,
  selectedJobId,
  savedJobs,
  jobsLoadingMore,
  jobSort,
  jobFilter,
  topMatchCount,
  jobAnalysesLoading,
  jobAnalysesStatus,
  jobAnalysesError,
  jobSemanticStatus,
  jobSemanticError,
  jobAnalysisDataRequired,
  jobSemanticDataRequired,
  libraryRankingReady,
  latestAnalysisByJob,
  semanticSimilarityByJob,
  showJobEditor,
  editingJobId,
  jobForm,
  showBulkImport,
  bulkImportText,
  onJobSortChange,
  onJobFilterChange,
  onTopMatchCountChange,
  onLoadAllJobsForRanking,
  onRunTopMatchesAnalysis,
  onChooseResume,
  onResetJobEditor,
  onSetShowJobEditor,
  onSetShowBulkImport,
  onSaveJob,
  onJobFormChange,
  onBulkImportTextChange,
  onBulkImportJobs,
  onSaveSampleAndAnalyze,
  onRetryJobAnalyses,
  onRetrySemanticMatches,
  onUseJob,
  onToggleBookmark,
  onBeginEditJob,
  onDeleteJob,
  onLoadMoreJobs,
}: JobLibrarySectionProps) {
  return (
    <section className="job-library" id="jobs" hidden={hidden}>
      <div className="library-toolbar">
        <div className="library-filters">
          <div className="filter-field">
            <span><ArrowDownWideNarrow size={15} aria-hidden="true" />排序</span>
            <ChoiceMenu<JobSort>
              label="职位排序"
              value={jobSort}
              disabled={jobsLoadingMore}
              options={JOB_SORT_OPTIONS.map(({ value, label, description }) => ({ value, label, description }))}
              onChange={(next) => {
                onJobSortChange(next);
                if (next === "score" || next === "analyzed") onLoadAllJobsForRanking();
              }}
            >
              {jobSortShortLabel(jobSort)}
            </ChoiceMenu>
          </div>
          <label className="filter-check">
            <input
              type="checkbox"
              checked={jobFilter === "unanalyzed"}
              disabled={jobsLoadingMore}
              onChange={(event) => {
                const next = event.target.checked ? "unanalyzed" : "all";
                onJobFilterChange(next);
                if (next === "unanalyzed") onLoadAllJobsForRanking();
              }}
            />
            仅看未分析
          </label>
          <div className="filter-topn">
            <ChoiceMenu
              label="精析数量"
              value={String(topMatchCount)}
              options={[
                { value: "3", label: "前 3 个", description: "精析语义排名前三的岗位" },
                { value: "5", label: "前 5 个", description: "精析语义排名前五的岗位" },
                { value: "10", label: "前 10 个", description: "精析语义排名前十的岗位" },
              ]}
              onChange={(value) => onTopMatchCountChange(Number(value))}
            >
              前 {topMatchCount} 个
            </ChoiceMenu>
            <button
              className="ghost"
              type="button"
              disabled={!!busy || !selectedResumeId}
              onClick={onRunTopMatchesAnalysis}
              title="把语义相关度最高的几个岗位逐个做完整分析"
            >
              <Sparkles size={15} aria-hidden="true" />{busy === "top-analysis" ? "逐个精析中…" : "精析"}
            </button>
          </div>
        </div>
        <div className="library-resume">
          <span className="library-resume-icon" aria-hidden="true"><FileText size={20} /></span>
          <div className="library-resume-copy">
            <small>当前用于匹配</small>
            <ChoiceMenu
              label="当前用于匹配的简历"
              value={String(selectedResumeId)}
              disabled={!!busy}
              options={[
                { value: "", label: "请选择简历", description: "尚未选择用于匹配的简历" },
                ...resumes.map((item) => ({ value: String(item.id), label: item.title, description: "使用这份简历进行岗位匹配" })),
              ]}
              onChange={(value) => onChooseResume(value ? Number(value) : "")}
            >
              {resumes.find((item) => item.id === selectedResumeId)?.title ?? "请选择简历"}
            </ChoiceMenu>
          </div>
        </div>
      </div>

      <div className="library-head">
        <h2>全部岗位<em>{effectiveJobsTotal}</em></h2>
        <small>语义相关度用于初筛，综合匹配分来自单独的完整分析。</small>
        <div className="library-actions">
          <button
            className="ghost"
            type="button"
            aria-expanded={showJobEditor}
            onClick={() => {
              if (showJobEditor && !editingJobId) {
                onSetShowJobEditor(false);
                return;
              }
              onResetJobEditor();
              onSetShowJobEditor(true);
            }}
          >
            <Plus size={16} aria-hidden="true" />添加岗位
          </button>
          <button className="ghost" type="button" onClick={() => { onSetShowJobEditor(true); onSetShowBulkImport(true); }}>
            <Upload size={16} aria-hidden="true" />批量导入
          </button>
        </div>
      </div>

      {showJobEditor && (
        <div className="library-editor" id="job-editor">
          <button
            className="icon-button library-editor-close"
            type="button"
            aria-label="收起岗位表单"
            onClick={() => { onResetJobEditor(); onSetShowJobEditor(false); }}
          >
            <X size={18} aria-hidden="true" />
          </button>
          <form className="step-card" onSubmit={onSaveJob}>
            <div className="step-title">
              <span>02</span>
              <div>
                <h3>{editingJobId ? `编辑职位 #${editingJobId}` : "录入职位 JD"}</h3>
                <p>{editingJobId ? "修改岗位要求后保存" : "告诉 AI 目标岗位要求"}</p>
              </div>
            </div>
            <div className="field-row">
              <label>职位名称<input required value={jobForm.title} onChange={(e) => onJobFormChange({ ...jobForm, title: e.target.value })} placeholder="Java RAG 工程师" /></label>
              <label>公司名称<input required value={jobForm.companyName} onChange={(e) => onJobFormChange({ ...jobForm, companyName: e.target.value })} placeholder="公司" /></label>
            </div>
            <div className="field-row">
              <label>工作地点<input value={jobForm.location} onChange={(e) => onJobFormChange({ ...jobForm, location: e.target.value })} placeholder="杭州" /></label>
              <label>用工类型<input value={jobForm.employmentType} onChange={(e) => onJobFormChange({ ...jobForm, employmentType: e.target.value })} /></label>
            </div>
            <label>岗位描述<textarea required rows={4} value={jobForm.description} onChange={(e) => onJobFormChange({ ...jobForm, description: e.target.value })} placeholder="岗位职责、业务方向…" /></label>
            <label>任职要求<textarea rows={4} value={jobForm.requirements} onChange={(e) => onJobFormChange({ ...jobForm, requirements: e.target.value })} placeholder="技术栈、经验要求…" /></label>
            <div className="form-actions">
              <button className="secondary" disabled={!!busy}>{busy === "job" ? "保存中…" : editingJobId ? "更新职位" : "保存职位"}</button>
              {editingJobId && (
                <button className="ghost" type="button" onClick={onResetJobEditor}>取消编辑</button>
              )}
              <button className="ghost" type="button" onClick={() => onSetShowBulkImport((v) => !v)} disabled={!!busy}>
                {showBulkImport ? "收起批量导入" : "批量导入"}
              </button>
            </div>
            {showBulkImport && (
              <div className="bulk-import-panel">
                <div className="bulk-import-head">
                  <strong>批量导入 JD</strong>
                  <small>支持 JSON 数组 / {"{ items }"} / NDJSON</small>
                </div>
                <textarea
                  rows={8}
                  value={bulkImportText}
                  onChange={(e) => onBulkImportTextChange(e.target.value)}
                  placeholder='[{"title":"...","companyName":"...","description":"..."}]'
                />
                <div className="form-actions">
                  <button className="secondary" type="button" disabled={!!busy} onClick={onBulkImportJobs}>
                    {busy === "bulk-import" ? "导入中…" : "确认导入"}
                  </button>
                  <button className="ghost" type="button" onClick={() => onBulkImportTextChange(DEFAULT_BULK_IMPORT_TEXT)}>
                    填入示例 JSON
                  </button>
                </div>
              </div>
            )}
          </form>
          <details className="sample-action">
            <summary>用示例数据体验</summary>
            <p>会在当前账号中保存示例简历和岗位，并提交一次分析。</p>
            <button className="ghost" disabled={!!busy} onClick={onSaveSampleAndAnalyze}>保存示例并分析</button>
          </details>
        </div>
      )}

      {!workspaceLoading && (jobAnalysesLoading || jobsLoadingMore || jobSemanticStatus === "loading") && (
        <LoadingState compact text="正在整理完整岗位库…" />
      )}
      {jobs.length > 0 && (jobAnalysisDataRequired || jobSemanticDataRequired) && !selectedResumeId && (
        <EmptyState compact icon={FileText} title="请先选择一份简历" text="选好简历后，才能按语义相关度或分析结果排序。" />
      )}
      {jobAnalysisDataRequired && jobAnalysesStatus === "error" && (
        <ErrorState
          compact
          text={`匹配信息加载失败：${jobAnalysesError || "请稍后重试"}`}
          onRetry={selectedResumeId ? () => onRetryJobAnalyses(Number(selectedResumeId)) : undefined}
        />
      )}
      {jobSemanticDataRequired && jobSemanticStatus === "error" && (
        <ErrorState
          compact
          text={`向量粗排失败：${jobSemanticError || "请稍后重试"}`}
          onRetry={selectedResumeId ? () => onRetrySemanticMatches(Number(selectedResumeId)) : undefined}
        />
      )}
      {jobs.length > 0 && !libraryRankingReady && (
        <p className="review-caveat" role="status">
          匹配数据尚未就绪，以下按最近保存展示全部已加载岗位；匹配排序和「仅看未分析」暂未应用。
        </p>
      )}

      {workspaceLoading ? (
        <Skeleton className="skeleton-rows" label="正在读取岗位" lines={4} />
      ) : jobs.length === 0 ? (
        <div className="state-panel">
          <EmptyState
            icon={BriefcaseBusiness}
            art="empty-jobs"
            title="职位库还是空的"
            text="添加一个目标岗位，或用浏览器插件从招聘页一键导入。"
            action={<button className="primary" type="button" onClick={() => { onResetJobEditor(); onSetShowJobEditor(true); }}><Plus size={16} aria-hidden="true" />添加岗位</button>}
          />
        </div>
      ) : libraryJobs.length === 0 ? (
        <EmptyState
          compact
          icon={ListChecks}
          title="这份简历的岗位都分析过了"
          text="取消「仅看未分析」可以查看全部岗位。"
          action={<button className="ghost" type="button" onClick={() => onJobFilterChange("all")}>显示全部岗位</button>}
        />
      ) : (
        <div className="job-list">
          {libraryJobs.map((item) => (
            <JobCard
              key={item.id}
              job={item}
              analysis={latestAnalysisByJob.get(item.id)}
              similarity={semanticSimilarityByJob.get(item.id)}
              prefer={jobSort === "semantic" ? "semantic" : "score"}
              selected={selectedJobId === item.id}
              saved={savedJobs.includes(item.id)}
              disabled={!!busy}
              onUse={() => onUseJob(item.id)}
              onToggleSave={() => onToggleBookmark(item.id)}
              onEdit={() => onBeginEditJob(item)}
              onDelete={() => onDeleteJob(item.id)}
            />
          ))}
        </div>
      )}

      {jobs.length < effectiveJobsTotal && (
        <div className="entity-load-more">
          <button className="ghost" type="button" disabled={jobsLoadingMore} onClick={onLoadMoreJobs}>
            {jobsLoadingMore
              ? <><LoaderCircle className="spin" size={14} aria-hidden="true" />加载中…</>
              : `继续加载（还有 ${effectiveJobsTotal - jobs.length} 个）`}
          </button>
        </div>
      )}
    </section>
  );
}
