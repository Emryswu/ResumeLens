import { ArrowRight, ArrowUpRight, BriefcaseBusiness, CircleAlert, FileText, LoaderCircle, Plus, RotateCw, Upload } from "lucide-react";
import type { AiStatus, Analysis, Job, Resume } from "../../lib/api";
import { pendingAnalysisLabel, runtimeModeLabel } from "../../runtime-mode";
import type { ReportPanel } from "../../view-states";
import type { HashView } from "../../workspace-nav";
import { BrandCtaCard } from "../BrandArt";
import { MatchReport } from "../MatchReport";
import { RetryButton, Skeleton } from "../StateBlock";

type MatchReportSectionProps = {
  analysis: Analysis | null;
  jobs: Job[];
  savedJobs: number[];
  busy: string;
  fetchingReport: boolean;
  aiStatus: AiStatus | null;
  selectedResumeId: number | "";
  selectedJobId: number | "";
  selectedJob?: Job;
  reportPanel: ReportPanel;
  reportFetchErrorMessage?: string;
  onOpenWorkbench: () => void;
  onBookmark: (jobId: number) => void;
  onExportMarkdown: () => void;
  onExportPdf: () => void;
  onSaveCopy: (copy: Resume) => void;
  onRunAnalysis: () => void;
  onRefreshWorkspace: () => void;
  onRetryReportFetch: () => void;
  onNavigate: (next: HashView) => void;
};

export function MatchReportSection({
  analysis,
  jobs,
  savedJobs,
  busy,
  fetchingReport,
  aiStatus,
  selectedResumeId,
  selectedJobId,
  selectedJob,
  reportPanel,
  reportFetchErrorMessage,
  onOpenWorkbench,
  onBookmark,
  onExportMarkdown,
  onExportPdf,
  onSaveCopy,
  onRunAnalysis,
  onRefreshWorkspace,
  onRetryReportFetch,
  onNavigate,
}: MatchReportSectionProps) {
  const showCompletedReport = analysis?.status === "COMPLETED" && busy !== "analysis";
  const matchedJob = showCompletedReport
    ? jobs.find((item) => item.id === analysis.jobDescriptionId)
    : undefined;
  const canRerun = Boolean(
    !busy
    && selectedResumeId
    && selectedJobId
    && analysis
    && analysis.resumeId === selectedResumeId
    && analysis.jobDescriptionId === selectedJobId,
  );

  return (
    <div className="match-main">
      <div className="report-toolbar">
        <button className="ghost" type="button" disabled={busy === "analysis"} onClick={onOpenWorkbench}>
          <Plus size={16} aria-hidden="true" />新建匹配
        </button>
      </div>
      <section
        className="match-report"
        id="result"
        aria-label="岗位匹配报告"
        aria-busy={busy === "analysis" || fetchingReport || analysis?.status === "PENDING"}
      >
        {showCompletedReport ? (
          <MatchReport
            key={analysis.id}
            analysis={analysis}
            job={matchedJob}
            saved={savedJobs.includes(analysis.jobDescriptionId)}
            disabled={!!busy}
            onBookmark={() => onBookmark(analysis.jobDescriptionId)}
            onExportMarkdown={onExportMarkdown}
            onExportPdf={onExportPdf}
            onSaveCopy={onSaveCopy}
          />
        ) : (
          <ReportStatePanel
            reportPanel={reportPanel}
            aiStatus={aiStatus}
            analysis={analysis}
            busy={busy}
            selectedResumeId={selectedResumeId}
            selectedJobId={selectedJobId}
            selectedJob={selectedJob}
            reportFetchErrorMessage={reportFetchErrorMessage}
            onRetryReportFetch={onRetryReportFetch}
            onRunAnalysis={onRunAnalysis}
            onRefreshWorkspace={onRefreshWorkspace}
            onOpenWorkbench={onOpenWorkbench}
            onNavigate={onNavigate}
          />
        )}
        {analysis?.status === "COMPLETED" && (
          <div className="report-rerun">
            <small>{runtimeModeLabel(aiStatus)}</small>
            <button className="text-action" disabled={!canRerun} onClick={onRunAnalysis}>
              <RotateCw size={16} aria-hidden="true" />重新分析当前组合
            </button>
          </div>
        )}
      </section>
      <BrandCtaCard />
    </div>
  );
}

type ReportStatePanelProps = {
  reportPanel: ReportPanel;
  aiStatus: AiStatus | null;
  analysis: Analysis | null;
  busy: string;
  selectedResumeId: number | "";
  selectedJobId: number | "";
  selectedJob?: Job;
  reportFetchErrorMessage?: string;
  onRetryReportFetch: () => void;
  onRunAnalysis: () => void;
  onRefreshWorkspace: () => void;
  onOpenWorkbench: () => void;
  onNavigate: (next: HashView) => void;
};

function ReportStatePanel({
  reportPanel,
  aiStatus,
  analysis,
  busy,
  selectedResumeId,
  selectedJobId,
  selectedJob,
  reportFetchErrorMessage,
  onRetryReportFetch,
  onRunAnalysis,
  onRefreshWorkspace,
  onOpenWorkbench,
  onNavigate,
}: ReportStatePanelProps) {
  if (reportPanel === "workspace-loading") {
    return <Skeleton className="report-skeleton" label="正在读取工作台" lines={4} />;
  }

  const waiting = reportPanel === "analyzing" || reportPanel === "fetching";
  const pairReady = !busy && !!selectedResumeId && !!selectedJobId;
  const view = describeReportState({
    reportPanel,
    aiStatus,
    analysis,
    pairReady,
    selectedResumeId,
    selectedJob,
    reportFetchErrorMessage,
    onRetryReportFetch,
    onRunAnalysis,
    onOpenWorkbench,
    onNavigate,
  });
  const Icon = view.error ? CircleAlert : view.icon;

  return (
    <div className={`report-state${view.error ? " is-error" : ""}`} role={view.error ? "alert" : "status"}>
      <span className="report-state-mark" aria-hidden="true">
        {waiting ? <LoaderCircle className="spin" size={24} /> : Icon && <Icon size={24} />}
      </span>
      <h2 title={view.title}>{view.title}</h2>
      {view.text && <p>{view.text}</p>}
      {waiting ? (
        <>
          <progress aria-label="匹配处理中" />
          <button className="ghost" disabled={!!busy} onClick={onRefreshWorkspace}>刷新报告状态</button>
        </>
      ) : (
        view.action
      )}
      <small>{runtimeModeLabel(aiStatus)}</small>
    </div>
  );
}

function describeReportState({
  reportPanel,
  aiStatus,
  analysis,
  pairReady,
  selectedResumeId,
  selectedJob,
  reportFetchErrorMessage,
  onRetryReportFetch,
  onRunAnalysis,
  onOpenWorkbench,
  onNavigate,
}: {
  reportPanel: ReportPanel;
  aiStatus: AiStatus | null;
  analysis: Analysis | null;
  pairReady: boolean;
  selectedResumeId: number | "";
  selectedJob?: Job;
  reportFetchErrorMessage?: string;
  onRetryReportFetch: () => void;
  onRunAnalysis: () => void;
  onOpenWorkbench: () => void;
  onNavigate: (next: HashView) => void;
}) {
  switch (reportPanel) {
    case "analyzing":
      return { title: "正在逐项核对岗位要求", text: pendingAnalysisLabel(aiStatus) };
    case "fetching":
      return { title: "正在读取匹配报告…", text: "正在读取当前简历与岗位对应的分析。" };
    case "fetch-error":
      return {
        error: true,
        title: "匹配报告没有读取出来",
        text: reportFetchErrorMessage,
        action: <RetryButton className="primary" label="重新读取" onClick={onRetryReportFetch} />,
      };
    case "failed":
      return {
        error: true,
        title: "这次分析没有完成",
        text: analysis?.summary || "输入仍然保留，可以重试分析。",
        action: (
          <button className="primary" type="button" disabled={!pairReady} onClick={onRunAnalysis}>
            <RotateCw size={16} aria-hidden="true" />重试分析
          </button>
        ),
      };
    case "no-resume":
      return {
        icon: FileText,
        title: "还没有简历，上传一份",
        text: "上传文件或粘贴简历正文，保存后就能和目标岗位匹配。",
        action: (
          <button className="primary" type="button" onClick={() => onNavigate("resumes")}>
            <Upload size={16} aria-hidden="true" />上传简历
          </button>
        ),
      };
    case "no-job":
      return {
        icon: BriefcaseBusiness,
        title: "还没有目标岗位，添加一个",
        text: "录入岗位描述后，这里会生成匹配报告和简历里的原文证据。",
        action: (
          <button className="primary" type="button" onClick={() => onNavigate("jobs")}>
            <Plus size={16} aria-hidden="true" />添加岗位
          </button>
        ),
      };
    default:
      return {
        icon: ArrowUpRight,
        title: selectedJob ? selectedJob.title : "先选一个目标岗位",
        text: selectedJob
          ? `${selectedJob.companyName}${selectedJob.location ? ` · ${selectedJob.location}` : ""} · ${selectedResumeId ? "准备好后，生成你的匹配报告。" : "先在上方选择一份简历。"}`
          : "回到工作台选择简历和岗位，生成你的匹配报告。",
        action: (
          <button className="primary" type="button" onClick={onOpenWorkbench}>
            回到工作台<ArrowRight size={16} aria-hidden="true" />
          </button>
        ),
      };
  }
}
